package io.github.lotusdecoder.daybookrecorder.core;

import java.nio.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class InventoryPagesTest {
    private Protocol.FileList page(long first,int count) {
        ByteBuffer bytes=ByteBuffer.allocate(2+count*12).order(ByteOrder.LITTLE_ENDIAN);bytes.putShort((short)count);
        for(int i=0;i<count;i++)bytes.putInt((int)(first+i)).putInt((int)(first+i+10)).putInt(1000);
        return new Protocol.FileList(bytes.array(),true);
    }
    @Test public void onlyTheTerminalPageCompletesTheSnapshot() {
        InventoryPages pages=new InventoryPages();assertFalse(pages.accept(page(100,10)));assertFalse(pages.accept(page(110,10)));assertTrue(pages.accept(page(120,3)));
        try{pages.accept(page(123,1));fail();}catch(IllegalArgumentException expected){ }
        pages.reset();assertTrue(pages.accept(page(100,0)));
    }
    @Test public void duplicateTruncatedAndUnknownPageShapesAreRejected() {
        InventoryPages pages=new InventoryPages();assertFalse(pages.accept(page(100,10)));
        try{pages.accept(page(109,2));fail();}catch(IllegalArgumentException expected){ }
        pages.reset();try{pages.accept(page(100,11));fail();}catch(IllegalArgumentException expected){ }
        pages.reset();try{pages.accept(page(0,1));fail();}catch(IllegalArgumentException expected){ }
        try{new Protocol.FileList(new byte[]{2,0},true);fail();}catch(IllegalArgumentException expected){ }
    }
    @Test public void fiveThousandEntriesNeedAnEmptyTerminatingPage() {
        InventoryPages pages=new InventoryPages();for(int i=0;i<500;i++)assertFalse(pages.accept(page(1+i*10,10)));
        assertTrue(pages.accept(page(5001,0)));
        pages.reset();for(int i=0;i<500;i++)assertFalse(pages.accept(page(1+i*10,10)));
        try{pages.accept(page(5001,1));fail();}catch(IllegalArgumentException expected){ }
    }
}
