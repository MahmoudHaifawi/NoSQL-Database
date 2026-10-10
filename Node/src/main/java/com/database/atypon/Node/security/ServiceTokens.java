package com.database.atypon.Node.security;

import org.springframework.stereotype.Component;

/**
 * Static bridge letting the plain {@code Node} model (not a Spring bean) mint internal service
 * JWTs for node-to-node calls. The single instance is created by Spring at start-up, capturing the
 * {@link JwtService} into a static field.
 */
@Component
public class ServiceTokens {

    private static JwtService jwtService;

    public ServiceTokens(JwtService jwtService) {
        ServiceTokens.jwtService = jwtService;
    }

    /** {@code "Bearer <internal service JWT>"} for authenticating node-to-node requests. */
    public static String bearer() {
        return "Bearer " + jwtService.generateServiceToken();
    }
}
