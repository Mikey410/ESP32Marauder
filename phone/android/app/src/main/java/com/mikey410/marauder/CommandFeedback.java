package com.mikey410.marauder;

import java.util.Locale;

/** Evidence from the USB write and firmware echo, not a claim of radio effectiveness. */
final class CommandFeedback {
    private String expected="";
    private final StringBuilder tail=new StringBuilder();
    int sequence; boolean written,received,replied,attention,timedOut;
    String detail="";
    int track(String command){sequence++;expected=command;written=received=replied=attention=timedOut=false;detail="";tail.setLength(0);return sequence;}
    void written(int id){if(id==sequence)written=true;}
    void timeout(int id){if(id==sequence&&!received)timedOut=true;}
    void clear(){track("");}
    String status(){return expected.isEmpty()?"No request yet":attention?"Device needs attention":replied?"Device replied":received?"Device received request":timedOut?"No device confirmation":written?"Sent over USB":"Sending request";}
    void accept(String chunk){
        for(char c:chunk.toCharArray()){
            if(c=='\r'||c=='\n'){if(tail.length()>0){line(tail.toString());tail.setLength(0);}}
            else if(tail.length()<4096)tail.append(c);
        }
    }
    private void line(String raw){
        String s=raw.trim().replaceFirst("^(?:>\\s*)+","");
        if(expected.isEmpty())return;
        if(s.equals("#"+expected)){received=true;timedOut=false;return;}
        if(!received||s.startsWith("#")||s.startsWith("[App]"))return;
        s=ResultPresentation.reading(ToolResults.redact(s));if(s.isEmpty())return;
        replied=true;String lower=s.toLowerCase(Locale.ROOT);
        boolean error=lower.contains("error")||lower.contains("failed")||lower.contains("invalid")||lower.contains("don't have any targets")||lower.contains("not connected")||lower.contains("not supported")||lower.contains("did not provide")||lower.contains("out of range");
        if(error){attention=true;detail=s;}else if(!attention)detail=s;
    }
}
