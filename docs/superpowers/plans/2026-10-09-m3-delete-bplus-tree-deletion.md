# M3 — Document Delete + B+-tree Key Deletion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add B+-tree key deletion and a version-checked document delete operation (origin-decides replication, incremental index key-removal), surfaced as a Delete button on the fetch-then-edit page. Task 7 (separable) converts M2's update index maintenance from full rebuild to incremental.

**Architecture:** `BPlusTree.delete` removes a key from its leaf with no rebalancing (leaves may be under-full/empty but stay sorted + chained; separators untouched). `IndexService.onDelete` removes each index's composite key. `WriteOperation.deleteDocument` (optimistic check) / `applyDelete` (verbatim) mirror M2's update. The gateway adds a Delete action to `/update`.

**Tech Stack:** Java 17, Spring Boot 2.7.x, org.json, JUnit 5 + AssertJ + Mockito, Thymeleaf + Bootstrap 4.

**Spec:** `docs/superpowers/specs/2026-10-09-m3-delete-bplus-tree-deletion-design.md`

## Global Constraints

- Build/test with `JAVA_HOME=C:\Program Files\Java\jdk-17`. Maven 3.9 on PATH.
- Work directly on `master`; commit (and push) there, no per-feature branches.
- Delete is version-checked (optimistic): a stale delete returns a conflict carrying the current version; nothing is deleted.
- `delete` never rebalances. `validate()` already has no minimum-occupancy invariant, so it stays correct for under-full/empty leaves — do not add one.
- Index-maintenance errors are logged and must NOT fail the write (derived data) — match `onInsert`.
- Replication: origin (user token) decides once; peers (`internal` token) apply verbatim. Single node = no peers = no-op broadcast.
- Composite keys are `KeyCodec.encode(keyType, coerce(keyType, fieldValue), docId)`; docId is the integer record id.

---

### Task 1: `BPlusTree.delete`

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java`
- Test: `Node/src/test/java/com/database/atypon/Node/index/BPlusTreeDeleteTest.java` (create)

**Interfaces:**
- Consumes: existing `findLeaf`, `readLeafKeys`, `writeLeafKeys`, `KeyCodec.compare`, `pager.markDirty`.
- Produces: `BPlusTree.delete(byte[] key)` → `boolean` (true iff an entry was removed).

- [ ] **Step 1: Write the failing tests** `BPlusTreeDeleteTest.java`:

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BPlusTreeDeleteTest {

    private static byte[] k(int v) {
        return KeyCodec.encode(KeyType.INTEGER, v, 0);
    }

    @Test
    void deleteRemovesKeyLeavingSiblings(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("a.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            tree.insert(k(10));
            tree.insert(k(5));
            tree.insert(k(20));
            assertThat(tree.delete(k(10))).isTrue();
            assertThat(tree.contains(k(10))).isFalse();
            assertThat(tree.contains(k(5))).isTrue();
            assertThat(tree.contains(k(20))).isTrue();
        }
    }

    @Test
    void deleteAbsentReturnsFalse(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("b.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            tree.insert(k(5));
            assertThat(tree.delete(k(99))).isFalse();
            assertThat(tree.contains(k(5))).isTrue();
        }
    }

    @Test
    void deleteMinKeyAfterSplitKeepsOthersAndValidates(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("c.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 5; v++) tree.insert(k(v)); // forces a split
            assertThat(tree.delete(k(1))).isTrue();          // remove a leaf minimum
            assertThat(tree.contains(k(1))).isFalse();
            for (int v = 2; v <= 5; v++) assertThat(tree.contains(k(v))).as("v=" + v).isTrue();
            tree.validate();
        }
    }

    @Test
    void deleteAllThenValidateAndReinsert(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("d.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 10; v++) tree.insert(k(v));
            for (int v = 1; v <= 10; v++) assertThat(tree.delete(k(v))).as("del " + v).isTrue();
            for (int v = 1; v <= 10; v++) assertThat(tree.contains(k(v))).isFalse();
            tree.validate();                 // empty/under-full leaves are still valid
            tree.insert(k(42));              // tree remains usable
            assertThat(tree.contains(k(42))).isTrue();
        }
    }

    @Test
    void oracleRandomDeletes(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("e.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 40; v++) tree.insert(k(v));
            List<Integer> toDelete = new ArrayList<>();
            for (int v = 1; v <= 40; v++) if (v % 3 == 0) toDelete.add(v);
            Collections.shuffle(toDelete, new java.util.Random(7));
            for (int v : toDelete) assertThat(tree.delete(k(v))).isTrue();
            for (int v = 1; v <= 40; v++) {
                assertThat(tree.contains(k(v))).as("v=" + v).isEqualTo(v % 3 != 0);
            }
            tree.validate();
        }
    }
}
```

