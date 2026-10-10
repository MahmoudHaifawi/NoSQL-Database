package com.database.atypon.Node.services.authentication;

import com.database.atypon.Node.model.User;
import com.database.atypon.Node.security.JwtService;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationServiceBcryptTest {

    private final Path info = Paths.get("./data/info.json");
    private byte[] original;

    private static User user(String name, String pw) {
        User u = new User();
        u.setUsername(name);
        u.setPassword(pw);
        return u;
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(info.getParent());
        original = Files.exists(info) ? Files.readAllBytes(info) : null;
        String hash = new BCryptPasswordEncoder().encode("secret");
        Files.writeString(info, new JSONObject()
                .put("databases", new JSONArray())
                .put("users", new JSONObject().put("alice",
                        new JSONObject().put("username", "alice").put("role", "user").put("password", hash)))
                .toString());
    }

    @AfterEach
    void restore() throws Exception {
        if (original != null) Files.write(info, original);
    }

    @Test
    void acceptsCorrectPasswordAndRejectsWrong() {
        AuthenticationService svc = new AuthenticationService(new BCryptPasswordEncoder(),
                new JwtService("test-secret-test-secret-test-secret-123456"));
        assertThat(svc.authenticateUser(user("alice", "secret")).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(svc.authenticateUser(user("alice", "wrong")).getResponseType()).isEqualTo(ResponseType.ERROR);
    }
}
