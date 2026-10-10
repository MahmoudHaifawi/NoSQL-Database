package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.security.JwtService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.Principal;

/**
 * Exposes the logged-in user's identity and their pinned node to every Thymeleaf view, so the shared
 * navbar can render them without each controller adding the attributes. Reads the node-issued JWT and
 * node URL stashed in the session at login. Every accessor is null-safe and never throws: the login
 * page has no session yet, and a malformed token must not break page rendering.
 */
@ControllerAdvice
public class GlobalViewModel {

    private final JwtService jwtService;

    public GlobalViewModel(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @ModelAttribute("currentUser")
    public String currentUser(HttpServletRequest request) {
        // The authenticated principal is a DbmsUser (not a UserDetails), so getUserPrincipal().getName()
        // would be its toString(); the username is stashed in the session at login instead.
        String username = sessionAttribute(request, "username");
        if (username != null) {
            return username;
        }
        Principal principal = request.getUserPrincipal();
        return principal == null ? null : principal.getName();
    }

    @ModelAttribute("currentRole")
    public String currentRole(HttpServletRequest request) {
        String token = sessionAttribute(request, "token");
        if (token == null) {
            return null;
        }
        try {
            return jwtService.role(token);
        } catch (Exception e) {
            return null;
        }
    }

    /** The pinned node as a short {@code host:port} label (scheme stripped), or null before login. */
    @ModelAttribute("currentNode")
    public String currentNode(HttpServletRequest request) {
        String nodeURL = sessionAttribute(request, "nodeURL");
        return nodeURL == null ? null : nodeURL.replaceFirst("^https?://", "");
    }

    private static String sessionAttribute(HttpServletRequest request, String name) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(name);
    }
}
