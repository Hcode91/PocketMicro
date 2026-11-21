package com.hcode91.pocketmicro;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Extracts only identified Java/native crash blocks for one package. */
public final class CrashLogFilter {
    private static final Pattern LINE=Pattern.compile("^(?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+(?:\\S+\\s+)?(\\d+)\\s+\\d+\\s+[VDIWEFA]\\s+([^:]+):\\s?(.*)$");
    private static final int GROUP_LIMIT=32*1024, OUTPUT_LIMIT=96*1024;
    public static boolean validPackage(String value){return value!=null&&value.length()<=255&&value.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");}
    private static final class Group {StringBuilder lines=new StringBuilder();boolean matches,identified,clipped;}
    private static final class Line {String text;long read;boolean clipped;}
    private static Line next(BufferedReader input,long remaining)throws IOException{
        StringBuilder text=new StringBuilder();Line line=new Line();
        for(int c;(c=input.read())!=-1;){line.read++;if(line.read>remaining)break;if(c=='\n')break;if(text.length()<16384)text.append((char)c);else line.clipped=true;}
        if(line.read==0)return null;line.text=text.toString();return line;
    }
    private CrashLogFilter(){}
    public static String extract(Reader source,String pkg,long maximum)throws IOException {
        if(!validPackage(pkg))throw new IllegalArgumentException("Invalid package name");
        Pattern identity=Pattern.compile("(?:Process:\\s*|Cmdline:\\s*|>>>\\s*)"+Pattern.quote(pkg)+"(?=[:,\\s]|$)");
        Map<String,Group> groups=new LinkedHashMap<>();StringBuilder out=new StringBuilder();
        BufferedReader input=new BufferedReader(source);long read=0;boolean limited=false,clipped=false;
        for(Line item;(item=next(input,maximum-read))!=null;){
            read+=item.read;if(read>maximum){limited=true;break;}String line=item.text;clipped|=item.clipped;
            if(line.startsWith("------")){for(Group g:groups.values())flush(g,out);groups.clear();continue;}
            Matcher parsed=LINE.matcher(line);if(!parsed.matches())continue;
            String tag=parsed.group(2).trim();if(!tag.equals("AndroidRuntime")&&!tag.equals("DEBUG"))continue;
            String key=parsed.group(1)+"/"+tag;Group g=groups.get(key);
            if(g==null){if(groups.size()>=64){String first=groups.keySet().iterator().next();flush(groups.remove(first),out);}g=new Group();groups.put(key,g);}
            String message=parsed.group(3);
            if(message.startsWith("FATAL EXCEPTION:")||message.startsWith("*** ***")){flush(g,out);g=new Group();groups.put(key,g);}
            boolean marker=message.startsWith("Process:")||message.startsWith("Cmdline:")||(tag.equals("DEBUG")&&message.startsWith("pid:")&&message.contains(">>>"));
            if(marker&&g.identified){flush(g,out);g=new Group();groups.put(key,g);}
            if(marker)g.identified=true;
            if(marker&&identity.matcher(message).find())g.matches=true;
            if(g.lines.length()+line.length()<GROUP_LIMIT)g.lines.append(line).append('\n');
            else if(!g.matches){g.lines.setLength(0);g.lines.append(line).append('\n');}
            else g.clipped=true;
            if(out.length()>=OUTPUT_LIMIT){limited=true;break;}
        }
        for(Group g:groups.values())flush(g,out);
        if(out.length()==0)out.append("No identified Java/native crash block found for ").append(pkg).append(".\nThis does not prove the game did not fail. Log access may be unavailable, the failure may not reach Android's crash buffer, or the relevant entry may be outside the captured window.\n");
        if(limited)out.append("\nInput/output limit reached; this report may be incomplete.\n");
        if(clipped)out.append("\nOversized log lines were truncated.\n");
        return out.toString();
    }
    private static void flush(Group g,StringBuilder out){if(g.matches&&out.length()<OUTPUT_LIMIT){int n=Math.min(g.lines.length(),OUTPUT_LIMIT-out.length());out.append(g.lines,0,n).append('\n');if(g.clipped)out.append("[Crash block truncated]\n");}}
}
