package com.database.atypon.BootstrappingNode.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=test-secret-test-secret-test-secret-123456")
class BootstrapAuthorizationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("u", role, Duration.ofMinutes(5));
    }

    private static final String USER_JSON = "{\"username\":\"a\",\"password\":\"b\"}";

    @Test
    void testEndpointIsPublic() throws Exception {
        mvc.perform(get("/test")).andExpect(status().isOk());
    }

    @Test
    void getUserNodeIsPublic() throws Exception {
        mvc.perform(post("/getUserNode").contentType(MediaType.APPLICATION_JSON).content(USER_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void createNewUserRequiresAdmin() throws Exception {
        mvc.perform(post("/createNewUser").contentType(MediaType.APPLICATION_JSON).content(USER_JSON)
                        .header("Authorization", bearer("USER")))
                .andExpect(status().isForbidden());
        // ADMIN passes authorization (the handler then runs; the node call fails in-test, yielding 2xx with null)
        mvc.perform(post("/createNewUser").contentType(MediaType.APPLICATION_JSON).content(USER_JSON)
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void createNewUserRejectsMissingToken() throws Exception {
        mvc.perform(post("/createNewUser").contentType(MediaType.APPLICATION_JSON).content(USER_JSON))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void clusterRequiresAuthentication() throws Exception {
        mvc.perform(get("/cluster")).andExpect(status().is4xxClientError());
    }

    @Test
    void clusterAcceptsAnyAuthenticatedUser() throws Exception {
        // No data nodes run in-test, so each probe fails and nodes report down — but the request is
        // authorized and returns the topology array.
        mvc.perform(get("/cluster").header("Authorization", bearer("USER")))
                .andExpect(status().isOk());
    }
}
