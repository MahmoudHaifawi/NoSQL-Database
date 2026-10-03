package com.database.atypon.DBMS.config;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Redirects unauthenticated requests to the login page.
 *
 * <p>The gateway keeps the logged-in user's token and their data-node URL in the HTTP session.
 * Without them, a forwarded call would build a request against a null node URL and fail deep in
 * the stack ("URI is not absolute"). Guarding protected pages here turns an expired or missing
 * session (e.g. after a server restart) into a clean redirect to {@code /login}.
 */
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        Object token = (session == null) ? null : session.getAttribute("token");
        Object nodeURL = (session == null) ? null : session.getAttribute("nodeURL");
        if (token == null || nodeURL == null) {
            response.sendRedirect(request.getContextPath() + "/login");
            return false;
        }
        return true;
    }
}
