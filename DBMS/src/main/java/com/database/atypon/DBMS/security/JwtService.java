package com.database.atypon.DBMS.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses node-issued JWTs on the gateway. The gateway does not mint user tokens (the node does); it
 * validates the token it received to read the caller's role for building Spring authorities.
 */
@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** The role claim of a (validated) token. */
    public String role(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build()
                .parseClaimsJws(token).getBody().get("role", String.class);
    }

    public static List<GrantedAuthority> authorities(String role) {
        List<GrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_USER"));
        if ("ADMIN".equals(role) || "INTERNAL".equals(role)) list.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if ("INTERNAL".equals(role)) list.add(new SimpleGrantedAuthority("ROLE_INTERNAL"));
        return list;
    }
}
