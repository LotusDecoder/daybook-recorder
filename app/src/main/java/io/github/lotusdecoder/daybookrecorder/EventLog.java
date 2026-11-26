package io.github.lotusdecoder.daybookrecorder;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class EventLog {
    static synchronized void add(Context c,String message) {
        // Callers pass curated status messages only, never packet bytes, keys, tokens or server bodies.
        try {
            File f=new File(c.getFilesDir(),"events.log");
            if(f.length()>256*1024) {
                File previous=new File(c.getFilesDir(),"events.previous.log");
                if(previous.exists() && !previous.delete())return;
                if(!f.renameTo(previous))return;
            }
            try(FileOutputStream out=new FileOutputStream(f,true)) {
                out.write((Instant.now()+" "+message.replace('\n',' ')+"\n").getBytes(StandardCharsets.UTF_8));
            }
        }catch(IOException ignored) { }
    }
    static synchronized String tail(Context c) {
        ArrayDeque<String> lines=new ArrayDeque<>();
        for(String name:new String[]{"events.previous.log","events.log"}) {
            try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(new File(c.getFilesDir(),name)),StandardCharsets.UTF_8))) {
                String line;while((line=r.readLine())!=null) { lines.add(line); if(lines.size()>80)lines.removeFirst(); }
            }catch(IOException ignored) { }
        }
        return String.join("\n",lines);
    }
}
