package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexManagerOnDeleteTest {

    @TempDir
    Path dataRoot;

    private void writeSchema(String db, String schema) throws Exception {
        Path schemas = dataRoot.resolve(db).resolve("schemas");
        Files.createDirectories(schemas);
        Files.writeString(schemas.resolve(schema + ".json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", schema).put("nextId", 2))
                        .put("schema", new JSONObject().put("price", "Integer")).toString());
    }

    private void writeRecord(String db, String schema, int id, JSONObject doc) throws Exception {
        Path records = dataRoot.resolve(db).resolve(schema + "-records");
        Files.createDirectories(records);
        Files.writeString(records.resolve(id + ".json"), doc.toString());
    }

    @Test
    void onDeleteRemovesOnlyThatDocsCompositeKey() throws Exception {
        writeSchema("shop", "products");
        writeRecord("shop", "products", 0, new JSONObject().put("price", 50).put("_version", 1));
        writeRecord("shop", "products", 1, new JSONObject().put("price", 50).put("_version", 1));
        IndexManager mgr = new IndexManager(dataRoot);
        mgr.createIndex("shop", "products", "price");

        // both docs share value 50
        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 50, null, true, 0, -1))
                .containsExactly(0, 1);

        mgr.onDelete("shop", "products", 0, new JSONObject().put("price", 50));

        // only doc 1 (same value) remains; the composite key for doc 0 is gone
        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 50, null, true, 0, -1))
                .containsExactly(1);
    }
}
