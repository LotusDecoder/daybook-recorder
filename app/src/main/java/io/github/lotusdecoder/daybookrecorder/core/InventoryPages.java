package io.github.lotusdecoder.daybookrecorder.core;

import java.util.*;

/** Validates an entire paginated response before it can replace a UI snapshot. */
public final class InventoryPages {
    private final Set<Long> seen=new HashSet<>();
    private int pages;
    private boolean ended;
    public void reset(){seen.clear();pages=0;ended=false;}
    /** Returns true for the last page; throws instead of publishing partial data. */
    public boolean accept(Protocol.FileList list) {
        if(ended || list.count>10 || list.count!=list.entries.size())throw new IllegalArgumentException("invalid inventory page");
        for(Protocol.Entry entry:list.entries)if(!seen.add(entry.id))throw new IllegalArgumentException("duplicate inventory entry");
        pages++;
        if(seen.size()>5000 || pages>501)throw new IllegalArgumentException("inventory too long");
        ended=list.count<10;
        return ended;
    }
}
