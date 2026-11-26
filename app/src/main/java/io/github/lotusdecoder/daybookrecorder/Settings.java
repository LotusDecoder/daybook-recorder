package io.github.lotusdecoder.daybookrecorder;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.*;
import android.util.Base64;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Settings {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("recorder",Context.MODE_PRIVATE); }
    static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT<31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    }
    static boolean scanPermitted(Context c) {
        if(Build.VERSION.SDK_INT>=31) {
            return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
                    && c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
        }
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    /** Returns the stable logical device key across BLE RPA MAC rotations for the same bound bean. */
    static String activeDeviceId(Context c) {
        SharedPreferences p=prefs(c);
        String address=p.getString("address","");
        if(address.isEmpty())return "";
        String stable=p.getString("storage_device","");
        String boundAddr=p.getString("storage_bound_address","");
        if(!stable.isEmpty() && address.equalsIgnoreCase(boundAddr))return stable;
        return RecordingStore.deviceId(address);
    }
    static String validateEndpoint(String text) {
        String value=text.trim(); if(value.isEmpty())return value;
        URI uri=URI.create(value);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null)
            throw new IllegalArgumentException("请填写 HTTPS 上传地址，不含账号、查询参数或片段");
        return value;
    }
    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(!ks.containsAlias("daybook-upload")) {
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("daybook-upload",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); g.generateKey();
        }
        return (SecretKey)ks.getKey("daybook-upload",null);
    }
    /** A blank input preserves the credential only for the exact saved destination. */
    static synchronized void configureUpload(Context c, String address, String enteredToken, boolean clear) throws Exception {
        String endpoint = validateEndpoint(address);
        SharedPreferences settings = prefs(c);
        SharedPreferences.Editor edit = settings.edit().putString("endpoint", endpoint);
        if (clear || endpoint.isEmpty()) {
            edit.remove("token").remove("token_iv");
        } else if (!enteredToken.isEmpty()) {
            if (enteredToken.contains("\n") || enteredToken.contains("\r")) {
                throw new IllegalArgumentException("Token 不能包含换行");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(enteredToken.getBytes(StandardCharsets.UTF_8));
            edit.putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            edit.putString("token_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        } else if (!endpoint.equals(settings.getString("endpoint", ""))) {
            edit.remove("token").remove("token_iv");
        }
        edit.apply();
    }

    static final class UploadTarget {
        final String endpoint;
        final String token;
        UploadTarget(String endpoint, String token) {
            this.endpoint = endpoint;
            this.token = token;
        }
    }

    static synchronized UploadTarget uploadTarget(Context c) throws Exception {
        SharedPreferences settings = prefs(c);
        String endpoint = validateEndpoint(settings.getString("endpoint", ""));
        String value = settings.getString("token", "");
        if (endpoint.isEmpty() || value.isEmpty()) return new UploadTarget(endpoint, "");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = Base64.decode(settings.getString("token_iv", ""), Base64.NO_WRAP);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] clear = cipher.doFinal(Base64.decode(value, Base64.NO_WRAP));
        return new UploadTarget(endpoint, new String(clear, StandardCharsets.UTF_8));
    }
}