- [ ] **Step 2: Run them, verify they fail**

Run: `cd Node && mvn -q -Dtest=BPlusTreeDeleteTest test`
Expected: FAIL — `delete` is undefined (compile error).

- [ ] **Step 3: Implement `delete`.** Add to `BPlusTree` after the `contains`/`findLeaf` search section (e.g. right before the `// ---- insert ----` banner):

```java
    // ---- delete (no rebalance) ----

    /**
     * Removes {@code key} from its leaf if present and returns whether anything was removed.
     * Performs no merging/borrowing/root-shrink: a leaf may become under-full or empty but stays
     * sorted and in the sibling chain, and internal separators are left unchanged (a separator is
     * a routing guide that remains valid when a leaf's minimum rises). Search and range scans stay
     * correct; only space is wasted after heavy deletion.
     */
    public boolean delete(byte[] key) throws IOException {
        int leafId = findLeaf(key);
        Page leaf = pager.get(leafId);
        List<byte[]> keys = readLeafKeys(leaf);
        int idx = -1;
        for (int i = 0; i < keys.size(); i++) {
            if (KeyCodec.compare(keyType, key, keys.get(i)) == 0) { idx = i; break; }
        }
        if (idx < 0) return false;
        keys.remove(idx);
        writeLeafKeys(leaf, keys);   // rewrites entries 0..size-1 and sets numKeys (trailing slot ignored)
        pager.markDirty(leafId);
        return true;
    }
```

- [ ] **Step 4: Run the tests, verify they pass**

Run: `cd Node && mvn -q -Dtest=BPlusTreeDeleteTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java Node/src/test/java/com/database/atypon/Node/index/BPlusTreeDeleteTest.java
git commit -m "feat(index): B+-tree key deletion (no rebalance)"
```

---

### Task 2: `IndexService.onDelete` + `IndexManager.onDelete`

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java`
- Test: `Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerOnDeleteTest.java` (create)

**Interfaces:**
- Consumes: `BPlusTree.delete` (Task 1); existing `listIndexes`, `indexFile`, `indexKeyType`, `coerce`, `KeyCodec.encode`.
- Produces:
  - `IndexService.onDelete(String db, String schema, int docId, JSONObject doc)` throws IOException.
  - `IndexManager.onDelete(String db, String schema, int docId, JSONObject doc)` throws IOException (write-locked).

- [ ] **Step 1: Write the failing test** `IndexManagerOnDeleteTest.java`:

```java
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
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd Node && mvn -q -Dtest=IndexManagerOnDeleteTest test`
Expected: FAIL — `onDelete` undefined (compile error).

- [ ] **Step 3: Implement `IndexService.onDelete`.** Add after `onInsert`:

```java
    /** Remove the deleted document's composite key from every index defined on this schema. */
    public void onDelete(String db, String schema, int docId, JSONObject doc) throws IOException {
        for (String field : listIndexes(db, schema)) {
            if (!doc.has(field)) {
                continue;
            }
            try (Pager pager = new Pager(indexFile(db, schema, field).toFile())) {
                BPlusTree tree = BPlusTree.open(pager);
                KeyType keyType = indexKeyType(pager);
                byte[] key = KeyCodec.encode(keyType, coerce(keyType, doc.get(field)), docId);
                if (tree.delete(key)) {
                    tree.flush();
                }
            }
        }
    }
