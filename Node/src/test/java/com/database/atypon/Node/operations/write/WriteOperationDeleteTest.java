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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WriteOperationDeleteTest {

    private final Path db = Paths.get("./data/deldb");
    private IndexManager indexManagerMock;
    private WriteOperation writeOp;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(db.resolve("schemas"));
        Files.createDirectories(db.resolve("users-records"));
        Files.writeString(db.resolve("schemas").resolve("users.json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", "users").put("nextId", 0))
                        .put("schema", new JSONObject().put("Age", "Integer")).toString());
        indexManagerMock = mock(IndexManager.class);
        writeOp = new WriteOperation(indexManagerMock);
        writeOp.createDocument("deldb", "users", new JSONObject().put("Age", 42)); // id 0, _version 1
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
    void deleteWithMatchingVersionRemovesFileAndFiresOnDelete() throws Exception {
        var resp = writeOp.deleteDocument("deldb", "users", "0", 1);
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(new File("./data/deldb/users-records/0.json")).doesNotExist();
        verify(indexManagerMock).onDelete(eq("deldb"), eq("users"), eq(0), any());
    }

    @Test
    void deleteWithStaleVersionConflictsAndKeepsFile() throws Exception {
        var resp = writeOp.deleteDocument("deldb", "users", "0", 9);
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.ERROR);
        assertThat(((Number) resp.getContent()).intValue()).isEqualTo(1);
        assertThat(new File("./data/deldb/users-records/0.json")).exists();
    }

    @Test
    void deleteMissingDocumentReturnsNotFound() throws Exception {
        var resp = writeOp.deleteDocument("deldb", "users", "999", 1);
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.ERROR);
        assertThat(resp.getMessage()).contains("not found");
    }

    @Test
    void applyDeleteRemovesFileWithoutVersionCheck() throws Exception {
        var resp = writeOp.applyDelete("deldb", "users", "0");
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(new File("./data/deldb/users-records/0.json")).doesNotExist();
    }

    @Test
    void applyDeleteOfAbsentFileIsSuccessNoOp() throws Exception {
        var resp = writeOp.applyDelete("deldb", "users", "999");
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
    }
}
