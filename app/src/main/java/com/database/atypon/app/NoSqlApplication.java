package com.database.atypon.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The single entry point for the whole system. One jar, launched in one role:
 *   java -jar nosql-db.jar --role=<node|coordinator|gateway>
 * (or --spring.profiles.active=<role>). Only the chosen role's beans are created.
 */
@SpringBootApplication(scanBasePackages = "com.database.atypon.app")
public class NoSqlApplication {

    public static void main(String[] args) {
        String role;
        try {
            role = RoleResolver.resolve(args, System.getenv("SPRING_PROFILES_ACTIVE"));
        } catch (IllegalArgumentException e) {
            System.err.println("nosql-db: " + e.getMessage());
            System.err.println("Usage: java -jar nosql-db.jar --role=<node|coordinator|gateway>");
            System.err.println("   (or --spring.profiles.active=<role>)");
            System.exit(1);
            return;
        }
        System.setProperty("spring.profiles.active", role);
        SpringApplication.run(NoSqlApplication.class, args);
    }
}
