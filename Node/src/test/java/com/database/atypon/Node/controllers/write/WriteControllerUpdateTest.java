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

import java.util.HashMap;
import java.util.Vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteControllerUpdateTest {

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
    void internalRoleAppliesVerbatimWithoutBroadcast() {
        authenticateAs("INTERNAL");
        when(writeService.applyUpdate(eq("db"), eq("s"), eq("0"), any()))
                .thenReturn(new Response(ResponseType.SUCCESS, "ok"));
        HashMap<String, Object> doc = new HashMap<>();
        doc.put("_version", 5);

        Vector<Response> out = controller.updateDocument("db", "s", "0", doc);

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).applyUpdate(eq("db"), eq("s"), eq("0"), any());
        verify(writeService, never()).updateDocument(any(), any(), any(), any(), anyInt());
    }

    @Test
    void userRoleRunsOriginUpdateWithExpectedVersionFromBody() {
        authenticateAs("ADMIN");
        when(writeService.updateDocument(eq("db"), eq("s"), eq("0"), any(), eq(3)))
                .thenReturn(new Response(ResponseType.SUCCESS, "updated", 4));
        HashMap<String, Object> doc = new HashMap<>();
        doc.put("Age", 10);
        doc.put("_version", 3);

        Vector<Response> out = controller.updateDocument("db", "s", "0", doc);

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).updateDocument(eq("db"), eq("s"), eq("0"), any(), eq(3));
    }
}
