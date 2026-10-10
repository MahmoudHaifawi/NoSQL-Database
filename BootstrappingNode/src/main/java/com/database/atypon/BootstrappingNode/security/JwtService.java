package com.database.atypon.BootstrappingNode.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/** HS256 JWT minting/validation. Shares the cluster secret so tokens validate across modules. */
@Service
public class JwtService {

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String role, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(username)
                .claim("role", role)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(ttl)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String generateServiceToken() {
        return generateToken("service", "INTERNAL", Duration.ofMinutes(5));
    }

    public Jws<Claims> parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
    }

    public static List<GrantedAuthority> authorities(String role) {
        List<GrantedAuthority> list = new ArrayList<>();
        list.add(new SimpleGrantedAuthority("ROLE_USER"));
        if ("ADMIN".equals(role) || "INTERNAL".equals(role)) list.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if ("INTERNAL".equals(role)) list.add(new SimpleGrantedAuthority("ROLE_INTERNAL"));
        return list;
    }

    public static String roleFromAuthorities(Collection<? extends GrantedAuthority> auths) {
        boolean internal = auths.stream().anyMatch(a -> a.getAuthority().equals("ROLE_INTERNAL"));
        boolean admin = auths.stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (internal) return "INTERNAL";
        if (admin) return "ADMIN";
        return "USER";
    }
}
