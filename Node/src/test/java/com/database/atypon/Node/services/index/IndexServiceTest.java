package com.database.atypon.Node.services.index;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexServiceTest {

    private void writeSchema(Path root, String db, String schema, JSONObject fields) throws IOException {
        Path dir = root.resolve(db).resolve("schemas");
        Files.createDirectories(dir);
        JSONObject full = new JSONObject().put("info", new JSONObject().put("schemaName", schema))
                .put("schema", fields);
        Files.writeString(dir.resolve(schema + ".json"), full.toString());
    }

    private void writeRecord(Path root, String db, String schema, int docId, JSONObject doc) throws IOException {
        Path dir = root.resolve(db).resolve(schema + "-records");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(docId + ".json"), doc.toString());
    }

    private void usersFixture(Path root) throws IOException {
        writeSchema(root, "shop", "users", new JSONObject().put("Name", "String").put("Age", "Integer"));
        writeRecord(root, "shop", "users", 0, new JSONObject().put("Name", "Alice").put("Age", 30));
        writeRecord(root, "shop", "users", 1, new JSONObject().put("Name", "Bob").put("Age", 25));
        writeRecord(root, "shop", "users", 2, new JSONObject().put("Name", "Carol").put("Age", 35));
    }

    @Test
    void createIndexBuildsFileAndListsIt(@TempDir Path root) throws Exception {
        usersFixture(root);
        IndexService svc = new IndexService(root);
        assertThat(svc.indexExists("shop", "users", "Age")).isFalse();
        svc.createIndex("shop", "users", "Age");
        assertThat(svc.indexExists("shop", "users", "Age")).isTrue();
        assertThat(Files.exists(root.resolve("shop").resolve("indexes").resolve("users.Age.idx"))).isTrue();
        assertThat(svc.listIndexes("shop", "users")).containsExactly("Age");
    }

    @Test
    void createIndexRejectsDuplicateAndUnknownField(@TempDir Path root) throws Exception {
        usersFixture(root);
        IndexService svc = new IndexService(root);
        svc.createIndex("shop", "users", "Age");
        assertThatThrownBy(() -> svc.createIndex("shop", "users", "Age"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> svc.createIndex("shop", "users", "Nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dropIndexRemovesIt(@TempDir Path root) throws Exception {
        usersFixture(root);
        IndexService svc = new IndexService(root);
        svc.createIndex("shop", "users", "Age");
        svc.dropIndex("shop", "users", "Age");
        assertThat(svc.indexExists("shop", "users", "Age")).isFalse();
        assertThat(svc.listIndexes("shop", "users")).isEmpty();
    }

    @Test
    void listIndexesEmptyWhenNone(@TempDir Path root) throws Exception {
        usersFixture(root);
        assertThat(new IndexService(root).listIndexes("shop", "users")).isEmpty();
    }
}
