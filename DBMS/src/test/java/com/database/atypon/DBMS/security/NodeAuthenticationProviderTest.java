package com.database.atypon.DBMS.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeAuthenticationProviderTest {

    private final NodeAuthenticationProvider provider =
            new NodeAuthenticationProvider(new JwtService("test-secret-test-secret-test-secret-123456"));

    @Test
    void supportsUsernamePasswordAuthentication() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
    }

    @Test
    void translatesClusterFailureToBadCredentials() {
        // No cluster is reachable in a unit test, so the node-login call fails; the provider must
        // surface that as a BadCredentials (which Spring renders as a login error), not leak the
        // raw exception.
        assertThatThrownBy(() ->
                provider.authenticate(new UsernamePasswordAuthenticationToken("someone", "secret")))
                .isInstanceOf(BadCredentialsException.class);
    }
}
