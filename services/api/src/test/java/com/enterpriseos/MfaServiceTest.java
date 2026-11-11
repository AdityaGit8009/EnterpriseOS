package com.enterpriseos;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MfaServiceTest {
    @Test
    void rfc6238Sha1VectorsMatchSixDigitTruncation() {
        String key = MfaService.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertEquals("287082", MfaService.totp(key, 59 / 30));
        assertEquals("081804", MfaService.totp(key, 1111111109L / 30));
        assertEquals("050471", MfaService.totp(key, 1111111111L / 30));
        assertEquals("279037", MfaService.totp(key, 2000000000L / 30));
    }

    @Test
    void base32RoundTripsRandomAuthenticatorSecrets() {
        byte[] key = new byte[20];
        new java.security.SecureRandom().nextBytes(key);
        assertArrayEquals(key, MfaService.unbase32(MfaService.base32(key)));
    }
}
