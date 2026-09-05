package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexServiceQueryTest {

    private void writeSchema(Path root, String db, String schema, JSONObject fields) throws IOException {
        Path dir = root.resolve(db).resolve("schemas");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(schema + ".json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", schema)).put("schema", fields).toString());
    }

    private void writeRecord(Path root, String db, String schema, int docId, JSONObject doc) throws IOException {
        Path dir = root.resolve(db).resolve(schema + "-records");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(docId + ".json"), doc.toString());
    }

    private IndexService usersIndexedByAge(Path root) throws IOException {
        writeSchema(root, "shop", "users", new JSONObject().put("Name", "String").put("Age", "Integer"));
        writeRecord(root, "shop", "users", 0, new JSONObject().put("Name", "Alice").put("Age", 30));
        writeRecord(root, "shop", "users", 1, new JSONObject().put("Name", "Bob").put("Age", 25));
        writeRecord(root, "shop", "users", 2, new JSONObject().put("Name", "Carol").put("Age", 35));
        IndexService svc = new IndexService(root);
        svc.createIndex("shop", "users", "Age");
        return svc;
    }

    @Test
    void queryOperatorsReturnCorrectDocIds(@TempDir Path root) throws Exception {
        IndexService svc = usersIndexedByAge(root);
        // Age: doc0=30, doc1=25, doc2=35 -> sorted (25,d1)(30,d0)(35,d2)
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.GTE, 30, null, true, 0, -1)).containsExactly(0, 2);
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.BETWEEN, 25, 30, true, 0, -1)).containsExactly(1, 0);
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.LT, 30, null, true, 0, -1)).containsExactly(1);
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.EQ, 35, null, true, 0, -1)).containsExactly(2);
    }

    @Test
    void queryCoercesStringRequestValues(@TempDir Path root) throws Exception {
        IndexService svc = usersIndexedByAge(root);
        // value arrives as a String (as it would from an HTTP param) — must be coerced to Integer
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.GTE, "30", null, true, 0, -1)).containsExactly(0, 2);
    }

    @Test
    void onInsertMaintainsTheIndex(@TempDir Path root) throws Exception {
        IndexService svc = usersIndexedByAge(root);
        svc.onInsert("shop", "users", 3, new JSONObject().put("Name", "Dan").put("Age", 28));
        // now sorted: (25,d1)(28,d3)(30,d0)(35,d2)
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.GTE, 28, null, true, 0, -1)).containsExactly(3, 0, 2);
    }

    @Test
    void descendingAndPagination(@TempDir Path root) throws Exception {
        IndexService svc = usersIndexedByAge(root);
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.GTE, 0, null, false, 0, -1)).containsExactly(2, 0, 1);
        assertThat(svc.query("shop", "users", "Age", BPlusTree.Op.GTE, 0, null, true, 1, 1)).containsExactly(0);
    }

    @Test
    void queryRejectsNullValue(@TempDir Path root) throws Exception {
        IndexService svc = usersIndexedByAge(root);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> svc.query("shop", "users", "Age", com.database.atypon.Node.index.BPlusTree.Op.EQ, null, null, true, 0, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
