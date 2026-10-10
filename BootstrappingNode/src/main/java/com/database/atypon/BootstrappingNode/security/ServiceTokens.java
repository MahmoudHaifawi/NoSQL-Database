package com.database.atypon.BootstrappingNode.security;

import org.springframework.stereotype.Component;

/** Static bridge so non-bean code can mint internal service JWTs for calls to the data nodes. */
@Component
public class ServiceTokens {

    private static JwtService jwtService;

    public ServiceTokens(JwtService jwtService) {
        ServiceTokens.jwtService = jwtService;
    }

    public static String bearer() {
        return "Bearer " + jwtService.generateServiceToken();
    }
}
