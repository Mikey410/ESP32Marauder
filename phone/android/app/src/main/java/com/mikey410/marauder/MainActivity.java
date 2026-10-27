package com.mikey410.marauder;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.hoho.android.usbserial.driver.*;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/** Local USB host console. No network permissions or persistent transcript. */
public class MainActivity extends Activity {
    private static final String PERMISSION = "com.mikey410.marauder.USB_PERMISSION";
    private final ExecutorService usbWorker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    // All transport operations are serialized on usbWorker; callbacks only queue text.
    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private SerialInputOutputManager reader;
    private volatile int generation;
    private volatile boolean connected;
    private boolean opening;
    private int activeDevice = -1, pendingDevice = -1;
    private TextView status, output;
    private EditText command;
    private ScrollView scroll;
    private final Object textLock = new Object();
    private final StringBuilder pendingText = new StringBuilder();
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final ByteBuffer bytes = ByteBuffer.allocate(32768);
    private boolean flushScheduled;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) return;
            if (PERMISSION.equals(intent.getAction()) && device.getDeviceId() == pendingDevice) {
                pendingDevice = -1;
                if (usb.hasPermission(device)) open(device);
                else { opening = false; status.setText("USB permission denied · tap Connect to retry"); }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) &&
                       (device.getDeviceId() == activeDevice || device.getDeviceId() == pendingDevice)) {
                disconnect("Cable disconnected", false);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager)getSystemService(USB_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setBackgroundColor(Color.rgb(11,16,23));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(18) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(),
                dp(18) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = label("MARAUDER / USB", 24, 0xffffad64);
        root.addView(title);
        root.addView(label("ProS3  ·  Cable console", 13, 0xff98a7b8));
        status = label("Connect your ProS3 with a USB-C data cable", 13, 0xffc8d5e4);
        status.setPadding(0, dp(14), 0, dp(10)); root.addView(status);
        LinearLayout row = row(root);
        button(row, "Connect", this::chooseDevice);
        button(row, "Disconnect", () -> disconnect("Disconnected", true));
        row = row(root);
        button(row, "Wi-Fi scan", () -> send("scanall"));
        button(row, "BLE scan", () -> send("sniffbt"));
        button(row, "STOP", () -> send("stopscan"));
        row = row(root);
        button(row, "Networks", () -> send("list -a"));
        button(row, "Devices", () -> send("list -b"));
        button(row, "Help", () -> send("help"));
        scroll = new ScrollView(this);
        output = label("Ready. Connect, approve USB access, then choose a scan or enter a command.\n", 12, 0xffc3dfd0);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        scroll.setBackgroundColor(0xff121b25); scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        command = new EditText(this); command.setSingleLine(true);
        command.setTextColor(Color.WHITE); command.setHintTextColor(0xff8896a7);
        command.setHint("Marauder command…");
        command.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(command);
        row = row(root);
        button(row, "Send", () -> { if (send(command.getText().toString())) command.setText(""); });
        button(row, "Clear", () -> { synchronized(textLock) { pendingText.setLength(0); } output.setText(""); });
        command.setOnEditorActionListener((v, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (send(command.getText().toString())) command.setText(""); return true;
            } return false;
        });
        setContentView(root);
        IntentFilter filter = new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density * n); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this); parent.addView(row); return row;
    }
    private void button(LinearLayout row, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setTextSize(11); b.setAllCaps(false);
        row.addView(b, new LinearLayout.LayoutParams(0, dp(50), 1)); b.setOnClickListener(v -> action.run());
    }
    private void chooseDevice() {
        if (connected || opening) return;
        List<UsbSerialDriver> devices = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
        // Only Espressif CDC devices, never send Marauder commands to arbitrary USB serial gear.
        devices.removeIf(d -> d.getDevice().getVendorId() != 0x303a || !(d instanceof CdcAcmSerialDriver));
        if (devices.isEmpty()) { status.setText("No ProS3 found · check data cable / USB host mode / firmware"); return; }
        if (devices.size() == 1) request(devices.get(0).getDevice());
        else {
            String[] names = new String[devices.size()];
            for (int i = 0; i < names.length; i++) names[i] = "Espressif USB · " + devices.get(i).getDevice().getDeviceName();
            new AlertDialog.Builder(this).setTitle("Choose your ProS3").setItems(names,
                (dialog, which) -> request(devices.get(which).getDevice())).show();
        }
    }
    private void request(UsbDevice device) {
        opening = true; pendingDevice = device.getDeviceId();
        if (usb.hasPermission(device)) { pendingDevice = -1; open(device); return; }
        status.setText("Approve Android USB access…");
        Intent intent = new Intent(PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags));
    }
    private void open(UsbDevice device) {
        final int session = ++generation; activeDevice = device.getDeviceId();
        status.setText("Opening USB console…");
        usbWorker.execute(() -> {
            try {
                closeTransport();
                if (session != generation) return;
                UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
                if (driver == null || driver.getPorts().isEmpty()) throw new Exception("No CDC serial port");
                connection = usb.openDevice(device);
                if (connection == null) throw new Exception("USB permission or device unavailable");
                port = driver.getPorts().get(0); port.open(connection);
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                port.setRTS(false); port.setDTR(true);
                synchronized (textLock) { decoder.reset(); bytes.clear(); }
                reader = new SerialInputOutputManager(port, new SerialInputOutputManager.Listener() {
                    public void onNewData(byte[] data) { if (session == generation) receive(data); }
                    public void onRunError(Exception e) { ui.post(() -> {
                        if (session == generation) disconnect("USB read failed · reconnect cable", false);
                    }); }
                });
                reader.start();
                ui.post(() -> {
                    if (session != generation) return;
                    connected = true; opening = false;
                    status.setText("Connected · USB 115200 · ProS3 console");
                    append("\n[USB connected. Tap Help for commands.]\n");
                });
            } catch (Exception e) {
                closeTransport();
                ui.post(() -> { if (session == generation) disconnect("Connection failed: " + e.getMessage(), false); });
            }
        });
    }
    private boolean send(String text) {
        if (!connected) { status.setText("Connect the ProS3 first"); return false; }
        String line = text.trim(); byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.isEmpty()) return false;
        if (payload.length > 513 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0) {
            append("\n[One command per send; maximum 512 UTF-8 bytes.]\n"); return false;
        }
        final int session = generation;
        usbWorker.execute(() -> {
            if (session != generation || port == null) return;
            try { port.write(payload, 1500); append("\n> " + line + "\n"); }
            catch (Exception e) { ui.post(() -> { if (session == generation) disconnect("USB write failed · reconnect", false); }); }
        });
        return true;
    }
    private void receive(byte[] data) {
        synchronized (textLock) {
            // Reader delivers small chunks; preserve incomplete UTF-8 sequences across packets.
            for (byte b : data) {
                if (!bytes.hasRemaining()) decodePending();
                bytes.put(b);
            }
            decodePending(); scheduleFlush();
        }
    }
    private void decodePending() {
        bytes.flip(); CharBuffer chars = CharBuffer.allocate(32768);
        decoder.decode(bytes, chars, false); bytes.compact(); chars.flip();
        pendingText.append(chars); boundPending();
    }
    private void append(String text) {
        synchronized(textLock) { pendingText.append(text); boundPending(); scheduleFlush(); }
    }
    private void boundPending() {
        if (pendingText.length() > 65536) pendingText.delete(0, pendingText.length() - 49152);
    }
    private void scheduleFlush() {
        if (flushScheduled) return; flushScheduled = true;
        ui.postDelayed(() -> {
            String chunk;
            synchronized(textLock) { chunk = pendingText.toString(); pendingText.setLength(0); flushScheduled = false; }
            boolean atBottom = !scroll.canScrollVertically(1);
            output.append(chunk);
            if (output.length() > 65536) output.setText(output.getText().subSequence(output.length() - 49152, output.length()));
            if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }, 100);
    }
    private void disconnect(String reason, boolean stopScan) {
        generation++; connected = false; opening = false; pendingDevice = -1; activeDevice = -1;
        status.setText(reason);
        usbWorker.execute(() -> {
            if (stopScan && port != null) {
                try { port.write("stopscan\n".getBytes(StandardCharsets.UTF_8), 300); } catch (Exception ignored) { }
            }
            closeTransport();
        });
    }
    private void closeTransport() {
        if (reader != null) { reader.stop(); reader = null; }
        if (port != null) { try { port.close(); } catch (Exception ignored) { } port = null; }
        if (connection != null) { connection.close(); connection = null; }
    }
    @Override protected void onStop() {
        super.onStop();
        // The OS USB permission dialog can temporarily pause the activity.
        if (!opening && connected) disconnect("Paused · tap Connect to resume", true);
    }
    @Override protected void onDestroy() {
        disconnect("Closed", true); unregisterReceiver(receiver);
        ui.removeCallbacksAndMessages(null); usbWorker.shutdown(); super.onDestroy();
    }
}
