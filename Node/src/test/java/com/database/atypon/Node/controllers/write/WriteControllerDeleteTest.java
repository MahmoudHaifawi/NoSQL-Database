package com.database.atypon.Node.controllers.write;

import com.database.atypon.Node.services.authentication.AuthenticationService;
import com.database.atypon.Node.services.write.WriteService;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class WriteControllerDeleteTest {

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
        Vector<Response> out = controller.deleteDocument("db", "s", "0", 1, "bad");
        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.ERROR);
        verify(writeService, never()).deleteDocument(any(), any(), any(), anyInt());
    }

    @Test
    void internalTokenAppliesDeleteWithoutBroadcast() {
        when(auth.isInternalToken("internal")).thenReturn(true);
        when(writeService.applyDelete(eq("db"), eq("s"), eq("0")))
                .thenReturn(new Response(ResponseType.SUCCESS, "ok"));

        Vector<Response> out = controller.deleteDocument("db", "s", "0", 0, "internal");

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).applyDelete(eq("db"), eq("s"), eq("0"));
        verify(writeService, never()).deleteDocument(any(), any(), any(), anyInt());
    }

    @Test
    void userTokenDeletesWithExpectedVersion() {
        when(auth.isInternalToken(anyString())).thenReturn(false);
        when(writeService.deleteDocument(eq("db"), eq("s"), eq("0"), eq(2)))
                .thenReturn(new Response(ResponseType.SUCCESS, "deleted"));

        Vector<Response> out = controller.deleteDocument("db", "s", "0", 2, "admin");

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).deleteDocument(eq("db"), eq("s"), eq("0"), eq(2));
    }
}
