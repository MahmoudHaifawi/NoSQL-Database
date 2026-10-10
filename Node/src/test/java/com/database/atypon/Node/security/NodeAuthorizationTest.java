package com.database.atypon.Node.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=test-secret-test-secret-test-secret-123456")
class NodeAuthorizationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("u", role, Duration.ofMinutes(5));
    }

    @Test
    void adminEndpointRejectsUserAcceptsAdmin() throws Exception {
        mvc.perform(get("/admin/index/list?database=d&schema=s").header("Authorization", bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/index/list?database=d&schema=s").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void networkEndpointRequiresInternal() throws Exception {
        mvc.perform(get("/network/get/nodes").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/network/get/nodes").header("Authorization", bearer("INTERNAL")))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenIsRejectedOnProtectedEndpoint() throws Exception {
        mvc.perform(get("/network/get/nodes")).andExpect(status().is4xxClientError());
    }

    @Test
    void healthEndpointIsPublicAndReportsUp() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("UP"));
    }
}
