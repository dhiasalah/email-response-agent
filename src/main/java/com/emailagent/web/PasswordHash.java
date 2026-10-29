package com.emailagent.web;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The owner's password, stored only as a salted, slow hash: {@code pbkdf2-sha256:<iterations>:<salt>:<hash>}
 * (Base64). PBKDF2 ships with the JDK. Generate one with {@code java PasswordHash.java 'the password'} and put it in
 * the OWNER_PASSWORD_HASH environment variable; the repository never holds a password or a hash.
 */
public final class PasswordHash {

    static final int ITERATIONS = 600_000; // OWASP 2023 recommendation for PBKDF2-HMAC-SHA256
    private static final String PREFIX = "pbkdf2-sha256";

    private PasswordHash() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return PREFIX + ":" + ITERATIONS + ":" + b64(salt) + ":" + b64(derive(password, salt, ITERATIONS));
    }

    /** Constant-time comparison; a malformed stored hash never matches (fail closed). */
    public static boolean matches(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split(":");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000) {
                return false; // a weak hash is a configuration error, not a password
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: java PasswordHash.java '<password>'");
            System.exit(2);
        }
        System.out.println(hash(args[0]));
    }
}
