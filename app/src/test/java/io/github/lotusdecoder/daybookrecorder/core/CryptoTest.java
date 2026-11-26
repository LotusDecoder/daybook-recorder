package io.github.lotusdecoder.daybookrecorder.core;

import org.junit.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.*;
import static org.junit.Assert.*;

public class CryptoTest {
    static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    @Test public void rfc5869CaseOneFirst32Bytes() throws Exception {
        byte[] ikm=new byte[22];Arrays.fill(ikm,(byte)0x0b);
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"),
                DeviceCrypto.hkdf(ikm,hex("000102030405060708090a0b0c"),hex("f0f1f2f3f4f5f6f7f8f9")));
    }
    @Test public void nistAes256CtrVector() throws Exception {
        assertArrayEquals(hex("6bc1bee22e409f96e93d7e117393172a"),DeviceCrypto.ctr(hex("601ec313775789a5b7a7f504bbf3d228"),
                hex("603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"),hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")));
    }
    private DeviceCrypto session(DeviceCrypto a,DeviceCrypto b) throws Exception {
        byte[] secret=b.shared(a.publicKey());assertArrayEquals(secret,a.shared(b.publicKey()));
        byte[] payload=new byte[97];System.arraycopy(b.publicKey(),0,payload,0,65);System.arraycopy(secret,0,payload,65,32);a.handshake(payload);return a;
    }
    @Test public void fileKeyAndPerSliceCounterInteroperate() throws Exception {
        DeviceCrypto a=new DeviceCrypto(),b=new DeviceCrypto();byte[] secret=b.shared(a.publicKey());session(a,b);
        byte[] sessionKey=DeviceCrypto.hkdf(secret,new byte[]{1,2,3},new byte[]{1,2,3});
        byte[] key=new byte[32],nonce=new byte[16],sessionNonce=new byte[16];Arrays.fill(key,(byte)7);Arrays.fill(nonce,(byte)3);
        byte[] clear=ByteBuffer.allocate(46).put("soundcored3200".getBytes(StandardCharsets.US_ASCII)).put(key).array();
        byte[] payload=ByteBuffer.allocate(87).order(ByteOrder.LITTLE_ENDIAN).putInt(123).putInt(160).put(nonce)
                .put(DeviceCrypto.ctr(clear,sessionKey,sessionNonce)).put(sessionNonce).put((byte)0).array();
        DeviceCrypto.FileKey fileKey=a.fileKey(payload,123);byte[] plain=new byte[160];Arrays.fill(plain,(byte)42);
        byte[] iv=nonce.clone();ByteBuffer.wrap(iv,12,4).putInt(20);
        assertArrayEquals(plain,fileKey.decrypt(2,DeviceCrypto.ctr(plain,key,iv)));
        try{a.fileKey(payload,124);fail("wrong file accepted");}catch(GeneralSecurityException expected){ }
        payload[24]^=1;
        try{a.fileKey(payload,123);fail("corrupt key accepted");}catch(GeneralSecurityException expected){ }
    }
    @Test public void failedHandshakeClearsSession() throws Exception {
        DeviceCrypto a=new DeviceCrypto();session(a,new DeviceCrypto());assertTrue(a.ready());
        try{a.handshake(new byte[97]);fail();}catch(GeneralSecurityException expected){ }
        assertFalse(a.ready());
    }
}
