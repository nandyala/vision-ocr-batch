package com.visionocr.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmFieldEncryptorTest {

    @Test
    void roundTrip() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        AesGcmFieldEncryptor enc = new AesGcmFieldEncryptor(Base64.getEncoder().encodeToString(key));

        String c1 = enc.encrypt("021000021");
        String c2 = enc.encrypt("021000021");
        assertTrue(c1.startsWith("enc:v1:"));
        assertNotEquals(c1, c2, "random IV per value");
        assertEquals("021000021", enc.decrypt(c1));
    }

    @Test
    void noKeyMeansPassThrough() {
        AesGcmFieldEncryptor enc = new AesGcmFieldEncryptor("");
        assertEquals("abc", enc.encrypt("abc"));
        assertEquals("abc", enc.decrypt("abc"));
    }

    @Test
    void masking() {
        assertEquals("*****0021", Masking.mask("021000021"));
        assertEquals("**34", Masking.mask("1234"));
    }
}
