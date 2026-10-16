package com.xinghuiTec.utils;

import java.security.SecureRandom;

/** Generates a high-entropy temporary password without storing or logging its cleartext. */
public final class TemporaryPasswordGenerator {
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@$%".toCharArray();
    private static final int PASSWORD_LENGTH = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {
    }

    public static String generate() {
        char[] password = new char[PASSWORD_LENGTH];
        for (int i = 0; i < password.length; i++) {
            password[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(password);
    }
}