```

- [ ] **Step 4: Implement `IndexManager.onDelete`.** Add after `onUpdate`:

```java
    public void onDelete(String db, String schema, int docId, JSONObject doc) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.onDelete(db, schema, docId, doc);
        } finally {
            lock.writeLock().unlock();
        }
    }
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `cd Node && mvn -q -Dtest=IndexManagerOnDeleteTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerOnDeleteTest.java
git commit -m "feat(index): onDelete removes a document's composite key from each index"
```

---

### Task 3: `WriteOperation.deleteDocument` + `applyDelete`

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java`
- Test: `Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationDeleteTest.java` (create)

**Interfaces:**
- Consumes: `IndexManager.onDelete` (Task 2); `PathBuilder.getPathToDocument`; `FileReader`; `JsonKeys.VERSION`.
- Produces:
  - `WriteOperation.deleteDocument(String database, String schema, String id, int expectedVersion)` → `Response` (SUCCESS; ERROR "Document not found"; ERROR "Version conflict..." content=current version).
  - `WriteOperation.applyDelete(String database, String schema, String id)` → `Response` (SUCCESS; absent file = success no-op).
  - `WriteService.deleteDocument(String db, String schema, String id, int expectedVersion)` → `Response`.
  - `WriteService.applyDelete(String db, String schema, String id)` → `Response`.

- [ ] **Step 1: Write the failing tests** `WriteOperationDeleteTest.java`:

```java
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
```

- [ ] **Step 2: Run them, verify they fail**

Run: `cd Node && mvn -q -Dtest=WriteOperationDeleteTest test`
Expected: FAIL — `deleteDocument` / `applyDelete` undefined.

- [ ] **Step 3: Implement the methods** in `WriteOperation` (after `applyUpdate`/`fireOnUpdate`):

```java
    public Response deleteDocument(String database, String schema, String id, int expectedVersion) {
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    return new Response(ResponseType.ERROR, "Document not found");

                FileReader fileReader = new FileReader(documentFile);
                fileReader.read();
                JSONObject doc = new JSONObject(fileReader.getContent());
                int storedVersion = doc.optInt(JsonKeys.VERSION, 1);
                if (storedVersion != expectedVersion)
                    return new Response(ResponseType.ERROR,
                            "Version conflict: document is at version " + storedVersion, storedVersion);

                if (!documentFile.delete())
                    return new Response(ResponseType.ERROR, "Failed to delete document");
                fireOnDelete(database, schema, Integer.parseInt(id), doc);
                return new Response(ResponseType.SUCCESS, "Document deleted successfully");
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    public Response applyDelete(String database, String schema, String id) {
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    return new Response(ResponseType.SUCCESS, "Document already absent");
                FileReader fileReader = new FileReader(documentFile);
                fileReader.read();
                JSONObject doc = new JSONObject(fileReader.getContent());
                documentFile.delete();
                fireOnDelete(database, schema, Integer.parseInt(id), doc);
                return new Response(ResponseType.SUCCESS, "Document deleted successfully");
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    private void fireOnDelete(String database, String schema, int docId, JSONObject doc) {
        try {
            indexManager.onDelete(database, schema, docId, doc);
        } catch (Exception indexError) {
            log.error("index maintenance failed for {}.{} doc {} on delete", database, schema, docId, indexError);
        }
    }
```

- [ ] **Step 4: Add the `WriteService` wrappers** (after `applyUpdate`):

```java
    public Response deleteDocument(String database, String schema, String id, int expectedVersion) {
        try {
            return writeOperation.deleteDocument(database, schema, id, expectedVersion);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }

    public Response applyDelete(String database, String schema, String id) {
        try {
            return writeOperation.applyDelete(database, schema, id);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }
```

- [ ] **Step 5: Run the tests, verify they pass**

Run: `cd Node && mvn -q -Dtest=WriteOperationDeleteTest test`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationDeleteTest.java
git commit -m "feat(node): deleteDocument (version-checked) + applyDelete (verbatim) with index key removal"
```

---

### Task 4: Node REST delete endpoint + replication

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/controllers/write/WriteController.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/model/Node.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java` (add `broadcastDelete`)
- Test: `Node/src/test/java/com/database/atypon/Node/controllers/write/WriteControllerDeleteTest.java` (create)

**Interfaces:**
- Consumes: `WriteService.deleteDocument` / `applyDelete` (Task 3); `AuthenticationService`; `Token.INTERNAL`; `Network.nodes`; `Broadcaster.broadcast`.
- Produces:
  - `POST /write/document/delete?database=&schema=&id=&version=` → `Vector<Response>`.
  - `WriteService.broadcastDelete(String db, String schema, String id)` → `List<Response>`.
  - `Node.deleteDocument(String db, String schema, String id)` → `Response` (peer call, `internal` token).

- [ ] **Step 1: Write the failing controller test** `WriteControllerDeleteTest.java`:

```java
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
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd Node && mvn -q -Dtest=WriteControllerDeleteTest test`
Expected: FAIL — `controller.deleteDocument` undefined.

- [ ] **Step 3: Add `broadcastDelete` to `WriteService`** (after `broadcastUpdate`):

```java
    public List<Response> broadcastDelete(String database, String schema, String id) {
        List<Supplier<Response>> tasks = new ArrayList<>();
        for (Node node : Network.nodes) {
            tasks.add(() -> node.deleteDocument(database, schema, id));
        }
        return Broadcaster.broadcast(tasks);
    }
```

- [ ] **Step 4: Add the peer call to `Node` (model)** (after `updateDocument`):

```java
    public Response deleteDocument(String database, String schema, String id) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add("authorization", "internal");
        HttpEntity entity = new HttpEntity(headers);
        try {
            Vector<LinkedHashMap<String, String>> response = restTemplate.postForObject(getURL() +
                            "/write/document/delete?database=" + database + "&schema=" + schema + "&id=" + id,
                    entity, Vector.class);
            return new Response(ResponseType.valueOf(response.get(0).get("responseType")), response.get(0).get("message"));
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, this.name + ": " + e.getMessage());
        }
    }
```

- [ ] **Step 5: Add the endpoint to `WriteController`** (after `updateDocument`). Class is `@RequestMapping("/write")`:

```java
    @PostMapping(value = "/document/delete", produces = "application/json")
    public Vector<Response> deleteDocument(@RequestParam String database,
                                           @RequestParam String schema,
                                           @RequestParam String id,
                                           @RequestParam(required = false, defaultValue = "0") int version,
                                           @RequestHeader("authorization") String token) {
        if (!authenticationService.isUserToken(token))
            return new Vector<>(List.of(new Response(ResponseType.ERROR, "Invalid token")));

        if (authenticationService.isInternalToken(token))
            return new Vector<>(List.of(writeService.applyDelete(database, schema, id)));

        Vector<Response> responses = new Vector<>();
        Response result = writeService.deleteDocument(database, schema, id, version);
        responses.add(result);
        if (result.getResponseType() == ResponseType.SUCCESS) {
            responses.addAll(writeService.broadcastDelete(database, schema, id));
        }
        return responses;
    }
```

- [ ] **Step 6: Run the controller test, verify it passes**

Run: `cd Node && mvn -q -Dtest=WriteControllerDeleteTest test`
Expected: PASS (3 tests).

- [ ] **Step 7: Full Node suite (regression)**

Run: `cd Node && mvn test`
Expected: PASS (all tests, including M1/M2).

- [ ] **Step 8: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/controllers/write/WriteController.java Node/src/main/java/com/database/atypon/Node/model/Node.java Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java Node/src/test/java/com/database/atypon/Node/controllers/write/WriteControllerDeleteTest.java
git commit -m "feat(node): /write/document/delete endpoint with verbatim replication to peers"
```

---

### Task 5: DBMS gateway forwarding for delete

**Files:**
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/database_system/PathBuilder.java`
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/database_system/write/WriteRequest.java`
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/service/WriteService.java`

**Interfaces:**
- Consumes: node `POST /write/document/delete?...` (Task 4).
- Produces:
  - `PathBuilder.buildDeleteDocumentPath(db, schema, id, version)`.
  - `WriteRequest.deleteDocument(String db, String schema, String id, int version, String token, String nodeURL)` → `String`.
  - `WriteService.deleteDocument(String db, String schema, String id, int version, String token, String nodeURL)` → void; throws with the node's message on ERROR (conflict/not-found).

- [ ] **Step 1: Add the path builder.** In DBMS `PathBuilder`, after `buildUpdateDocumentPath`:

```java
    public static String buildDeleteDocumentPath(String databaseName, String schemaName, String id, int version){
        return "/write/document/delete?" + "database=" + databaseName + "&schema=" + schemaName
                + "&id=" + id + "&version=" + version;
    }
```

- [ ] **Step 2: Add the forwarder.** In DBMS `WriteRequest`, after `updateDocument`:

```java
    public static String deleteDocument(String databaseName, String schemaName, String id, int version,
                                        String token, String nodeURL) {
        RestTemplate restTemplate = new RestTemplate();
        String url = nodeURL + PathBuilder.buildDeleteDocumentPath(databaseName, schemaName, id, version);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", token);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        return restTemplate.postForObject(url, request, String.class);
    }
```

- [ ] **Step 3: Add the gateway service method.** In DBMS `WriteService`, after `updateDocument`:

```java
    public void deleteDocument(String databaseName, String schemaName, String id, int version,
                               String token, String nodeURL) throws Exception {
        String raw = com.database.atypon.DBMS.database_system.write.WriteRequest
                .deleteDocument(databaseName, schemaName, id, version, token, nodeURL);
        org.json.JSONArray responses = new org.json.JSONArray(raw);
        if (responses.isEmpty()) {
            throw new Exception("No response from node");
        }
        JSONObject first = responses.getJSONObject(0);
        if ("ERROR".equals(first.optString("responseType"))) {
            throw new Exception(first.optString("message"));
        }
    }
```

- [ ] **Step 4: Build the DBMS module**

Run: `cd DBMS && mvn -q -DskipTests package`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add DBMS/src/main/java/com/database/atypon/DBMS/database_system/PathBuilder.java DBMS/src/main/java/com/database/atypon/DBMS/database_system/write/WriteRequest.java DBMS/src/main/java/com/database/atypon/DBMS/service/WriteService.java
git commit -m "feat(gateway): forward document delete to the node"
```

---

### Task 6: Delete button on the Update page + live validation

**Files:**
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/controller/UpdateController.java`
- Modify: `DBMS/src/main/resources/templates/update.html`

**Interfaces:**
- Consumes: `WriteService.deleteDocument` (Task 5); existing `loadForEdit` (private, in `UpdateController`).
- Produces: `POST /update/delete` (params `database`, `schema`, `id`, `version`).

- [ ] **Step 1: Add the controller mapping.** In `UpdateController`, after `save`:

```java
    @PostMapping("/delete")
    public String delete(@RequestParam String database, @RequestParam String schema,
                         @RequestParam String id, @RequestParam int version,
                         HttpServletRequest request, Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        model.addAttribute("f_id", id);
        try {
            writeService.deleteDocument(database, schema, id, version, token, nodeURL);
            model.addAttribute("updateMessage", "Deleted document " + id);
            // the document is gone: do not reload the edit form
        } catch (Exception e) {
            model.addAttribute("updateError", e.getMessage());
            loadForEdit(database, schema, id, request, model); // conflict: show the current version
        }
        return "update";
    }
```

- [ ] **Step 2: Add the Delete form** to `update.html`, inside the loaded-document card, right after the Save form's closing `</form>`:

```html
                <form method="post" th:action="@{/update/delete}" class="mt-2"
                      onsubmit="return confirm('Delete this document?');">
                    <input type="hidden" name="database" th:value="${f_database}"/>
                    <input type="hidden" name="schema" th:value="${f_schema}"/>
                    <input type="hidden" name="id" th:value="${f_id}"/>
                    <input type="hidden" name="version" th:value="${version}"/>
                    <button type="submit" class="btn btn-danger btn-block">Delete</button>
                </form>
```

- [ ] **Step 3: Build, run the cluster, validate live.** Rebuild Node + DBMS (JDK 17), run Node (8080) → BootstrappingNode (8079) → DBMS (8078). Scriptable check (matches the UI path; `admin` is the real user token):

```bash
N=http://localhost:8080; A='-H Authorization:admin -H Content-Type:application/json'
curl -s -X POST "$N/admin/database/create?databaseName=m3db" -H "Authorization:internal" >/dev/null
curl -s -X POST "$N/write/schema/new?database=m3db" -H "Authorization:internal" -H "Content-Type:application/json" -d '{"schemaName":"t","schema":{"n":"Integer"}}' >/dev/null
curl -s -X POST "$N/write/document/new?database=m3db&schema=t" -H "Authorization:internal" -H "Content-Type:application/json" -d '{"n":1}' >/dev/null
curl -s -X POST "$N/admin/index/create?database=m3db&schema=t&field=n" -H "Authorization:internal" >/dev/null
# stale delete (wrong version) -> conflict, doc remains
curl -s -X POST "$N/write/document/delete?database=m3db&schema=t&id=0&version=99" $A
# correct delete at version 1 -> success
curl -s -X POST "$N/write/document/delete?database=m3db&schema=t&id=0&version=1" $A
# doc gone
curl -s -X POST "$N/user/read/document?databaseName=m3db&schemaName=t&id=0" -H "Authorization:internal"
# index no longer returns it
curl -s -X POST "$N/user/index/query" -H "Authorization:internal" -H "Content-Type:application/json" -d '{"database":"m3db","schema":"t","field":"n","op":"EQ","value":1}'
```
Expected: stale delete → ERROR "Version conflict: document is at version 1"; correct delete → SUCCESS; read → ERROR/empty (doc gone); index query → `content:[]`.

Then in the browser at `http://localhost:8078` (login `mahmoud`/`admin`): Update Document → Fetch a doc → **Delete** → "Deleted document N"; the Read Documents page no longer lists it. Fetch again and try Delete with a stale version (e.g. re-submit an old tab) → conflict message + reload.

- [ ] **Step 4: Commit**

```bash
git add DBMS/src/main/java/com/database/atypon/DBMS/controller/UpdateController.java DBMS/src/main/resources/templates/update.html
git commit -m "feat(gateway): Delete button on the Update page (version-checked, conflict-aware)"
```

---

### Task 7 (separable): convert update index maintenance to incremental

Drop this task for a tighter M3 — update keeps M2's correct full rebuild and everything above still works. This task retires the rebuild now that key deletion exists.

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java` (change `onUpdate`)
- Modify: `Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java` (change `onUpdate` signature)
- Modify: `Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java` (capture old doc; new `fireOnUpdate` args in both `updateDocument` and `applyUpdate`)
- Modify: `Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationUpdateTest.java` (the `onUpdate` verify signature changes)
- Test: `Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerIncrementalUpdateTest.java` (create)

**Interfaces:**
- Produces:
  - `IndexService.onUpdate(String db, String schema, int docId, JSONObject oldDoc, JSONObject newDoc)` — for each indexed field: if `oldDoc` has it, delete the old composite key; if `newDoc` has it, insert the new composite key (skip if already present).
  - `IndexManager.onUpdate(String db, String schema, int docId, JSONObject oldDoc, JSONObject newDoc)` (write-locked).
  - `WriteOperation.fireOnUpdate(String database, String schema, int docId, JSONObject oldDoc, JSONObject newDoc)`.

- [ ] **Step 1: Write the failing incremental test** `IndexManagerIncrementalUpdateTest.java`:

```java
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
```

- [ ] **Step 2: Run it, verify it fails to compile** (old `onUpdate(db,schema)` signature)

Run: `cd Node && mvn -q -Dtest=IndexManagerIncrementalUpdateTest test`
Expected: FAIL — no `onUpdate(String,String,int,JSONObject,JSONObject)`.

- [ ] **Step 3: Replace `IndexService.onUpdate`** (remove the rebuild version, add incremental):

```java
    /** Patch every index on this schema for a document update: remove the old composite key, add the new. */
    public void onUpdate(String db, String schema, int docId, JSONObject oldDoc, JSONObject newDoc) throws IOException {
        for (String field : listIndexes(db, schema)) {
            try (Pager pager = new Pager(indexFile(db, schema, field).toFile())) {
                BPlusTree tree = BPlusTree.open(pager);
                KeyType keyType = indexKeyType(pager);
                boolean changed = false;
                if (oldDoc.has(field)) {
                    byte[] oldKey = KeyCodec.encode(keyType, coerce(keyType, oldDoc.get(field)), docId);
                    if (tree.delete(oldKey)) changed = true;
                }
                if (newDoc.has(field)) {
                    byte[] newKey = KeyCodec.encode(keyType, coerce(keyType, newDoc.get(field)), docId);
                    if (!tree.contains(newKey)) { tree.insert(newKey); changed = true; }
                }
                if (changed) tree.flush();
            }
        }
    }
```

- [ ] **Step 4: Replace `IndexManager.onUpdate`** signature:

```java
    public void onUpdate(String db, String schema, int docId, JSONObject oldDoc, JSONObject newDoc) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.onUpdate(db, schema, docId, oldDoc, newDoc);
        } finally {
            lock.writeLock().unlock();
        }
    }
