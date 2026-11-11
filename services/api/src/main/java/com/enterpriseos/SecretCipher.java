package com.enterpriseos;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Versioned AES-256-GCM envelopes for 43-character webhook secrets; key stays
 * outside the database.
 */
@Service
public class SecretCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${APP_ENCRYPTION_KEY:}") String encoded) {
        key = encoded.matches("[0-9a-fA-F]{64}") ? HexFormat.of().parseHex(encoded) : null;
    }

    public String encrypt(String secret) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(sealed, 0, envelope, nonce.length, sealed.length);
            String value = "v1:" + Base64.getEncoder().encodeToString(envelope);
            if (value.length() > 100)
                throw new IllegalArgumentException();
            return value;
        } catch (Exception ex) {
            throw new ApiException(503, "Signing-secret encryption is unavailable.");
        }
    }

    public String decrypt(String value) {
        requireKey();
        try {
            if (value == null || !value.startsWith("v1:"))
                throw new IllegalArgumentException();
            byte[] sealed = Base64.getDecoder().decode(value.substring(3));
            if (sealed.length < 29)
                throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(sealed, 12)));
            return new String(cipher.doFinal(sealed, 12, sealed.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new ApiException(503,
                    "The signing secret cannot be decrypted. Restore the original encryption key or rotate the endpoint secret.");
        }
    }

    private void requireKey() {
        if (key == null)
            throw new ApiException(503,
                    "Configure the persistent APP_ENCRYPTION_KEY (64 hex characters) before creating or using webhook secrets.");
    }
}
