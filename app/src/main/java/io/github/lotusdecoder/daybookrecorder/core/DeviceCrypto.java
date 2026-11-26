package io.github.lotusdecoder.daybookrecorder.core;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Local key agreement only; no account token. Never logs key material. */
public final class DeviceCrypto {
    private final KeyPair pair;
    private byte[] sessionKey;
    public DeviceCrypto() throws GeneralSecurityException {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"),new SecureRandom()); pair=generator.generateKeyPair();
    }
    public byte[] publicKey() {
        ECPublicKey key=(ECPublicKey)pair.getPublic(); byte[] b=new byte[65]; b[0]=4;
        fixed(key.getW().getAffineX(),b,1); fixed(key.getW().getAffineY(),b,33); return b;
    }
    private static void fixed(BigInteger n, byte[] b, int offset) {
        byte[] x=n.toByteArray(); int start=Math.max(0,x.length-32), length=Math.min(32,x.length);
        System.arraycopy(x,start,b,offset+32-length,length);
    }
    public byte[] shared(byte[] pub) throws GeneralSecurityException {
        if (pub.length!=65 || pub[0]!=4) throw new GeneralSecurityException("invalid peer key");
        ECParameterSpec parameters=((ECPublicKey)pair.getPublic()).getParams();
        ECPublicKeySpec spec=new ECPublicKeySpec(new ECPoint(new BigInteger(1,Arrays.copyOfRange(pub,1,33)),
                new BigInteger(1,Arrays.copyOfRange(pub,33,65))),parameters);
        KeyAgreement agreement=KeyAgreement.getInstance("ECDH"); agreement.init(pair.getPrivate());
        agreement.doPhase(KeyFactory.getInstance("EC").generatePublic(spec),true);
        return agreement.generateSecret();
    }
    public void handshake(byte[] payload) throws GeneralSecurityException {
        sessionKey=null;
        if (payload.length<97) throw new GeneralSecurityException("short handshake");
        byte[] secret=shared(Arrays.copyOfRange(payload,0,65));
        if (!MessageDigest.isEqual(secret,Arrays.copyOfRange(payload,65,97))) throw new GeneralSecurityException("ECDH check failed");
        sessionKey=hkdf(secret,new byte[]{1,2,3},new byte[]{1,2,3}); Arrays.fill(secret,(byte)0);
    }
    public boolean ready() { return sessionKey!=null; }
    public FileKey fileKey(byte[] payload, long expectedId) throws GeneralSecurityException {
        if (sessionKey==null || payload.length<87 || Protocol.u32(payload,0)!=expectedId || payload[86]!=0)
            throw new GeneralSecurityException("invalid file head");
        byte[] clear=ctr(Arrays.copyOfRange(payload,24,70),sessionKey,Arrays.copyOfRange(payload,70,86));
        byte[] magic="soundcored3200".getBytes(StandardCharsets.US_ASCII);
        if (clear.length!=46 || !MessageDigest.isEqual(magic,Arrays.copyOfRange(clear,0,14)))
            throw new GeneralSecurityException("file key check failed");
        FileKey key=new FileKey(Arrays.copyOfRange(clear,14,46),Arrays.copyOfRange(payload,8,24));
        Arrays.fill(clear,(byte)0); return key;
    }
    public static byte[] hkdf(byte[] secret, byte[] salt, byte[] info) throws GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(salt,"HmacSHA256")); byte[] prk=mac.doFinal(secret);
        mac.init(new SecretKeySpec(prk,"HmacSHA256")); mac.update(info); mac.update((byte)1);
        byte[] result=mac.doFinal(); Arrays.fill(prk,(byte)0); return result;
    }
    public static byte[] ctr(byte[] data, byte[] key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher=Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv)); return cipher.doFinal(data);
    }
    public static final class FileKey {
        private final byte[] key, nonce;
        public FileKey(byte[] key,byte[] nonce) { this.key=key; this.nonce=nonce; }
        public byte[] decrypt(long sequence,byte[] data) throws GeneralSecurityException {
            if (sequence<0 || sequence>0x19999999L || data.length!=160) throw new GeneralSecurityException("invalid slice");
            byte[] iv=Arrays.copyOf(nonce,16);
            ByteBuffer.wrap(iv,12,4).putInt((int)(sequence*10)); return ctr(data,key,iv);
        }
    }
}