```

- [ ] **Step 5: Update `WriteOperation`** to capture the old document and pass both to the hook.

In `updateDocument`, replace the version-read line
`int storedVersion = new JSONObject(fileReader.getContent()).optInt(JsonKeys.VERSION, 1);`
with:

```java
                JSONObject oldDoc = new JSONObject(fileReader.getContent());
                int storedVersion = oldDoc.optInt(JsonKeys.VERSION, 1);
```

and replace the `fireOnUpdate(database, schema);` call (after the write) with:

```java
                fireOnUpdate(database, schema, Integer.parseInt(id), oldDoc, newDoc);
```

In `applyUpdate`, read the old document before overwriting. Replace the body of the `synchronized` try (from locating the file through the write) so it reads the existing doc first:

```java
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                JSONObject oldDoc = new JSONObject();
                if (documentFile.exists()) {
                    FileReader fileReader = new FileReader(documentFile);
                    fileReader.read();
                    oldDoc = new JSONObject(fileReader.getContent());
                } else {
                    documentFile.createNewFile();
                }
                new FileWriter(documentFile, doc.toString()).write();
                fireOnUpdate(database, schema, Integer.parseInt(id), oldDoc, doc);
                return new Response(ResponseType.SUCCESS, "Document updated successfully");
```

Replace `fireOnUpdate`:

```java
    private void fireOnUpdate(String database, String schema, int docId, JSONObject oldDoc, JSONObject newDoc) {
        try {
            indexManager.onUpdate(database, schema, docId, oldDoc, newDoc);
        } catch (Exception indexError) {
            log.error("index maintenance failed for {}.{} doc {} on update", database, schema, docId, indexError);
        }
    }
