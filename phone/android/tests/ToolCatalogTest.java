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
        System.out.println("Dashboard command and result tests passed ("+ToolCatalog.ALL.size()+" tools)");
    }
}
