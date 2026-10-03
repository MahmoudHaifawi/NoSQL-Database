package com.database.atypon.Node.operations.write;

import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WriteOperationIndexHookTest {

    private final Path db = Paths.get("./data/hookdb");
    private IndexManager indexManagerMock;

    @BeforeEach
    void setUp() throws Exception {
        // a database + a schema on disk (createDocument validates against it); nextId starts at 0
        Files.createDirectories(db.resolve("schemas"));
        Files.createDirectories(db.resolve("users-records"));
        Files.writeString(db.resolve("schemas").resolve("users.json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", "users").put("nextId", 0))
                        .put("schema", new JSONObject().put("Age", "Integer")).toString());
        indexManagerMock = mock(IndexManager.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(db)) {
            try (var paths = Files.walk(db)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void createDocumentFiresOnInsertWithNewDocId() throws Exception {
        WriteOperation writeOp = new WriteOperation(indexManagerMock);
        JSONObject doc = new JSONObject().put("Age", 42);

        // act
        var resp = writeOp.createDocument("hookdb", "users", doc);

        // assert: write succeeded and the hook fired for docId 0 (the first nextId) with the written document
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(new File("./data/hookdb/users-records/0.json")).exists();
        verify(indexManagerMock).onInsert(eq("hookdb"), eq("users"), eq(0),
                argThat(d -> d.getInt("Age") == 42));
    }

    @Test
    void createStampsVersionOne() throws Exception {
        WriteOperation writeOp = new WriteOperation(indexManagerMock);
        writeOp.createDocument("hookdb", "users", new JSONObject().put("Age", 42));

        String written = Files.readString(Paths.get("./data/hookdb/users-records/0.json"));
        assertThat(new JSONObject(written).getInt("_version")).isEqualTo(1);
    }

    @Test
    void indexMaintenanceFailureDoesNotFailTheWrite() throws Exception {
        doThrow(new RuntimeException("boom"))
                .when(indexManagerMock).onInsert(any(), any(), anyInt(), any());
        WriteOperation writeOp = new WriteOperation(indexManagerMock);

        var resp = writeOp.createDocument("hookdb", "users", new JSONObject().put("Age", 7));

        // indexes are rebuildable: a maintenance error is logged, the write itself still succeeds
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(new File("./data/hookdb/users-records/0.json")).exists();
    }
}
