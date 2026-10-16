package com.database.atypon.app;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the single launch role from CLI args or the environment profile.
 *
 * <p>All sources are considered together — {@code --role=}, {@code --spring.profiles.active=}, and
 * the {@code SPRING_PROFILES_ACTIVE} env var — rather than first-match-wins, so a {@code --role}
 * that disagrees with a {@code --spring.profiles.active} (or any input naming more than one role)
 * fails fast here instead of letting Spring's command-line precedence silently pick a different role
 * or activating two role scans at once.
 */
public final class RoleResolver {

    public static final Set<String> ROLES = Set.of("node", "coordinator", "gateway");

    private RoleResolver() {
    }

    public static String resolve(String[] args, String envProfiles) {
        Set<String> roles = new LinkedHashSet<>();
        boolean anyToken = false;
        for (String source : List.of(nullToEmpty(argValue(args, "--role=")),
                nullToEmpty(argValue(args, "--spring.profiles.active=")), nullToEmpty(envProfiles))) {
            for (String part : source.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    anyToken = true;
                    if (ROLES.contains(p)) {
                        roles.add(p);
                    }
                }
            }
        }
        if (!anyToken) {
            throw new IllegalArgumentException("no role selected");
        }
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("unknown role");
        }
        if (roles.size() > 1) {
            throw new IllegalArgumentException("choose exactly one role, got: " + roles);
        }
        return roles.iterator().next();
    }

    private static String argValue(String[] args, String prefix) {
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
