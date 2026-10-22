package com.orangecode.tianmu.utils;

import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private final String original = System.getProperty("tianmu.jwt.secret-base64");

    @AfterEach void restore() {
        if (original == null) System.clearProperty("tianmu.jwt.secret-base64");
        else System.setProperty("tianmu.jwt.secret-base64", original);
    }

    private void privateKey() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        System.setProperty("tianmu.jwt.secret-base64", Base64.getEncoder().encodeToString(bytes));
    }

    @Test void externallyConfiguredKeySignsAndVerifiesExistingSubjectFormat() {
        privateKey(); String token = JwtUtil.generate("881");
        assertEquals("881", JwtUtil.parse(token).getSubject());
    }

    @Test void changingKeyInvalidatesOldLoginTokens() {
        privateKey(); String token = JwtUtil.generate("881"); privateKey();
        assertNull(JwtUtil.parse(token));
    }

    @Test void keyMustHaveAtLeast256Bits() {
        System.setProperty("tianmu.jwt.secret-base64", Base64.getEncoder().encodeToString(new byte[16]));
        assertThrows(WeakKeyException.class, JwtUtil::getSignInKey);
    }
}
