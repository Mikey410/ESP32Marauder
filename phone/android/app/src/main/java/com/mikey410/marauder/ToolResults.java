package com.mikey410.marauder;
import java.util.*;
import java.util.regex.*;
final class ToolResults {
    final Map<String,String> networks=new LinkedHashMap<>(), ble=new LinkedHashMap<>();
    int revision;
    private boolean previousCR;
    private final StringBuilder tail=new StringBuilder();
    private static final Pattern AP=Pattern.compile("\\[(\\d+)\\]\\[CH:[^\\]]+\\].*");
    private static final Pattern BLE=Pattern.compile("\\[(\\d+)\\]\\[RSSI:[^\\]]+\\].*");
    static String redact(String line){return line.matches("(?i)^\\s*[># ]*join\\s.*") && !line.trim().endsWith("join -s") ? "[Wi-Fi join command hidden]" : line;}
    String accept(String chunk){
        StringBuilder out=new StringBuilder();
        for(char c:chunk.toCharArray()){
            if(c=='\n' && previousCR){previousCR=false;continue;}
            previousCR=c=='\r';
            if(c=='\n'||c=='\r'){
                String line=tail.toString();tail.setLength(0);
                Matcher a=AP.matcher(line),b=BLE.matcher(line);
                if(a.find()&&(networks.size()<256||networks.containsKey(a.group(1)))){if(!a.group().equals(networks.put(a.group(1),a.group())))revision++;}
                if(b.find()&&(ble.size()<256||ble.containsKey(b.group(1)))){if(!b.group().equals(ble.put(b.group(1),b.group())))revision++;}
                out.append(redact(line)).append('\n');
            }else if(tail.length()<4096)tail.append(c);
        }
        return out.toString();
    }
    void clear(){networks.clear();ble.clear();tail.setLength(0);previousCR=false;}
}
