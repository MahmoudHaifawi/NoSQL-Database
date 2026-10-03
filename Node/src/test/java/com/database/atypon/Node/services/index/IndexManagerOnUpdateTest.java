package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexManagerOnUpdateTest {

    @TempDir
    Path dataRoot;

    private void writeSchemaAndRecord(String db, String schema, String id, JSONObject doc) throws Exception {
        Path schemas = dataRoot.resolve(db).resolve("schemas");
        Files.createDirectories(schemas);
        Files.writeString(schemas.resolve(schema + ".json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", schema).put("nextId", 1))
                        .put("schema", new JSONObject().put("price", "Integer")).toString());
        Path records = dataRoot.resolve(db).resolve(schema + "-records");
        Files.createDirectories(records);
        Files.writeString(records.resolve(id + ".json"), doc.toString());
    }

    @Test
    void onUpdateRebuildsIndexAgainstCurrentRecords() throws Exception {
        writeSchemaAndRecord("shop", "products", "0", new JSONObject().put("price", 30).put("_version", 1));
        IndexManager mgr = new IndexManager(dataRoot);
        mgr.createIndex("shop", "products", "price");

        // doc 0 found under old value 30
        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 30, null, true, 0, -1))
                .containsExactly(0);

        // the record changes to price=99 on disk, then the schema is rebuilt
        Files.writeString(dataRoot.resolve("shop").resolve("products-records").resolve("0.json"),
                new JSONObject().put("price", 99).put("_version", 2).toString());
        mgr.onUpdate("shop", "products");

        // old key gone, new key present
        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 30, null, true, 0, -1)).isEmpty();
        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 99, null, true, 0, -1))
                .containsExactly(0);
    }
}
