package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexManagerIncrementalUpdateTest {

    @TempDir
    Path dataRoot;

    @Test
    void onUpdateMovesDocFromOldValueToNew() throws Exception {
        Path schemas = dataRoot.resolve("shop").resolve("schemas");
        Files.createDirectories(schemas);
        Files.writeString(schemas.resolve("products.json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", "products").put("nextId", 1))
                        .put("schema", new JSONObject().put("price", "Integer")).toString());
        Path records = dataRoot.resolve("shop").resolve("products-records");
        Files.createDirectories(records);
        Files.writeString(records.resolve("0.json"), new JSONObject().put("price", 30).put("_version", 1).toString());

        IndexManager mgr = new IndexManager(dataRoot);
        mgr.createIndex("shop", "products", "price");
        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 30, null, true, 0, -1)).containsExactly(0);

        mgr.onUpdate("shop", "products", 0,
                new JSONObject().put("price", 30), new JSONObject().put("price", 99));

        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 30, null, true, 0, -1)).isEmpty();
        assertThat(mgr.query("shop", "products", "price", BPlusTree.Op.EQ, 99, null, true, 0, -1)).containsExactly(0);
    }
}
