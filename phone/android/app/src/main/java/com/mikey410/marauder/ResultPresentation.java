package com.mikey410.marauder;

import java.util.regex.*;

/** Turns firmware list records into presentation data without changing their IDs. */
final class ResultPresentation {
    private static final Pattern WIFI=Pattern.compile("\\[(\\d+)\\]\\[CH:(\\d+)\\] (.*) (-?\\d+)( \\(selected\\))?$");
    private static final Pattern BLE=Pattern.compile("\\[(\\d+)\\]\\[RSSI:(-?\\d+)\\] (.*)$");
    final String id,name,subtitle; final int rssi; final boolean selected;
    private ResultPresentation(String id,String name,String subtitle,int rssi,boolean selected){this.id=id;this.name=name;this.subtitle=subtitle;this.rssi=rssi;this.selected=selected;}
    static String strength(int rssi){return rssi>=-55?"Strong":rssi>=-70?"Good":rssi>=-85?"Fair":"Weak";}
    static ResultPresentation parse(String raw,boolean bluetooth){
        Matcher m=(bluetooth?BLE:WIFI).matcher(raw);
        if(!m.matches())return new ResultPresentation("", "Unknown device", "Details unavailable",-100,false);
        int rssi;try{rssi=Integer.parseInt(m.group(bluetooth?2:4));}catch(NumberFormatException ex){rssi=-100;}
        String name=m.group(3).trim(); if(name.isEmpty())name=bluetooth?"Unnamed device":"Hidden network";
        boolean selected=!bluetooth&&m.group(5)!=null;
        return new ResultPresentation(m.group(1),name,bluetooth?strength(rssi)+" signal":"Channel "+m.group(2)+" · "+strength(rssi)+" signal",rssi,selected);
    }
    static String reading(String line){
        String s=line.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]","").trim();
        if(s.isEmpty()||s.startsWith(">")||s.startsWith("#")||s.startsWith("[USB")||s.equals("[Wi-Fi join command hidden]")||s.matches("[=*_\\- ]+"))return "";
        // Leave measurements, warnings, and errors intact. Only remove protocol decoration.
        return s;
    }
}
