package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class IndexManagerTest {

    private void fixture(Path root) throws IOException {
        Path schemas = root.resolve("shop").resolve("schemas");
        Files.createDirectories(schemas);
        Files.writeString(schemas.resolve("users.json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", "users"))
                        .put("schema", new JSONObject().put("Name", "String").put("Age", "Integer")).toString());
        Path recs = root.resolve("shop").resolve("users-records");
        Files.createDirectories(recs);
        Files.writeString(recs.resolve("0.json"), new JSONObject().put("Name", "A").put("Age", 30).toString());
        Files.writeString(recs.resolve("1.json"), new JSONObject().put("Name", "B").put("Age", 25).toString());
    }

    @Test
    void createQueryDropUnderLocks(@TempDir Path root) throws Exception {
        fixture(root);
        IndexManager mgr = new IndexManager(root);
        mgr.createIndex("shop", "users", "Age");
        assertThat(mgr.listIndexes("shop", "users")).containsExactly("Age");
        assertThat(mgr.query("shop", "users", "Age", BPlusTree.Op.GTE, 25, null, true, 0, -1)).containsExactly(1, 0);
        mgr.dropIndex("shop", "users", "Age");
        assertThat(mgr.listIndexes("shop", "users")).isEmpty();
    }

    @Test
    void concurrentQueriesDuringInsertDoNotCorrupt(@TempDir Path root) throws Exception {
        fixture(root);
        IndexManager mgr = new IndexManager(root);
        mgr.createIndex("shop", "users", "Age");
        // hammer query while an insert runs; the global RW lock must keep both consistent
        CompletableFuture<?> writer = CompletableFuture.runAsync(() -> {
            try { mgr.onInsert("shop", "users", 2, new JSONObject().put("Name", "C").put("Age", 40)); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        CompletableFuture<List<Integer>> reader = CompletableFuture.supplyAsync(() -> {
            try { return mgr.query("shop", "users", "Age", BPlusTree.Op.GTE, 0, null, true, 0, -1); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        writer.get();
        reader.get(); // must not throw
        // after the insert, the new doc is present
        assertThat(mgr.query("shop", "users", "Age", BPlusTree.Op.EQ, 40, null, true, 0, -1)).containsExactly(2);
    }
}
