package com.database.atypon.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleResolverTest {

    @Test
    void resolvesRoleFromRoleAlias() {
        assertThat(RoleResolver.resolve(new String[]{"--role=node"}, null)).isEqualTo("node");
    }

    @Test
    void resolvesRoleFromProfileArg() {
        assertThat(RoleResolver.resolve(new String[]{"--spring.profiles.active=gateway"}, null)).isEqualTo("gateway");
    }

    @Test
    void resolvesRoleFromEnvProfile() {
        assertThat(RoleResolver.resolve(new String[]{}, "coordinator")).isEqualTo("coordinator");
    }

    @Test
    void rejectsNoRole() {
        assertThatThrownBy(() -> RoleResolver.resolve(new String[]{}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no role");
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() -> RoleResolver.resolve(new String[]{"--role=banana"}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown role");
    }

    @Test
    void rejectsMultipleRoles() {
        assertThatThrownBy(() -> RoleResolver.resolve(new String[]{"--spring.profiles.active=node,gateway"}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }

    @Test
    void rejectsConflictingRoleAliasAndProfileArg() {
        // --role and --spring.profiles.active naming different roles is ambiguous: must fail fast,
        // never silently pick one (Spring's arg precedence would otherwise win over the alias).
        assertThatThrownBy(() -> RoleResolver.resolve(
                new String[]{"--role=node", "--spring.profiles.active=gateway"}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
    }

    @Test
    void acceptsRoleAliasAndMatchingProfileArg() {
        // Same role from both sources is consistent, not a conflict.
        assertThat(RoleResolver.resolve(
                new String[]{"--role=gateway", "--spring.profiles.active=gateway"}, null)).isEqualTo("gateway");
    }
}
