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
}
