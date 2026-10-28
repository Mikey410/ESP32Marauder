package com.mikey410.marauder;

import android.app.*;
import androidx.core.content.ContextCompat;
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

/** Local USB tool dashboard. No network permissions or persistent transcript. */
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
        buildDashboard(root);
        setContentView(root);
        IntentFilter filter = new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    private LinearLayout toolsPage, toolList, resultCards;
    private ScrollView resultsPage, toolsScroll;
    private TextView activityLabel;
    private String category="Wi-Fi";
    private final ToolResults results=new ToolResults();
    private final List<Button> launchButtons=new ArrayList<>();
    private void buildDashboard(LinearLayout root) {
        root.addView(label("MARAUDER",26,0xffffad64));
        root.addView(label("PROS3  /  USB TOOLKIT",12,0xff98a7b8));
        status=label("Connect your ProS3 with a USB-C data cable",13,0xffc8d5e4);
        status.setPadding(0,dp(10),0,dp(6)); root.addView(status);
        LinearLayout controls=row(root);
        button(controls,"Connect",this::chooseDevice);
        button(controls,"Disconnect",()->disconnect("Disconnected",true));
        button(controls,"Stop",()->{if(send("stopscan"))activityLabel.setText("Stop requested · check device response in Results");});
        activityLabel=label("Ready · choose a tool below",12,0xffa7bdce);root.addView(activityLabel);
        LinearLayout nav=row(root);
        button(nav,"Tools",()->showResults(false));button(nav,"Results",()->showResults(true));button(nav,"Advanced",this::advanced);
        toolsPage=new LinearLayout(this);toolsPage.setOrientation(LinearLayout.VERTICAL);
        root.addView(toolsPage,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView categories=new HorizontalScrollView(this);categories.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=new LinearLayout(this);categories.addView(chips);toolsPage.addView(categories);
        Set<String> names=new LinkedHashSet<>();for(ToolCatalog.Tool t:ToolCatalog.ALL)names.add(t.category);
        for(String name:names){Button chip=new Button(this);chip.setText(name);chip.setAllCaps(false);chips.addView(chip);
            chip.setOnClickListener(v->{category=name;renderTools();toolsScroll.smoothScrollTo(0,0);});}
        toolsScroll=new ScrollView(this);toolList=new LinearLayout(this);toolList.setOrientation(LinearLayout.VERTICAL);toolsScroll.addView(toolList);
        toolsPage.addView(toolsScroll,new LinearLayout.LayoutParams(-1,0,1));
        resultsPage=new ScrollView(this);scroll=resultsPage;
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);resultsPage.addView(content);
        root.addView(resultsPage,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout refresh=row(content);button(refresh,"Networks",()->send("list -a"));button(refresh,"BLE",()->send("list -b"));
        button(refresh,"Clear view",()->{synchronized(textLock){pendingText.setLength(0);}results.clear();output.setText("");renderResults();});
        content.addView(label("Stop a scan, then refresh its list. Names below come from your device.",12,0xff98a7b8));
        resultCards=new LinearLayout(this);resultCards.setOrientation(LinearLayout.VERTICAL);content.addView(resultCards);
        content.addView(label("Device responses",18,0xffffad64));
        output=label("",13,0xffc3dfd0);output.setTextIsSelectable(true);output.setPadding(dp(8),dp(8),dp(8),dp(8));content.addView(output);
        renderTools();renderResults();showResults(false);
    }
    private void showResults(boolean show){toolsPage.setVisibility(show?View.GONE:View.VISIBLE);resultsPage.setVisibility(show?View.VISIBLE:View.GONE);}
    private LinearLayout card(LinearLayout parent,String title,String description){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(12),dp(14),dp(10));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xff172331);bg.setCornerRadius(dp(16));card.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(4));parent.addView(card,p);
        card.addView(label(title,18,0xfff0f4fa));TextView desc=label(description,13,0xffa9bdcf);desc.setPadding(0,dp(6),0,dp(8));card.addView(desc);return card;
    }
    private void renderTools(){
        if(toolList==null)return;toolList.removeAllViews();launchButtons.clear();
        toolList.addView(label(category,21,0xffffad64));
        for(ToolCatalog.Tool t:ToolCatalog.ALL)if(t.category.equals(category)){
            LinearLayout c=card(toolList,t.name,t.description);Button launch=new Button(this);launch.setAllCaps(false);
            launch.setText(t.template.isEmpty()?"Requires hardware":t.fields.length>0?"Configure":t.starts?"Start":"Open");
            launch.setEnabled(connected&&!t.template.isEmpty());launch.setOnClickListener(v->configure(t));c.addView(launch);launchButtons.add(launch);
        }
    }
    private void configure(ToolCatalog.Tool tool){
        if(!connected){status.setText("Connect the ProS3 first");return;}
        if(tool.fields.length==0){executeTool(tool,tool.command());return;}
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(10),dp(20),dp(8));
        List<java.util.function.Supplier<String>> readers=new ArrayList<>();
        for(ToolCatalog.Field f:tool.fields){
            TextView caption=new TextView(this);caption.setText(f.name);form.addView(caption);
            if(f.type.equals("choice")||f.type.equals("ap")){
                final List<String> values=new ArrayList<>(),labels=new ArrayList<>();
                if(f.type.equals("ap")){
                    if(results.networks.isEmpty()){new AlertDialog.Builder(this).setTitle("Discover networks first").setMessage("Start a Wi-Fi scan, tap Stop, then tap Networks in Results. Return here to choose a network by name.").setPositiveButton("OK",null).show();return;}
                    for(Map.Entry<String,String> e:results.networks.entrySet()){values.add(e.getKey());labels.add(e.getValue());}
                }else{values.addAll(Arrays.asList(f.choices));labels.addAll(values);}
                Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);form.addView(spinner);readers.add(()->values.get(spinner.getSelectedItemPosition()));
            }else{
                EditText input=new EditText(this);input.setSingleLine(true);
                input.setInputType(f.type.equals("number")?android.text.InputType.TYPE_CLASS_NUMBER:android.text.InputType.TYPE_CLASS_TEXT|(f.type.equals("password")?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
                form.addView(input);readers.add(()->input.getText().toString());
            }
        }
        TextView error=new TextView(this);error.setTextColor(0xffd04840);form.addView(error);
        ScrollView scroller=new ScrollView(this);scroller.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tool.name).setView(scroller).setNegativeButton("Cancel",null).setPositiveButton(tool.starts?"Start":"Apply",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{String[] values=new String[readers.size()];for(int i=0;i<values.length;i++)values[i]=readers.get(i).get();String cmd=tool.command(values);dialog.dismiss();executeTool(tool,cmd);}
            catch(IllegalArgumentException ex){error.setText(ex.getMessage());}
        }));dialog.show();
    }
    private void executeTool(ToolCatalog.Tool tool,String cmd){
        Runnable run=()->{if(tool.starts&&!send("stopscan"))return;if(send(cmd)){activityLabel.setText("Requested: "+tool.name+" · see Results for device status");showResults(true);}};
        if(tool.confirm)new AlertDialog.Builder(this).setTitle(tool.name).setMessage(tool.description+"\n\nRun this action?").setNegativeButton("Cancel",null).setPositiveButton("Run",(d,w)->run.run()).show();else run.run();
    }
    private void renderResults(){
        if(resultCards==null)return;resultCards.removeAllViews();
        resultCards.addView(label(results.networks.size()+" networks  ·  "+results.ble.size()+" BLE devices",16,0xffffad64));
        int count=0;for(Map.Entry<String,String> e:results.networks.entrySet()){
            if(++count>60)break;String id=e.getKey();LinearLayout c=card(resultCards,"Wi-Fi · #"+id,e.getValue());LinearLayout r=row(c);
            button(r,"Details",()->send("info -a "+id));button(r,"Select / deselect",()->send("select -a "+id));
        }
        count=0;for(Map.Entry<String,String> e:results.ble.entrySet()){
            if(++count>60)break;card(resultCards,"BLE · #"+e.getKey(),e.getValue());
        }
        if(results.networks.size()>60||results.ble.size()>60)resultCards.addView(label("First 60 cards per type shown. More entries are in device responses; all cached networks appear in pickers.",12,0xff98a7b8));
    }
    private void advanced(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Marauder command");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this).setTitle("Advanced command").setMessage("For options without dashboard controls. Responses appear in Results.").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Send",(d,w)->{if(send(input.getText().toString()))showResults(true);}).show();
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
                    status.setText("Connected · USB · ProS3"); renderTools();
                    append("\n[USB connected. Choose a tool to begin.]\n");
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
            try { port.write(payload, 1500); append("\n> " + ToolResults.redact(line) + "\n"); }
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
            int previousRevision = results.revision;
            String parsed = results.accept(chunk);
            output.append(parsed);
            if (previousRevision != results.revision) renderResults();
            if (output.length() > 65536) output.setText(output.getText().subSequence(output.length() - 49152, output.length()));
            if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }, 100);
    }
    private void disconnect(String reason, boolean stopScan) {
        generation++; connected = false; opening = false; pendingDevice = -1; activeDevice = -1;
        status.setText(reason);
        synchronized(textLock) { pendingText.setLength(0); }
        results.clear(); renderTools(); renderResults();
        activityLabel.setText("Disconnected · reconnect to use tools");
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
