package com.database.atypon.DBMS.security;

import com.database.atypon.DBMS.database_system.connection.ConnectionRequest;
import com.database.atypon.DBMS.model.User;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Authenticates a gateway form login by delegating to the cluster: it resolves the user's data node
 * via the bootstrapping node, then calls that node's {@code /login}, which (on correct BCrypt
 * credentials) returns a signed JWT. The JWT + node URL are carried on the authenticated principal
 * so the session can forward them to the node on later requests.
 */
@Component
public class NodeAuthenticationProvider implements AuthenticationProvider {

    private final JwtService jwtService;

    public NodeAuthenticationProvider(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();
        String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        try {
            String nodeURL = ConnectionRequest.retrieveNodeURL(user);
            String jwt = ConnectionRequest.login(user, nodeURL); // node /login returns the JWT
            String role = jwtService.role(jwt);
            DbmsUser principal = new DbmsUser(username, role, jwt, nodeURL);
            return new UsernamePasswordAuthenticationToken(principal, null, JwtService.authorities(role));
        } catch (Exception e) {
            throw new BadCredentialsException("Login failed: " + e.getMessage());
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
