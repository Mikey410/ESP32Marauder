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
        root.setBackgroundColor(SURFACE);
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
    private static final int INK=0xff1c1c1e, MUTED=0xff767680, BLUE=0xff007aff, SURFACE=0xfff2f2f7;
    private LinearLayout toolList, resultCards, devicePage, activeBar, readings;
    private ScrollView resultsPage, toolsScroll, deviceScroll;
    private TextView activityLabel, pageTitle;
    private Button connectionButton, scanButton, wifiSegment, bleSegment;
    private final List<Button> navButtons=new ArrayList<>();
    private final ToolResults results=new ToolResults();
    private final ArrayList<String> recentReadings=new ArrayList<>();
    private String category="", query="", preferredAp="", activeTitle="";
    private boolean bluetooth=false;
    private String discoveryMode="";
    private AlertDialog readingDialog;

    private android.graphics.drawable.Drawable background(int color,int radius){
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;
    }
    private LinearLayout column(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);return c;}
    private TextView text(String title,int size,int color){TextView t=label(title,size,color);t.setFontFeatureSettings("kern");return t;}
    private void space(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private Button action(String title,boolean primary,Runnable callback){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(16);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setTextColor(primary?Color.WHITE:BLUE);b.setBackground(background(primary?BLUE:0xffe8f1ff,14));b.setMinHeight(dp(50));b.setMinimumHeight(dp(50));
        b.setPadding(dp(14),dp(10),dp(14),dp(10));b.setStateListAnimator(null);b.setOnClickListener(v->callback.run());return b;
    }
    private void section(LinearLayout parent,String name){TextView t=text(name,20,INK);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));t.setPadding(0,dp(22),0,dp(10));parent.addView(t);}
    private LinearLayout surface(LinearLayout parent){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(background(Color.WHITE,22));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(10));parent.addView(c,p);return c;}
    private void buildDashboard(LinearLayout root){
        getWindow().setStatusBarColor(SURFACE);getWindow().setNavigationBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);root.addView(top);
        TextView brand=text("Marauder",15,MUTED);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        connectionButton=action("Connect",false,()->{if(connected)showTab("Device");else chooseDevice();});connectionButton.setTextSize(13);connectionButton.setMinHeight(dp(44));connectionButton.setMinimumHeight(dp(44));top.addView(connectionButton);
        pageTitle=text("Nearby",34,INK);pageTitle.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));pageTitle.setPadding(0,dp(14),0,dp(16));root.addView(pageTitle);
        status=text("Connect your ProS3 to get started.",13,MUTED);status.setPadding(0,0,0,dp(10));root.addView(status);
        FrameLayout pages=new FrameLayout(this);root.addView(pages,new LinearLayout.LayoutParams(-1,0,1));
        resultsPage=new ScrollView(this);resultsPage.setFillViewport(true);resultsPage.setVerticalScrollBarEnabled(false);scroll=resultsPage;
        LinearLayout nearby=column();resultsPage.addView(nearby);pages.addView(resultsPage);
        LinearLayout segments=new LinearLayout(this);segments.setPadding(dp(4),dp(4),dp(4),dp(4));segments.setBackground(background(0xffe5e5ea,14));nearby.addView(segments);
        wifiSegment=action("Wi-Fi",false,()->setDiscovery(false));bleSegment=action("Bluetooth",false,()->setDiscovery(true));
        segments.addView(wifiSegment,new LinearLayout.LayoutParams(0,-2,1));segments.addView(bleSegment,new LinearLayout.LayoutParams(0,-2,1));space(nearby,16);
        scanButton=action("Find networks",true,this::startDiscovery);nearby.addView(scanButton,new LinearLayout.LayoutParams(-1,-2));space(nearby,16);
        resultCards=column();nearby.addView(resultCards);
        toolsScroll=new ScrollView(this);toolsScroll.setVerticalScrollBarEnabled(false);LinearLayout toolsContent=column();toolsScroll.addView(toolsContent);pages.addView(toolsScroll);
        EditText search=new EditText(this);search.setSingleLine(true);search.setTextSize(16);search.setHint("Find a tool");search.setTextColor(INK);search.setHintTextColor(MUTED);search.setPadding(dp(16),dp(12),dp(16),dp(12));search.setBackground(background(0xffe5e5ea,14));toolsContent.addView(search);
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString().trim();renderTools();}public void afterTextChanged(android.text.Editable e){}});
        toolList=column();toolsContent.addView(toolList);
        deviceScroll=new ScrollView(this);deviceScroll.setVerticalScrollBarEnabled(false);devicePage=column();deviceScroll.addView(devicePage);pages.addView(deviceScroll);
        activeBar=new LinearLayout(this);activeBar.setGravity(Gravity.CENTER_VERTICAL);activeBar.setPadding(dp(12),dp(8),dp(8),dp(8));activeBar.setBackground(background(Color.WHITE,18));
        activityLabel=text("",13,INK);activityLabel.setMaxLines(2);activityLabel.setOnClickListener(v->openReadings("Device readings"));activeBar.addView(activityLabel,new LinearLayout.LayoutParams(0,-2,1));
        Button stop=action("Stop",false,this::stopActivity);stop.setTextColor(0xffd93338);activeBar.addView(stop);root.addView(activeBar);activeBar.setVisibility(View.GONE);
        LinearLayout nav=new LinearLayout(this);nav.setPadding(0,dp(10),0,0);root.addView(nav);
        for(String tab:new String[]{"Nearby","Tools","Device"}){
            Button b=action(tab,false,()->showTab(tab));b.setTextSize(12);b.setCompoundDrawablesWithIntrinsicBounds(null,new AppIcon(tab,dp(23),BLUE),null,null);b.setCompoundDrawablePadding(dp(5));nav.addView(b,new LinearLayout.LayoutParams(0,-2,1));navButtons.add(b);
        }
        // Transport keeps a bounded private buffer; no console is attached to the UI.
        output=new TextView(this);
        renderTools();renderResults();renderDevice();showTab("Nearby");
    }
    private void showTab(String tab){
        pageTitle.setText(tab);resultsPage.setVisibility(tab.equals("Nearby")?View.VISIBLE:View.GONE);toolsScroll.setVisibility(tab.equals("Tools")?View.VISIBLE:View.GONE);deviceScroll.setVisibility(tab.equals("Device")?View.VISIBLE:View.GONE);
        for(Button b:navButtons){boolean selected=b.getText().toString().equals(tab);b.setTextColor(selected?BLUE:MUTED);b.setBackground(background(selected?0xffe6efff:SURFACE,16));b.setCompoundDrawablesWithIntrinsicBounds(null,new AppIcon(b.getText().toString(),dp(23),selected?BLUE:MUTED),null,null);}
        if(tab.equals("Device"))renderDevice();
    }
    private void setDiscovery(boolean value){bluetooth=value;renderResults();}
    private void renderResults(){
        if(resultCards==null)return;
        connectionButton.setText(connected?"Connected":"Connect");
        wifiSegment.setBackground(background(!bluetooth?Color.WHITE:0xffe5e5ea,11));bleSegment.setBackground(background(bluetooth?Color.WHITE:0xffe5e5ea,11));wifiSegment.setTextColor(!bluetooth?INK:MUTED);bleSegment.setTextColor(bluetooth?INK:MUTED);
        scanButton.setText(!discoveryMode.isEmpty()?"Finish scan":bluetooth?"Find devices":"Find networks");
        resultCards.removeAllViews();Map<String,String> data=bluetooth?results.ble:results.networks;
        if(data.isEmpty()){
            LinearLayout empty=surface(resultCards);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(34),dp(24),dp(34));
            ImageView icon=new ImageView(this);icon.setImageDrawable(new AppIcon(bluetooth?"Bluetooth":"Wi-Fi",dp(48),BLUE));empty.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));space(empty,20);
            TextView title=text(!connected?"Your surroundings, at a glance":!discoveryMode.isEmpty()?"Ready when you are":"See what's nearby",21,INK);title.setGravity(Gravity.CENTER);empty.addView(title);space(empty,10);
            TextView sub=text(!connected?"Connect your ProS3 with a USB-C cable.":!discoveryMode.isEmpty()?"Give it a moment, then finish the scan to see what was found.":bluetooth?"Find nearby Bluetooth devices with one tap.":"Find nearby Wi-Fi networks with one tap.",15,MUTED);sub.setGravity(Gravity.CENTER);empty.addView(sub);return;
        }
        TextView count=text(data.size()+(bluetooth?" devices":" networks"),13,MUTED);count.setPadding(dp(4),0,0,dp(12));resultCards.addView(count);
        List<ResultPresentation> items=new ArrayList<>();for(String raw:data.values())items.add(ResultPresentation.parse(raw,bluetooth));items.sort((a,b)->Integer.compare(b.rssi,a.rssi));
        for(ResultPresentation item:items){
            LinearLayout cell=surface(resultCards);cell.setOrientation(LinearLayout.HORIZONTAL);cell.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon=new ImageView(this);icon.setImageDrawable(new AppIcon(bluetooth?"Bluetooth":"Wi-Fi",dp(26),BLUE));cell.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
            LinearLayout copy=column();copy.setPadding(dp(12),0,dp(8),0);TextView name=text(item.name,17,INK);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(name);space(copy,4);copy.addView(text(item.subtitle+(item.selected?" · Selected":""),13,MUTED));cell.addView(copy,new LinearLayout.LayoutParams(0,-2,1));cell.addView(text("›",25,0xffb6b6bd));
            boolean isBle=bluetooth;cell.setOnClickListener(v->deviceDetails(item,isBle));cell.setContentDescription(item.name+", "+item.subtitle);cell.setFocusable(true);
        }
    }
    private void startDiscovery(){
        if(!connected){chooseDevice();return;}
        if(!discoveryMode.isEmpty()){stopActivity();return;}
        beginReadout(bluetooth?"Bluetooth scan":"Wi-Fi scan");
        if(send("stopscan")&&send(bluetooth?"sniffbt":"scanall")){
            discoveryMode=bluetooth?"ble":"wifi";if(bluetooth)results.ble.clear();else results.networks.clear();
            markActivity(bluetooth?"Bluetooth scan":"Wi-Fi scan");renderResults();
        }
    }
    private void markActivity(String name){activeTitle=name;activityLabel.setText(name+" requested\nTap for readings");activeBar.setVisibility(View.VISIBLE);}
    private void stopActivity(){
        if(!send("stopscan"))return;
        String mode=discoveryMode;discoveryMode="";activeBar.setVisibility(View.GONE);
        if(!mode.isEmpty()){bluetooth=mode.equals("ble");send(bluetooth?"list -b":"list -a");showTab("Nearby");}
        Toast.makeText(this,"Stop requested",Toast.LENGTH_SHORT).show();renderResults();
    }
    private void deviceDetails(ResultPresentation item,boolean ble){
        String[] options=ble?new String[]{"Find signal"}:new String[]{"Network details","Join network",item.selected?"Deselect":"Select","Find signal"};
        new AlertDialog.Builder(this).setTitle(item.name).setItems(options,(d,which)->{
            if(!connected){chooseDevice();return;}
            if(ble){runDirect("Find signal","foxhunt -b "+item.id,true);return;}
            if(which==0)runDirect(item.name,"info -a "+item.id,false);
            if(which==1){preferredAp=item.id;configure(findTool("join -a {0} -p {1}"));preferredAp="";}
            if(which==2){send("select -a "+item.id);send("list -a");}
            if(which==3)runDirect("Find signal","foxhunt -w "+item.id,true);
        }).setNegativeButton("Done",null).show();
    }
    private ToolCatalog.Tool findTool(String template){for(ToolCatalog.Tool t:ToolCatalog.ALL)if(t.template.equals(template))return t;throw new IllegalArgumentException("Unknown tool");}
    private void renderTools(){
        if(toolList==null)return;toolList.removeAllViews();
        if(category.isEmpty()&&query.isEmpty()){
            section(toolList,"Explore");String[] names={"Wi-Fi","Bluetooth","Monitor","Network","Targets","Remote ID","Lab","Device","Hardware"};
            LinearLayout pair=null;for(int i=0;i<names.length;i++){String name=names[i];if(i%2==0){pair=new LinearLayout(this);toolList.addView(pair);}
                LinearLayout tile=column();tile.setPadding(dp(18),dp(20),dp(14),dp(20));tile.setBackground(background(Color.WHITE,20));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(i%2==0?0:dp(6),0,i%2==0?dp(6):0,dp(12));pair.addView(tile,p);
                ImageView icon=new ImageView(this);icon.setImageDrawable(new AppIcon(name,dp(28),BLUE));tile.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));space(tile,16);tile.addView(text(name,17,INK));tile.setOnClickListener(v->{category=name;renderTools();toolsScroll.smoothScrollTo(0,0);});tile.setContentDescription(name+" tools");tile.setFocusable(true);
            }return;
        }
        if(!category.isEmpty()&&query.isEmpty()){Button back=action("‹  All tools",false,()->{category="";renderTools();});toolList.addView(back);section(toolList,category);}else section(toolList,"Search results");
        int matches=0;for(ToolCatalog.Tool t:ToolCatalog.ALL){
            if(query.isEmpty()?!t.category.equals(category):!(t.name+" "+t.category).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;matches++;
            LinearLayout line=surface(toolList);line.setOrientation(LinearLayout.HORIZONTAL);line.setGravity(Gravity.CENTER_VERTICAL);
            TextView title=text(t.name,16,INK);line.addView(title,new LinearLayout.LayoutParams(0,-2,1));line.addView(text(t.template.isEmpty()?"Accessory":"›",t.template.isEmpty()?12:24,MUTED));
            line.setOnClickListener(v->{if(t.template.isEmpty())new AlertDialog.Builder(this).setTitle(t.name).setMessage(t.description).setPositiveButton("Done",null).show();else configure(t);});line.setFocusable(true);line.setContentDescription(t.name);
        }
        if(matches==0)toolList.addView(text("No matching tools",16,MUTED));
    }
    private void renderDevice(){
        if(devicePage==null)return;devicePage.removeAllViews();LinearLayout c=surface(devicePage);
        c.addView(text("ProS3",26,INK));space(c,6);c.addView(text(connected?"Connected by USB":"Not connected",15,MUTED));space(c,20);
        c.addView(action(connected?"Disconnect":"Connect",!connected,()->{if(connected)disconnect("Disconnected",true);else chooseDevice();}));
        section(devicePage,"Preferences");
        for(String[] item:new String[][]{{"Channel hopping","settings -s ChanHop {0}"},{"Radio channel","channel -s {0}"},{"Board settings","settings"},{"Restart board","reboot"}}){
            LinearLayout r=surface(devicePage);r.addView(text(item[0]+"  ›",16,INK));r.setOnClickListener(v->configure(findTool(item[1])));r.setFocusable(true);
        }
        LinearLayout advancedRow=surface(devicePage);advancedRow.addView(text("Advanced  ›",16,INK));advancedRow.setOnClickListener(v->advanced());advancedRow.setFocusable(true);
        section(devicePage,"About");LinearLayout about=surface(devicePage);about.addView(text("Marauder",17,INK));space(about,6);about.addView(text("Version 0.3 · USB connection",14,MUTED));
        about.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("About this app").setMessage("Controls the Marauder firmware on your ProS3 through a USB cable. GPS, SD card and on-board display features need additional hardware.").setPositiveButton("Done",null).show());
    }
    private void beginReadout(String title){recentReadings.clear();renderReadings();}
    private void runDirect(String title,String command,boolean starts){
        beginReadout(title);if(starts&&!send("stopscan"))return;if(!send(command))return;
        if(command.equals("clearlist -a")){results.networks.clear();renderResults();}
        if(starts){markActivity(title);discoveryMode="";renderResults();}openReadings(title);
    }
    private void openReadings(String title){
        if(readingDialog!=null)readingDialog.dismiss();ScrollView scrollView=new ScrollView(this);readings=column();readings.setPadding(dp(22),dp(8),dp(22),dp(16));scrollView.addView(readings);
        readingDialog=new AlertDialog.Builder(this).setTitle(title.isEmpty()?"Readings":title).setView(scrollView).setPositiveButton("Done",null).create();
        AlertDialog current=readingDialog;current.setOnDismissListener(d->{if(readingDialog==current){readings=null;readingDialog=null;}});renderReadings();readingDialog.show();
    }
    private void renderReadings(){
        if(readings==null)return;readings.removeAllViews();if(recentReadings.isEmpty()){readings.addView(text(connected?"Waiting for the device…":"Connect your ProS3 to continue.",16,MUTED));return;}
        for(String line:recentReadings){TextView t=text(line,15,INK);t.setPadding(0,dp(10),0,dp(10));t.setTextIsSelectable(true);readings.addView(t);View divider=new View(this);divider.setBackgroundColor(0xffe5e5ea);readings.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));}
    }
    private void acceptReadings(String parsed){
        boolean changed=false;for(String line:parsed.split("\n")){String clean=ResultPresentation.reading(line);if(clean.isEmpty())continue;if(recentReadings.size()>=32)recentReadings.remove(0);recentReadings.add(clean);changed=true;}
        if(changed)renderReadings();
    }
    private void configure(ToolCatalog.Tool tool){
        if(!connected){chooseDevice();return;}
        if(tool.fields.length==0){
            if(tool.confirm){executeTool(tool,tool.command());return;}
            new AlertDialog.Builder(this).setTitle(tool.name).setMessage(tool.description).setNegativeButton("Cancel",null).setPositiveButton(tool.starts?"Start":"Open",(d,w)->executeTool(tool,tool.command())).show();return;
        }
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(10),dp(20),dp(8));
        List<java.util.function.Supplier<String>> readers=new ArrayList<>();
        for(ToolCatalog.Field f:tool.fields){
            TextView caption=new TextView(this);caption.setText(f.name);form.addView(caption);
            if(f.type.equals("choice")||f.type.equals("ap")){
                final List<String> values=new ArrayList<>(),labels=new ArrayList<>();
                if(f.type.equals("ap")){
                    if(results.networks.isEmpty()){new AlertDialog.Builder(this).setTitle("Discover networks first").setMessage("Open Nearby, find networks, then finish the scan. You can then choose a network by name.").setPositiveButton("OK",null).show();return;}
                    for(Map.Entry<String,String> e:results.networks.entrySet()){values.add(e.getKey());labels.add(ResultPresentation.parse(e.getValue(),false).name+" · "+ResultPresentation.parse(e.getValue(),false).subtitle);}
                }else{values.addAll(Arrays.asList(f.choices));labels.addAll(values);}
                Spinner spinner=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);if(f.type.equals("ap")&&!preferredAp.isEmpty()&&values.contains(preferredAp))spinner.setSelection(values.indexOf(preferredAp));form.addView(spinner);readers.add(()->values.get(spinner.getSelectedItemPosition()));
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
        Runnable run=()->{
            if(cmd.equals("scanall")||cmd.equals("sniffbt")){
                bluetooth=cmd.equals("sniffbt");if(!discoveryMode.isEmpty())stopActivity();startDiscovery();showTab("Nearby");return;
            }
            if(cmd.equals("list -a")||cmd.equals("list -b")){bluetooth=cmd.equals("list -b");send(cmd);showTab("Nearby");return;}
            runDirect(tool.name,cmd,tool.starts);
        };
        if(tool.confirm)new AlertDialog.Builder(this).setTitle(tool.name).setMessage(tool.description+"\n\nRun this action?").setNegativeButton("Cancel",null).setPositiveButton("Run",(d,w)->run.run()).show();else run.run();
    }
    private void advanced(){
        new AlertDialog.Builder(this).setTitle("Advanced").setItems(new String[]{"Custom command","Full device response","Command reference","Stop activity","Clear phone results"},(dialog,which)->{
            if(which==0){EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Command");input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                new AlertDialog.Builder(this).setTitle("Custom command").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Send",(d,w)->runDirect("Command result",input.getText().toString(),false)).show();
            }else if(which==1){TextView detail=text(output.getText().toString(),14,INK);detail.setTextIsSelectable(true);detail.setPadding(dp(20),dp(12),dp(20),dp(12));ScrollView sv=new ScrollView(this);sv.addView(detail);new AlertDialog.Builder(this).setTitle("Full device response").setView(sv).setPositiveButton("Done",null).show();
            }else if(which==2)runDirect("Command reference","help",false);
            else if(which==3)stopActivity();
            else {synchronized(textLock){pendingText.setLength(0);}results.clear();recentReadings.clear();output.setText("");renderResults();renderReadings();Toast.makeText(this,"Phone results cleared",Toast.LENGTH_SHORT).show();}
        }).setNegativeButton("Done",null).show();
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
        status.setText("Connecting…");
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
                    status.setText("ProS3 connected"); renderTools(); renderResults(); renderDevice();
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
            int previousRevision = results.revision;
            String parsed = results.accept(chunk);
            output.append(parsed);
            acceptReadings(parsed);
            if (previousRevision != results.revision) renderResults();
            if (output.length() > 65536) output.setText(output.getText().subSequence(output.length() - 49152, output.length()));
        }, 100);
    }
    private void disconnect(String reason, boolean stopScan) {
        generation++; connected = false; opening = false; pendingDevice = -1; activeDevice = -1;
        status.setText(reason);
        synchronized(textLock) { pendingText.setLength(0); }
        results.clear(); renderTools(); renderResults();
        discoveryMode=""; activeBar.setVisibility(View.GONE);
        recentReadings.clear(); renderReadings(); renderResults(); renderDevice();
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
