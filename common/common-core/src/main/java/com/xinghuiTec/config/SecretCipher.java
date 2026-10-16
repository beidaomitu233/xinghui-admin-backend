package com.xinghuiTec.config;

import com.xinghuiTec.exception.ServiceException;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Supplier;

/** Server-only authenticated encryption; purpose binds ciphertext to its owning configuration. */
@Component
public class SecretCipher {
    private final Supplier<String> master;
    private final SecureRandom random = new SecureRandom();
    public SecretCipher() { this(() -> System.getenv("CONFIG_MASTER_KEY")); }
    public SecretCipher(Supplier<String> master) { this.master = master; }
    public boolean ready() {
        try { key(); return true; } catch (ServiceException missing) { return false; }
    }
    /**
     * Server-keyed, purpose-isolated input fingerprint. The master and derived HMAC key never leave
     * this method. Resolution is lazy: deployments not using encrypted configuration/AI still start.
     * This does not change the AES-GCM key, nonce format, AAD or existing ciphertext compatibility.
     */
    public String fingerprint(String purpose, byte[] canonicalMessage) {
        if (purpose == null || !purpose.matches("[A-Za-z0-9:._/-]{1,128}")
                || canonicalMessage == null || canonicalMessage.length > 131_072) {
            throw new ServiceException("指纹参数无效");
        }
        byte[] masterBytes = null, derived = null;
        try {
            masterBytes = key().getEncoded();
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(masterBytes, "HmacSHA256"));
            derived = derive.doFinal(("xinghui:config:fingerprint:v1:" + purpose).getBytes(StandardCharsets.UTF_8));
            Mac digest = Mac.getInstance("HmacSHA256");
            digest.init(new SecretKeySpec(derived, "HmacSHA256"));
            return HexFormat.of().formatHex(digest.doFinal(canonicalMessage));
        } catch (ServiceException invalid) { throw invalid; }
        catch (Exception unavailable) { throw new ServiceException("配置指纹计算失败"); }
        finally {
            if (masterBytes != null) Arrays.fill(masterBytes, (byte) 0);
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }
    private SecretKeySpec key() {
        try {
            String encoded = master.get();
            byte[] bytes = encoded == null ? new byte[0] : Base64.getDecoder().decode(encoded);
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (RuntimeException invalid) { throw new ServiceException("配置主密钥未正确设置，请联系部署管理员"); }
    }
    public String encrypt(String purpose, String clear) {
        if (clear == null || clear.isBlank() || clear.length() > 32768 || clear.chars().anyMatch(c -> c < 32 || c == 127))
            throw new ServiceException("密钥格式无效");
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8)));
        } catch (ServiceException invalid) { throw invalid; }
        catch (Exception failure) { throw new ServiceException("密钥加密失败"); }
    }
    public String decrypt(String purpose, String encrypted) {
        try {
            String[] parts = encrypted.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (ServiceException invalid) { throw invalid; }
        catch (Exception invalid) { throw new ServiceException("配置密钥解密失败，请重新设置密钥"); }
    }
}
