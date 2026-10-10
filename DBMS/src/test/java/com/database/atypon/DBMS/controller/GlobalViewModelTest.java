package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.security.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the global view advice that feeds the shared navbar: it reads identity and the
 * pinned node from the session/principal, and must be null-safe before login (no session yet).
 */
class GlobalViewModelTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-123456";
    private final GlobalViewModel advice = new GlobalViewModel(new JwtService(SECRET));

    private static String tokenWithRole(String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().setSubject("mahmoud").claim("role", role).signWith(key).compact();
    }

    @Test
    void exposesUserRoleAndShortNodeLabelFromSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("username", "mahmoud");
        request.getSession().setAttribute("token", tokenWithRole("ADMIN"));
        request.getSession().setAttribute("nodeURL", "http://localhost:8080");

        assertThat(advice.currentUser(request)).isEqualTo("mahmoud");
        assertThat(advice.currentRole(request)).isEqualTo("ADMIN");
        assertThat(advice.currentNode(request)).isEqualTo("localhost:8080");
    }

    @Test
    void fallsBackToPrincipalNameWhenSessionUsernameAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setUserPrincipal(() -> "fallback-user");
        assertThat(advice.currentUser(request)).isEqualTo("fallback-user");
    }

    @Test
    void returnsNullsBeforeLoginWithoutThrowing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(advice.currentUser(request)).isNull();
        assertThat(advice.currentRole(request)).isNull();
        assertThat(advice.currentNode(request)).isNull();
    }

    @Test
    void malformedTokenYieldsNullRoleInsteadOfError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("token", "not-a-jwt");
        assertThat(advice.currentRole(request)).isNull();
    }
}
