package com.database.atypon.Node.controllers.write;

import com.database.atypon.Node.security.JwtService;
import com.database.atypon.Node.services.write.WriteService;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteControllerDeleteTest {

    private WriteService writeService;
    private WriteController controller;

    @BeforeEach
    void setUp() {
        writeService = mock(WriteService.class);
        controller = new WriteController(writeService);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "token", JwtService.authorities(role)));
    }

    @Test
    void internalRoleAppliesDeleteWithoutBroadcast() {
        authenticateAs("INTERNAL");
        when(writeService.applyDelete(eq("db"), eq("s"), eq("0")))
                .thenReturn(new Response(ResponseType.SUCCESS, "ok"));

        Vector<Response> out = controller.deleteDocument("db", "s", "0", 0);

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).applyDelete(eq("db"), eq("s"), eq("0"));
        verify(writeService, never()).deleteDocument(any(), any(), any(), anyInt());
    }

    @Test
    void userRoleDeletesWithExpectedVersion() {
        authenticateAs("ADMIN");
        when(writeService.deleteDocument(eq("db"), eq("s"), eq("0"), eq(2)))
                .thenReturn(new Response(ResponseType.SUCCESS, "deleted"));

        Vector<Response> out = controller.deleteDocument("db", "s", "0", 2);

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).deleteDocument(eq("db"), eq("s"), eq("0"), eq(2));
    }
}
