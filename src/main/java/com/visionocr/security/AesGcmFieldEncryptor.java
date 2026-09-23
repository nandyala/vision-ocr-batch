package com.visionocr.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM. Key = base64 of 32 random bytes (generate with: openssl rand -base64 32),
 * supplied via FIELD_ENCRYPTION_KEY - in Azure keep it in Key Vault.
 * With no key configured values pass through unchanged (dev only) and a warning is logged.
 * Stored format: "enc:v1:" + base64(iv || ciphertext+tag).
 */
public class AesGcmFieldEncryptor implements FieldEncryptor {

    private static final Logger log = LoggerFactory.getLogger(AesGcmFieldEncryptor.class);
    private static final String PREFIX = "enc:v1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmFieldEncryptor(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("FIELD_ENCRYPTION_KEY not set: sensitive values will be stored UNENCRYPTED (dev only)");
            this.key = null;
        } else {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) {
                throw new IllegalArgumentException("FIELD_ENCRYPTION_KEY must be 32 bytes (base64)");
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    @Override
    public String encrypt(String plain) {
        if (plain == null || key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("Value is encrypted but FIELD_ENCRYPTION_KEY is not set");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
