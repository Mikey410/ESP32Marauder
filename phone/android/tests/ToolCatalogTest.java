package com.mikey410.marauder;
public class ToolCatalogTest {
    static void equal(String a,String b){if(!a.equals(b))throw new AssertionError(a+" != "+b);}
    static ToolCatalog.Tool tool(String template){return ToolCatalog.ALL.stream().filter(t->t.template.equals(template)).findFirst().orElseThrow();}
    static void reject(Runnable r){try{r.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Accepted invalid input");}
    public static void main(String[] args){
        equal(tool("join -a {0} -p {1}").command("2","my password"),"join -a 2 -p \"my password\"");
        equal(tool("portscan -s {0}").command("https"),"portscan -s https");
        equal(tool("csimotion start {0}").command("3"),"csimotion start 3");
        equal(tool("ssid -a -n {0}").command("{1}"),"ssid -a -n \"{1}\"");
        reject(()->tool("channel -s {0}").command("0"));reject(()->tool("channel -s {0}").command("12"));
        reject(()->tool("select -a {0}").command("-1"));reject(()->tool("select -a {0}").command("999999999999"));
        reject(()->tool("portscan -s {0}").command("http\nreboot"));
        reject(()->tool("join -a {0} -p {1}").command("2","a\" -s"));
        reject(()->tool("ssid -a -n {0}").command("a\nreboot"));
        ToolResults r=new ToolResults();equal(r.accept("[2][CH:6] My"),"");r.accept(" network -45\r\n[1][RSSI:-60] Test\n");
        if(r.networks.size()!=1||r.ble.size()!=1)throw new AssertionError("Missing results");
        equal(r.accept("> #join -a 2 -p \"sec"),"");equal(r.accept("ret\"\n"),"[Wi-Fi join command hidden]\n");
        equal(ToolResults.redact("join -s"),"join -s");r.clear();if(!r.networks.isEmpty())throw new AssertionError("Clear failed");
        for(ToolCatalog.Tool t:ToolCatalog.ALL){String[] v=new String[t.fields.length];for(int i=0;i<v.length;i++){ToolCatalog.Field f=t.fields[i];v[i]=f.type.equals("choice")?f.choices[0]:f.type.equals("number")||f.type.equals("ap")?"1":"test";}t.command(v);}
        ResultPresentation wifi=ResultPresentation.parse("[2][CH:6] My network -45 (selected)",false);
        equal(wifi.name,"My network");equal(wifi.subtitle,"Channel 6 · Strong signal");equal(wifi.id,"2");
        if(!wifi.selected)throw new AssertionError("Selection lost");
        equal(ResultPresentation.parse("[1][CH:11]  -80",false).name,"Hidden network");
        equal(ResultPresentation.parse("[1][RSSI:-60] Headphones",true).subtitle,"Good signal");
        equal(ResultPresentation.parse("[1][CH:1] Network -50 inside name -90",false).name,"Network -50 inside name");
        equal(ResultPresentation.reading("> #scanall"),"");equal(ResultPresentation.reading("Error: not connected"),"Error: not connected");
        equal(ResultPresentation.reading("RSSI: -50"),"RSSI: -50");
        if(ToolCatalog.ALL.size()!=65)throw new AssertionError("Tool catalog changed during visual redesign");
        equal(ResultPresentation.reading("> RSSI: -55"),"RSSI: -55");
        equal(ResultPresentation.reading("> Error: no data"),"Error: no data");
        ToolResults progress=new ToolResults();equal(progress.accept("RSSI: -60\rRSSI: -"),"RSSI: -60\n");equal(progress.accept("55\r\n"),"RSSI: -55\n");
        java.util.Map<String,String> nets=new java.util.LinkedHashMap<>();
        nets.put("0","[0][CH:1] First -50");nets.put("7","[7][CH:6] Chosen -60 (selected)");
        equal(ResultPresentation.preferredNetwork(nets,""),"7");equal(ResultPresentation.preferredNetwork(nets,"0"),"0");
        nets.put("0","[0][CH:1] First -50 (selected)");equal(ResultPresentation.preferredNetwork(nets,""),"");
        nets.clear();equal(ResultPresentation.preferredNetwork(nets,"7"),"");
        CommandFeedback feedback=new CommandFeedback();int id=feedback.track("info");feedback.written(id);
        equal(feedback.status(),"Sent over USB");feedback.accept("[App] Sent: info\n> #in");
        equal(feedback.status(),"Sent over USB");feedback.accept("fo\r\n");equal(feedback.status(),"Device received request");
        feedback.accept("> Error: not connected\n");equal(feedback.status(),"Device needs attention");
        feedback.accept("Other output\n");equal(feedback.detail,"Error: not connected");
        id=feedback.track("channel");feedback.accept("#info\nUnrelated output\n");feedback.timeout(id);equal(feedback.status(),"No device confirmation");
        feedback.accept("#channel\nChannel: 6\n");equal(feedback.status(),"Device replied");equal(feedback.detail,"Channel: 6");
        feedback.clear();equal(feedback.status(),"No request yet");
        System.out.println("Dashboard command and result tests passed ("+ToolCatalog.ALL.size()+" tools)");
    }
}
