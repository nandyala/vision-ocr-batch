package com.visionocr.security;

/** Encrypts sensitive values before they are stored. */
public interface FieldEncryptor {
    String encrypt(String plain);

    String decrypt(String stored);
}
