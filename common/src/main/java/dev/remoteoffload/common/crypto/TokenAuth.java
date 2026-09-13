package dev.remoteoffload.common.crypto;

import dev.remoteoffload.common.util.Hash;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Autenticación por token: challenge/HMAC y comparación en tiempo constante. */
public final class TokenAuth {

    private static final String HMAC_ALGO = "HmacSHA256";

    private TokenAuth() {}

    public static byte[] randomBytes(int len) {
        byte[] out = new byte[len];
        new SecureRandom().nextBytes(out);
        return out;
    }

    public static byte[] nonce() {
        return randomBytes(16);
    }

    public static byte[] challenge() {
        return randomBytes(32);
    }

    /**
     * HMAC-SHA256(token, challenge ‖ nonce). El token nunca viaja por red;
     * el servidor replica el mismo cálculo con su token.
     */
    public static byte[] computeMac(byte[] token, byte[] challenge, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(token, HMAC_ALGO));
            mac.update(challenge);
            mac.update(nonce);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparación en tiempo constante (evita timing attacks). */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest_isEqual(a, b);
    }

    private static boolean MessageDigest_isEqual(byte[] a, byte[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        // Merkle–Damgård-safe comparison
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    /** HMAC-SHA256 hex hot-string helper (para logs, sin el token). */
    public static String macHex(byte[] token, byte[] challenge, byte[] nonce) {
        return Hash.hex(computeMac(token, challenge, nonce));
    }
}