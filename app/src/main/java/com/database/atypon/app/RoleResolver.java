package com.database.atypon.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Resolves the single launch role from CLI args or the environment profile. */
public final class RoleResolver {

    public static final Set<String> ROLES = Set.of("node", "coordinator", "gateway");

    private RoleResolver() {
    }

    public static String resolve(String[] args, String envProfiles) {
        String source = firstNonBlank(argValue(args, "--role="),
                argValue(args, "--spring.profiles.active="), envProfiles);
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("no role selected");
        }
        List<String> known = new ArrayList<>();
        for (String part : source.split(",")) {
            String p = part.trim();
            if (ROLES.contains(p)) {
                known.add(p);
            }
        }
        if (known.isEmpty()) {
            throw new IllegalArgumentException("unknown role: " + source);
        }
        if (known.size() > 1) {
            throw new IllegalArgumentException("choose exactly one role, got: " + known);
        }
        return known.get(0);
    }

    private static String argValue(String[] args, String prefix) {
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
