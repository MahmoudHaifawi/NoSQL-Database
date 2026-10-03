package com.database.atypon.Node.controllers.write;

import com.database.atypon.Node.services.authentication.AuthenticationService;
import com.database.atypon.Node.services.write.WriteService;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteControllerUpdateTest {

    private WriteService writeService;
    private AuthenticationService auth;
    private WriteController controller;

    @BeforeEach
    void setUp() {
        writeService = mock(WriteService.class);
        auth = mock(AuthenticationService.class);
        controller = new WriteController(writeService, auth);
        when(auth.isUserToken(anyString())).thenReturn(true);
    }

    @Test
    void rejectsNonUser() {
        when(auth.isUserToken("bad")).thenReturn(false);
        Vector<Response> out = controller.updateDocument("db", "s", "0", new HashMap<>(), "bad");
        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.ERROR);
        verify(writeService, never()).updateDocument(any(), any(), any(), any(), anyInt());
    }

    @Test
    void internalTokenAppliesVerbatimWithoutBroadcast() {
        when(auth.isInternalToken("internal")).thenReturn(true);
        when(writeService.applyUpdate(eq("db"), eq("s"), eq("0"), any()))
                .thenReturn(new Response(ResponseType.SUCCESS, "ok"));
        HashMap<String, Object> doc = new HashMap<>();
        doc.put("_version", 5);

        Vector<Response> out = controller.updateDocument("db", "s", "0", doc, "internal");

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).applyUpdate(eq("db"), eq("s"), eq("0"), any());
        verify(writeService, never()).updateDocument(any(), any(), any(), any(), anyInt());
    }

    @Test
    void userTokenRunsOriginUpdateWithExpectedVersionFromBody() {
        when(auth.isInternalToken(anyString())).thenReturn(false);
        when(writeService.updateDocument(eq("db"), eq("s"), eq("0"), any(), eq(3)))
                .thenReturn(new Response(ResponseType.SUCCESS, "updated", 4));
        HashMap<String, Object> doc = new HashMap<>();
        doc.put("Age", 10);
        doc.put("_version", 3);

        Vector<Response> out = controller.updateDocument("db", "s", "0", doc, "admin");

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).updateDocument(eq("db"), eq("s"), eq("0"), any(), eq(3));
    }
}
