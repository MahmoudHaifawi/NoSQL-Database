package com.database.atypon.Node.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwt = new JwtService("test-secret-test-secret-test-secret-123456");

    @Test
    void generateAndParseRoundTrip() {
        String token = jwt.generateToken("mahmoud", "ADMIN", Duration.ofHours(1));
        Jws<Claims> parsed = jwt.parse(token);
        assertThat(parsed.getBody().getSubject()).isEqualTo("mahmoud");
        assertThat(parsed.getBody().get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void expiredTokenIsRejected() {
        String token = jwt.generateToken("x", "USER", Duration.ofSeconds(-1));
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwt.generateToken("x", "USER", Duration.ofHours(1));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "b" : "a") + "c";
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void authoritiesEncodeHierarchy() {
        assertThat(JwtService.authorities("USER")).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER");
        assertThat(JwtService.authorities("ADMIN")).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(JwtService.authorities("INTERNAL")).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN", "ROLE_INTERNAL");
    }
}
