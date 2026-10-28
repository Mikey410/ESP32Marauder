package com.mikey410.marauder;

import java.util.*;
import java.nio.charset.StandardCharsets;

/** Typed controls for existing firmware commands. No transport or Android dependencies. */
final class ToolCatalog {
    static final class Field {
        final String name, type; final String[] choices; final int max;
        Field(String name, String type, int max, String... choices) {
            this.name=name; this.type=type; this.max=max; this.choices=choices;
        }
        String validate(String value) {
            if (type.equals("choice")) {
                if (!Arrays.asList(choices).contains(value)) throw new IllegalArgumentException("Choose " + name);
                return value;
            }
            if (type.equals("number") || type.equals("ap")) {
                try { int n=Integer.parseInt(value); if(n<0 || n>max || (name.equals("Channel") && n==0)) throw new NumberFormatException(); return Integer.toString(n); }
                catch(NumberFormatException e) { throw new IllegalArgumentException(name + ": enter " + (name.equals("Channel")?1:0) + "–" + max); }
            }
            if(value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length>max) throw new IllegalArgumentException(name+": enter 1–"+max+" UTF-8 bytes");
            for(char c:value.toCharArray()) if(Character.isISOControl(c)||c=='\"'||c=='\'') throw new IllegalArgumentException(name+": quotes and control characters are unsupported by firmware");
            return "\""+value+"\"";
        }
    }
    static final class Tool {
        final String category,name,description,template; final boolean starts,confirm; final Field[] fields;
        Tool(String c,String n,String d,String t,boolean s,boolean confirm,Field...f){category=c;name=n;description=d;template=t;starts=s;this.confirm=confirm;fields=f;}
        String command(String...values) {
            if(values.length!=fields.length) throw new IllegalArgumentException("Complete every field");
            StringBuilder out=new StringBuilder(); int cursor=0;
            for(int i=0;i<values.length;i++){int p=template.indexOf("{"+i+"}",cursor);if(p<0)throw new IllegalArgumentException("Invalid template");out.append(template,cursor,p).append(fields[i].validate(values[i]));cursor=p+3;}
            out.append(template.substring(cursor));
            if(out.toString().getBytes(StandardCharsets.UTF_8).length>512)throw new IllegalArgumentException("Command too long");
            return out.toString();
        }
    }
    static Field ap(){return new Field("Network","ap",65535);}
    static Field index(){return new Field("List index","number",65535);}
    static Field choice(String name,String...values){return new Field(name,"choice",0,values);}
    static final List<Tool> ALL=new ArrayList<>();
    static void add(String c,String n,String d,String t,boolean s,Field...f){ALL.add(new Tool(c,n,d,t,s,false,f));}
    static {
        add("Wi-Fi","Scan networks & clients","Discover nearby access points and connected clients. Stop, then refresh Networks in Results.","scanall",true);
        add("Wi-Fi","Scan beacons","Discover Wi-Fi access points from their beacons.","sniffbeacon",true);
        add("Wi-Fi","Networks","Refresh discovered networks and their names.","list -a",false);
        add("Wi-Fi","Clients","Show discovered client devices.","list -c",false);
        add("Wi-Fi","Network details","Inspect a discovered network.","info -a {0}",false,ap());
        add("Wi-Fi","Join network","Connect the ProS3 for network diagnostics.","join -a {0} -p {1}",false,ap(),new Field("Password","password",63));
        add("Wi-Fi","Join saved network","Reconnect using credentials saved on the ProS3.","join -s",false);
        add("Wi-Fi","Set channel","Choose a 2.4 GHz channel; turn channel hopping off to hold it.","channel -s {0}",false,new Field("Channel","number",11));
        add("Wi-Fi","Current channel","Read the current radio channel.","channel",false);
        String[][] monitors={{"Probe requests","sniffprobe"},{"Deauthentication monitor","sniffdeauth"},{"Raw packets","sniffraw"},{"Packet count","packetcount"},{"SAE monitor","sniffsae"},{"EAPOL / PMKID monitor","sniffpmkid"},{"Pwnagotchi detection","sniffpwn"},{"Pineapple detection","sniffpinescan"},{"Multiple SSID detection","sniffmultissid"},{"MAC tracking","mactrack"}};
        for(String[] m:monitors)add("Monitor",m[0],"Start this firmware mode. Existing firmware settings apply; stop with the Stop button.",m[1],true);
        add("Monitor","Probe names","Show names observed in probe requests.","list -p",false);
        add("Monitor","CSI motion","Select a discovered access point for motion monitoring.","csimotion start {0}",true,ap());
        add("Monitor","Recalibrate motion","Recalibrate the active CSI monitor.","csimotion recalibrate",false);
        add("Monitor","Motion status","Read CSI monitor state.","csimotion status",false);
        add("Bluetooth","Scan Bluetooth","Discover nearby BLE devices.","sniffbt",true);
        add("Bluetooth","Filtered scan","Choose a firmware device filter.","sniffbt -t {0}",true,choice("Filter","airtag","ibeacon","flipper","flock","meta"));
        add("Bluetooth","Devices","Refresh discovered Bluetooth devices.","list -b",false);
        add("Bluetooth","Find My devices","Show the firmware's Find My list.","list -t",false);
        add("Bluetooth","Flippers","Show detected Flipper devices.","list -f",false);
        add("Bluetooth","Skimmer detection","Run the firmware's Bluetooth skimmer detector.","sniffskim",true);
        add("Network","Ping discovery","Join your network first, then discover responding IP addresses.","pingscan",true);
        add("Network","ARP discovery","Join your network first, then discover local hosts.","arpscan",true);
        add("Network","IP addresses","View discovered hosts and their indexes.","list -i",false);
        add("Network","Service scan","Scan discovered hosts for one service after joining your network.","portscan -s {0}",true,choice("Service","ssh","telnet","dns","http","smtp","https","rdp"));
        add("Network","Host port scan","Choose a host index from the IP addresses list.","portscan -a -t {0}",true,index());
        add("Targets","Select / deselect network","Toggle a discovered access point in the firmware selection.","select -a {0}",false,ap());
        add("Targets","Select / deselect client","Toggle a client index from the Clients list.","select -c {0}",false,index());
        add("Targets","Find Wi-Fi signal","Track signal strength for a discovered network.","foxhunt -w {0}",true,ap());
        add("Targets","Find BLE signal","Track a device index from the Bluetooth list.","foxhunt -b {0}",true,index());
        add("Targets","Find My signal","Track an index from the Find My list.","foxhunt -t {0}",true,index());
        add("Targets","SSID list","Show saved SSID names and indexes.","list -s",false);
        add("Targets","Add SSID","Add a name to the firmware's SSID list.","ssid -a -n {0}",false,new Field("SSID","text",32));
        add("Targets","Remove SSID","Remove an entry from the SSID list.","ssid -r {0}",false,index());
        add("Targets","Select / deselect SSID","Toggle an entry in the SSID list.","select -s {0}",false,index());
        ALL.add(new Tool("Targets","Clear list","Erase one firmware discovery list.","clearlist {0}",false,true,choice("List","-a","-c","-s")));
        add("Remote ID","Scan Remote ID","Discover nearby broadcast Remote ID messages.","remoteid scan",true);
        add("Remote ID","Remote ID list","Show discovered Remote ID records.","remoteid list",false);
        add("Remote ID","Track Remote ID","Choose a record index from the Remote ID list.","remoteid track {0}",true,index());
        ALL.add(new Tool("Remote ID","Clear Remote ID","Clear discovered Remote ID records.","remoteid clear",false,true));
        String[][] labs={{"Deauthentication","deauth"},{"Client deauthentication","deauth -c"},{"SSID list beacons","beacon -l"},{"Access point beacons","beacon -a"},{"Probe test","probe"},{"Channel switch test","csa"},{"Quiet test","quiet"}};
        for(String[] l:labs)ALL.add(new Tool("Lab",l[0],"Uses the firmware's selected targets or SSID list. For your authorized test network.","attack -t "+l[1],true,true));
        add("Device","Channel hopping","Enable hopping, or disable it to hold a channel.","settings -s ChanHop {0}",false,choice("Channel hopping","enable","disable"));
        add("Device","Settings","Read current firmware settings.","settings",false);
        add("Device","Device information","Read firmware information.","info",false);
        add("Device","Capabilities","Read the firmware's protocol capabilities.","protocolinfo",false);
        add("Device","Command reference","Show firmware help; uncommon options remain available in Advanced.","help",false);
        ALL.add(new Tool("Device","Restart board","Restart the ProS3; reconnect USB afterwards.","reboot",false,true));
        ALL.add(new Tool("Device","Reset settings","Restore firmware settings to defaults.","settings -r",false,true));
        add("Hardware","GPS / wardriving","Requires a GPS receiver and a matching firmware configuration.","",false);
        add("Hardware","SD / PCAP / backups","Requires an SD card interface and matching firmware configuration.","",false);
        add("Hardware","On-board display controls","The bare ProS3 has no Marauder display attached.","",false);
    }
}
