package com.database.atypon.Node.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the authenticated caller's effective role from the security context, for controller logic
 * that branches on who is calling (e.g. an INTERNAL peer applies a write verbatim; an ADMIN origin
 * re-broadcasts). Returns null when there is no authentication.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static String currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return JwtService.roleFromAuthorities(auth.getAuthorities());
    }

    /** The raw JWT of the current caller (stored as credentials by the filter), for re-forwarding. */
    public static String currentToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getCredentials() == null) {
            return null;
        }
        return auth.getCredentials().toString();
    }
}