```

- [ ] **Step 6: Fix the M2 update test.** In `WriteOperationUpdateTest.updateWithMatchingVersionIncrementsAndWrites`, change the verification from the old signature to:

```java
        verify(indexManagerMock).onUpdate(eq("updb"), eq("users"), eq(0), any(), any());
```

(Add `import static org.mockito.ArgumentMatchers.any;` if not already present — it is.)

- [ ] **Step 7: Run the affected tests, then the full suite**

Run: `cd Node && mvn -q -Dtest=IndexManagerIncrementalUpdateTest,WriteOperationUpdateTest test`
Expected: PASS.
Run: `cd Node && mvn test`
Expected: PASS (all tests).

- [ ] **Step 8: Re-validate update live** (rebuild Node, run cluster) with the M2 flow: update an indexed field and confirm the query reflects the new value and not the old (same as the Task 6 index check but via update). Then commit:

```bash
git add Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationUpdateTest.java Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerIncrementalUpdateTest.java
git commit -m "refactor(index): incremental onUpdate (delete old key + insert new), retiring full rebuild"
```

---

## Notes for the executor

- After each Node task, `cd Node && mvn test` under JDK 17 must stay green.
- `Response.getContent()` is `Object`; the delete conflict path puts the current version `Integer` — cast via `((Number) r.getContent()).intValue()`.
- The delete endpoint's `version` param defaults to 0 and is used only on the user path; the internal/replication peer call omits it (and `applyDelete` ignores it).
- Live-run order is Node → BootstrappingNode → DBMS; the local cluster from earlier sessions is stopped and must be restarted for Task 6/Task 7 live checks.
