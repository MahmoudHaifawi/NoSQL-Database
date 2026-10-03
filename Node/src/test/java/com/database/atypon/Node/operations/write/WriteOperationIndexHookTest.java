package com.database.atypon.Node.operations.write;

import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WriteOperationIndexHookTest {

    private final Path db = Paths.get("./data/hookdb");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(db)) {
            Files.walk(db).sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void createDocumentFiresOnInsertWithNewDocId() throws Exception {
        // arrange: a database + a schema on disk (createDocument validates against it)
        Files.createDirectories(db.resolve("schemas"));
        Files.createDirectories(db.resolve("users-records"));
        Files.writeString(db.resolve("schemas").resolve("users.json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", "users").put("nextId", 0))
                        .put("schema", new JSONObject().put("Age", "Integer")).toString());

        IndexManager mgr = mock(IndexManager.class);
        WriteOperation writeOp = new WriteOperation(mgr);
        JSONObject doc = new JSONObject().put("Age", 42);

        // act
        var resp = writeOp.createDocument("hookdb", "users", doc);

        // assert: write succeeded and the hook fired for docId 0 (the first nextId)
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(new File("./data/hookdb/users-records/0.json")).exists();
        verify(mgr).onInsert(eq("hookdb"), eq("users"), eq(0), any(JSONObject.class));
    }
}
