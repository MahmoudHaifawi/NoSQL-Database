package com.database.atypon.DBMS.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link AuthInterceptor} on the pages that forward to a data node and therefore need a
 * logged-in session. {@code /login} is intentionally not guarded.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns(
                        "/dashboard",
                        "/read", "/read/**",
                        "/update", "/update/**",
                        "/index", "/index/**",
                        "/createSchema",
                        "/createDocument",
                        "/admin/**");
    }
}
