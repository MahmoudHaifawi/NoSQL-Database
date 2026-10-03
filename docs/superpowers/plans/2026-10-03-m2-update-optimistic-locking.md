# M2 — Document Update + Optimistic Locking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a document update operation with optimistic concurrency control (a server-controlled `_version` per document; stale updates are rejected), full replication, index rebuild on update, and a fetch-then-edit web UI.

**Architecture:** The data node gains an `updateDocument` (origin: check version + increment + write) and an `applyUpdate` (verbatim apply for replicated writes). An update rebuilds the schema's indexes (B+-tree delete is M3). The origin broadcasts the stamped document to peers with the `internal` token; peers apply it verbatim. The DBMS gateway adds a fetch-then-edit page that forwards to the node.

**Tech Stack:** Java 17, Spring Boot 2.7.x, org.json, JUnit 5 + AssertJ + Mockito, Thymeleaf + Bootstrap 4.

**Spec:** `docs/superpowers/specs/2026-10-03-m2-update-optimistic-locking-design.md`

## Global Constraints

- Build/test with `JAVA_HOME=C:\Program Files\Java\jdk-17` (Spring Boot 2.7.x does not run on the default newer JDK). Maven 3.9 on PATH.
- Work directly on the `master` branch — commit and push there, no per-feature branches (current user workflow).
- `_version` is a reserved, server-controlled field: never trust a client-supplied value except as the *expected* version on update; the server sets the stored value.
- Index-maintenance errors are logged and must NOT fail the write (indexes are derived/rebuildable) — match the existing `onInsert` hook behaviour.
- Reuse existing patterns: `Response(type, message)` / `Response(type, message, content)`; controllers return `Vector<Response>`; peer calls use the `internal` auth token; node data lives under `./data/<db>/<schema>-records/<id>.json`.

---

### Task 1: Stamp `_version` on document create

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/utils/JsonKeys.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java` (inside `createDocument`)
- Test: `Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationIndexHookTest.java` (add a test)

**Interfaces:**
- Consumes: existing `WriteOperation(IndexManager)`, `createDocument(String, String, JSONObject)`.
- Produces: `JsonKeys.VERSION` (`"_version"`); every created document file now contains `"_version": 1`.

- [ ] **Step 1: Add the failing test** to `WriteOperationIndexHookTest`:

```java
    @Test
    void createStampsVersionOne() throws Exception {
        WriteOperation writeOp = new WriteOperation(indexManagerMock);
        writeOp.createDocument("hookdb", "users", new JSONObject().put("Age", 42));

        String written = Files.readString(Paths.get("./data/hookdb/users-records/0.json"));
        assertThat(new JSONObject(written).getInt("_version")).isEqualTo(1);
    }
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd Node && mvn -q -Dtest=WriteOperationIndexHookTest#createStampsVersionOne test`
Expected: FAIL — JSON has no `_version` key (`JSONException`).

- [ ] **Step 3: Add the constant.** In `JsonKeys.java`, add next to the other constants:

```java
    public static final String VERSION = "_version";
```

- [ ] **Step 4: Stamp the version.** In `WriteOperation.createDocument`, inside the `synchronized (this)` block, immediately before `FileWriter fileWriter = new FileWriter(documentFile, documentJSON.toString());`, add:

```java
                documentJSON.put(JsonKeys.VERSION, 1); // reserved, server-controlled
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `cd Node && mvn -q -Dtest=WriteOperationIndexHookTest test`
Expected: PASS (all tests in the class, including the existing two).

- [ ] **Step 6: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/utils/JsonKeys.java Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationIndexHookTest.java
git commit -m "feat(node): stamp _version=1 on document create"
```

---

### Task 2: Index rebuild hook `IndexManager.onUpdate`

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java`
- Test: `Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerOnUpdateTest.java` (create)

**Interfaces:**
- Consumes: existing `IndexService.listIndexes(db,schema)`, `dropIndex(db,schema,field)`, `createIndex(db,schema,field)`; `IndexManager(Path dataRoot)` ctor.
- Produces: `IndexManager.onUpdate(String db, String schema) throws IOException` — rebuilds every index on the schema (drop + bulk-load recreate) under the write lock.

- [ ] **Step 1: Write the failing test** `IndexManagerOnUpdateTest.java`:

```java
package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd Node && mvn -q -Dtest=IndexManagerOnUpdateTest test`
Expected: FAIL — `onUpdate` is undefined (compile error).

- [ ] **Step 3: Implement `onUpdate`.** Add to `IndexManager` (after `onInsert`):

```java
    /**
     * Rebuilds every index on the schema from the current records. Called after a document update,
     * where an indexed field's value may have changed and in-place key deletion does not yet exist
     * (that is M3). Runs under the write lock so readers never see a half-rebuilt index.
     */
    public void onUpdate(String db, String schema) throws IOException {
        lock.writeLock().lock();
        try {
            for (String field : indexService.listIndexes(db, schema)) {
                indexService.dropIndex(db, schema, field);
                indexService.createIndex(db, schema, field);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `cd Node && mvn -q -Dtest=IndexManagerOnUpdateTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerOnUpdateTest.java
git commit -m "feat(index): onUpdate rebuilds schema indexes after a document update"
```

---

### Task 3: Update engine — `WriteOperation.updateDocument` + `applyUpdate`

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java`
- Test: `Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationUpdateTest.java` (create)

**Interfaces:**
- Consumes: `IndexManager.onUpdate(db,schema)` (Task 2); `Validators.validateDocument`; `PathBuilder.getPathToDocument`; `FileReader`; `FileWriter`; `JsonKeys.VERSION`.
- Produces:
  - `WriteOperation.updateDocument(String database, String schema, String id, JSONObject newDoc, int expectedVersion)` → `Response` (SUCCESS content=new version Integer; ERROR "Document not found"; ERROR "Version conflict..." content=current version Integer; ERROR validation message). No write on any error.
  - `WriteOperation.applyUpdate(String database, String schema, String id, JSONObject doc)` → `Response` SUCCESS, writes `doc` verbatim (keeps its `_version`), no version check.
  - `WriteService.updateDocument(String db, String schema, String id, HashMap<String,Object> document, int expectedVersion)` → `Response`.
  - `WriteService.applyUpdate(String db, String schema, String id, HashMap<String,Object> document)` → `Response`.

- [ ] **Step 1: Write the failing tests** `WriteOperationUpdateTest.java`:

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WriteOperationUpdateTest {

    private final Path db = Paths.get("./data/updb");
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
        // seed one document at version 1
        writeOp.createDocument("updb", "users", new JSONObject().put("Age", 42));
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
    void updateWithMatchingVersionIncrementsAndWrites() throws Exception {
        var resp = writeOp.updateDocument("updb", "users", "0", new JSONObject().put("Age", 50), 1);

        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(((Number) resp.getContent()).intValue()).isEqualTo(2);
        JSONObject stored = new JSONObject(Files.readString(db.resolve("users-records").resolve("0.json")));
        assertThat(stored.getInt("Age")).isEqualTo(50);
        assertThat(stored.getInt("_version")).isEqualTo(2);
        verify(indexManagerMock).onUpdate(eq("updb"), eq("users"));
    }

    @Test
    void updateWithStaleVersionConflictsAndDoesNotWrite() throws Exception {
        writeOp.updateDocument("updb", "users", "0", new JSONObject().put("Age", 50), 1); // -> v2
        var resp = writeOp.updateDocument("updb", "users", "0", new JSONObject().put("Age", 99), 1); // stale

        assertThat(resp.getResponseType()).isEqualTo(ResponseType.ERROR);
        assertThat(((Number) resp.getContent()).intValue()).isEqualTo(2); // current version
        JSONObject stored = new JSONObject(Files.readString(db.resolve("users-records").resolve("0.json")));
        assertThat(stored.getInt("Age")).isEqualTo(50); // unchanged
        assertThat(stored.getInt("_version")).isEqualTo(2);
    }

    @Test
    void updateMissingDocumentReturnsNotFound() throws Exception {
        var resp = writeOp.updateDocument("updb", "users", "999", new JSONObject().put("Age", 1), 1);
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.ERROR);
        assertThat(resp.getMessage()).contains("not found");
    }

    @Test
    void updateFailingValidationDoesNotWrite() throws Exception {
        var resp = writeOp.updateDocument("updb", "users", "0", new JSONObject().put("Age", "notAnInt"), 1);
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.ERROR);
        JSONObject stored = new JSONObject(Files.readString(db.resolve("users-records").resolve("0.json")));
        assertThat(stored.getInt("Age")).isEqualTo(42); // original untouched
    }

    @Test
    void applyUpdateWritesVerbatimWithoutVersionCheck() throws Exception {
        var resp = writeOp.applyUpdate("updb", "users", "0", new JSONObject().put("Age", 7).put("_version", 9));
        assertThat(resp.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        JSONObject stored = new JSONObject(Files.readString(db.resolve("users-records").resolve("0.json")));
        assertThat(stored.getInt("Age")).isEqualTo(7);
        assertThat(stored.getInt("_version")).isEqualTo(9); // taken from the document, not incremented
    }
}
```

- [ ] **Step 2: Run them, verify they fail**

Run: `cd Node && mvn -q -Dtest=WriteOperationUpdateTest test`
Expected: FAIL — `updateDocument` / `applyUpdate` undefined (compile error).

- [ ] **Step 3: Implement the two methods** in `WriteOperation` (after `createDocument`):

```java
    public Response updateDocument(String database, String schema, String id, JSONObject newDoc, int expectedVersion) {
        try {
            Validators.validateDocument(newDoc, schema, database);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    return new Response(ResponseType.ERROR, "Document not found");

                FileReader fileReader = new FileReader(documentFile);
                fileReader.read();
                int storedVersion = new JSONObject(fileReader.getContent()).optInt(JsonKeys.VERSION, 1);
                if (storedVersion != expectedVersion)
                    return new Response(ResponseType.ERROR,
                            "Version conflict: document is at version " + storedVersion, storedVersion);

                int newVersion = storedVersion + 1;
                newDoc.put(JsonKeys.VERSION, newVersion);
                new FileWriter(documentFile, newDoc.toString()).write();
                fireOnUpdate(database, schema);
                return new Response(ResponseType.SUCCESS, "Document updated successfully", newVersion);
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    public Response applyUpdate(String database, String schema, String id, JSONObject doc) {
        try {
            Validators.validateDocument(doc, schema, database);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
        synchronized (this) {
            try {
                File documentFile = new File(PathBuilder.getPathToDocument(database, schema, id));
                if (!documentFile.exists())
                    documentFile.createNewFile();
                new FileWriter(documentFile, doc.toString()).write();
                fireOnUpdate(database, schema);
                return new Response(ResponseType.SUCCESS, "Document updated successfully");
            } catch (Exception e) {
                return new Response(ResponseType.ERROR, e.getMessage());
            }
        }
    }

    private void fireOnUpdate(String database, String schema) {
        try {
            indexManager.onUpdate(database, schema);
        } catch (Exception indexError) {
            // indexes are derived/rebuildable; don't fail the write on a maintenance error
            log.error("index maintenance failed for {}.{} on update", database, schema, indexError);
        }
    }
```

- [ ] **Step 4: Add the `WriteService` wrappers** (after `createDocument`):

```java
    public Response updateDocument(String database, String schema, String id,
                                   HashMap<String, Object> document, int expectedVersion) {
        try {
            return writeOperation.updateDocument(database, schema, id, new JSONObject(document), expectedVersion);
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }

    public Response applyUpdate(String database, String schema, String id, HashMap<String, Object> document) {
        try {
            return writeOperation.applyUpdate(database, schema, id, new JSONObject(document));
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }
```

- [ ] **Step 5: Run the tests, verify they pass**

Run: `cd Node && mvn -q -Dtest=WriteOperationUpdateTest test`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationUpdateTest.java
git commit -m "feat(node): updateDocument (optimistic) + applyUpdate (verbatim) with index rebuild"
```

---

### Task 4: Node REST endpoint + replication

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/controllers/write/WriteController.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/model/Node.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java` (add `broadcastUpdate`)
- Test: `Node/src/test/java/com/database/atypon/Node/controllers/write/WriteControllerUpdateTest.java` (create)

**Interfaces:**
- Consumes: `WriteService.updateDocument`, `applyUpdate` (Task 3); `AuthenticationService.isUserToken/isInternalToken`; `Token.INTERNAL`; `Network.nodes`; `Broadcaster.broadcast`.
- Produces:
  - `POST /write/document/update?database=&schema=&id=` with `@RequestBody HashMap<String,Object>` + `@RequestHeader("authorization")` → `Vector<Response>`.
  - `WriteService.broadcastUpdate(String db, String schema, String id, HashMap<String,Object> document)` → `List<Response>`.
  - `Node.updateDocument(String db, String schema, String id, HashMap<String,Object> document)` → `Response` (peer call, `internal` token).

- [ ] **Step 1: Write the failing controller test** `WriteControllerUpdateTest.java`:

```java
package com.database.atypon.Node.controllers.write;

import com.database.atypon.Node.services.authentication.AuthenticationService;
import com.database.atypon.Node.services.write.WriteService;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
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

class WriteControllerUpdateTest {

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
        Vector<Response> out = controller.updateDocument("db", "s", "0", new HashMap<>(), "bad");
        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.ERROR);
        verify(writeService, never()).updateDocument(any(), any(), any(), any(), anyInt());
    }

    @Test
    void internalTokenAppliesVerbatimWithoutBroadcast() {
        when(auth.isInternalToken("internal")).thenReturn(true);
        when(writeService.applyUpdate(eq("db"), eq("s"), eq("0"), any()))
                .thenReturn(new Response(ResponseType.SUCCESS, "ok"));
        HashMap<String, Object> doc = new HashMap<>();
        doc.put("_version", 5);

        Vector<Response> out = controller.updateDocument("db", "s", "0", doc, "internal");

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).applyUpdate(eq("db"), eq("s"), eq("0"), any());
        verify(writeService, never()).updateDocument(any(), any(), any(), any(), anyInt());
    }

    @Test
    void userTokenRunsOriginUpdateWithExpectedVersionFromBody() {
        when(auth.isInternalToken(anyString())).thenReturn(false);
        when(writeService.updateDocument(eq("db"), eq("s"), eq("0"), any(), eq(3)))
                .thenReturn(new Response(ResponseType.SUCCESS, "updated", 4));
        HashMap<String, Object> doc = new HashMap<>();
        doc.put("Age", 10);
        doc.put("_version", 3);

        Vector<Response> out = controller.updateDocument("db", "s", "0", doc, "admin");

        assertThat(out.get(0).getResponseType()).isEqualTo(ResponseType.SUCCESS);
        verify(writeService).updateDocument(eq("db"), eq("s"), eq("0"), any(), eq(3));
    }
}
```

- [ ] **Step 2: Run it, verify it fails**

Run: `cd Node && mvn -q -Dtest=WriteControllerUpdateTest test`
Expected: FAIL — `controller.updateDocument` undefined (compile error).

- [ ] **Step 3: Add `broadcastUpdate` to `WriteService`** (after `broadcastDocument`):

```java
    public List<Response> broadcastUpdate(String database, String schema, String id,
                                          HashMap<String, Object> document) {
        List<Supplier<Response>> tasks = new ArrayList<>();
        for (Node node : Network.nodes) {
            tasks.add(() -> node.updateDocument(database, schema, id, document));
        }
        return Broadcaster.broadcast(tasks);
    }
```

- [ ] **Step 4: Add the peer call to `Node` (model)** (after `createDocument`):

```java
    public Response updateDocument(String database, String schema, String id, HashMap<String, Object> document) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add("authorization", "internal");
        HttpEntity entity = new HttpEntity(document, headers);
        try {
            Vector<LinkedHashMap<String, String>> response = restTemplate.postForObject(getURL() +
                            "/write/document/update?database=" + database + "&schema=" + schema + "&id=" + id,
                    entity, Vector.class);
            return new Response(ResponseType.valueOf(response.get(0).get("responseType")), response.get(0).get("message"));
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, this.name + ": " + e.getMessage());
        }
    }
```

- [ ] **Step 5: Add the endpoint to `WriteController`** (after `createDocument`). Note the class is `@RequestMapping("/write")`, so the path is `/document/update`:

```java
    @PostMapping(value = "/document/update", produces = "application/json")
    public Vector<Response> updateDocument(@RequestParam String database,
                                           @RequestParam String schema,
                                           @RequestParam String id,
                                           @RequestBody HashMap<String, Object> document,
                                           @RequestHeader("authorization") String token) {
        if (!authenticationService.isUserToken(token))
            return new Vector<>(List.of(new Response(ResponseType.ERROR, "Invalid token")));

        if (authenticationService.isInternalToken(token))
            return new Vector<>(List.of(writeService.applyUpdate(database, schema, id, document)));

        int expectedVersion = 1;
        Object v = document.get("_version");
        if (v instanceof Number)
            expectedVersion = ((Number) v).intValue();

        Vector<Response> responses = new Vector<>();
        Response result = writeService.updateDocument(database, schema, id, document, expectedVersion);
        responses.add(result);

        if (result.getResponseType() == ResponseType.SUCCESS) {
            // stamp the new version onto the document and replicate it verbatim to peers
            document.put("_version", ((Number) result.getContent()).intValue());
            responses.addAll(writeService.broadcastUpdate(database, schema, id, document));
        }
        return responses;
    }
```

- [ ] **Step 6: Run the test, verify it passes**

Run: `cd Node && mvn -q -Dtest=WriteControllerUpdateTest test`
Expected: PASS (3 tests).

- [ ] **Step 7: Full Node suite (regression)**

Run: `cd Node && mvn -q test`
Expected: PASS (all tests).

- [ ] **Step 8: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/controllers/write/WriteController.java Node/src/main/java/com/database/atypon/Node/model/Node.java Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java Node/src/test/java/com/database/atypon/Node/controllers/write/WriteControllerUpdateTest.java
git commit -m "feat(node): /write/document/update endpoint with verbatim replication to peers"
```

---

### Task 5: DBMS gateway forwarding for update

**Files:**
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/database_system/PathBuilder.java`
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/database_system/write/WriteRequest.java`
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/service/WriteService.java`

**Interfaces:**
- Consumes: node `POST /write/document/update?...` (Task 4); existing `ReadService.readById` for fetch-for-edit.
- Produces:
  - `PathBuilder.buildUpdateDocumentPath(db, schema, id)` → `"/write/document/update?database=..&schema=..&id=.."`.
  - `WriteRequest.updateDocument(String db, String schema, String id, JSONObject document, String token, String nodeURL)` → `String` (raw node JSON array body).
  - `WriteService.updateDocument(String db, String schema, String id, String documentJson, int expectedVersion, String token, String nodeURL)` → `int` new version; throws `Exception` with the conflict/error message on failure.

- [ ] **Step 1: Add the path builder.** In DBMS `PathBuilder`, next to `buildCreateDocumentPath`:

```java
    public static String buildUpdateDocumentPath(String databaseName, String schemaName, String id){
        return "/write/document/update?" + "database=" + databaseName + "&schema=" + schemaName + "&id=" + id;
    }
```

- [ ] **Step 2: Add the forwarder.** In DBMS `WriteRequest`, after `createDocument`:

```java
    public static String updateDocument(String databaseName, String schemaName, String id,
                                        JSONObject document, String token, String nodeURL) {
        RestTemplate restTemplate = new RestTemplate();
        String url = nodeURL + PathBuilder.buildUpdateDocumentPath(databaseName, schemaName, id);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(document.toString(), headers);
        return restTemplate.postForObject(url, request, String.class);
    }
```

- [ ] **Step 3: Add the gateway service method.** In DBMS `WriteService`, add (the node returns a JSON array; `_version` carries the expected version into the body):

```java
    public int updateDocument(String databaseName, String schemaName, String id, String documentJson,
                              int expectedVersion, String token, String nodeURL) throws Exception {
        JSONObject doc = new JSONObject(documentJson);
        doc.put("_version", expectedVersion);
        String raw = com.database.atypon.DBMS.database_system.write.WriteRequest
                .updateDocument(databaseName, schemaName, id, doc, token, nodeURL);
        org.json.JSONArray responses = new org.json.JSONArray(raw);
        if (responses.isEmpty())
            throw new Exception("No response from node");
        JSONObject first = responses.getJSONObject(0);
        if ("ERROR".equals(first.optString("responseType")))
            throw new Exception(first.optString("message"));
        return first.optInt("content", expectedVersion + 1); // new version
    }
```

- [ ] **Step 4: Build the DBMS module**

Run: `cd DBMS && mvn -q -DskipTests package`
Expected: BUILD SUCCESS (jar produced). (No unit tests in the DBMS module; this layer is exercised live in Task 6.)

- [ ] **Step 5: Commit**

```bash
git add DBMS/src/main/java/com/database/atypon/DBMS/database_system/PathBuilder.java DBMS/src/main/java/com/database/atypon/DBMS/database_system/write/WriteRequest.java DBMS/src/main/java/com/database/atypon/DBMS/service/WriteService.java
git commit -m "feat(gateway): forward document update to the node"
```

---

### Task 6: DBMS fetch-then-edit UI + live validation

**Files:**
- Create: `DBMS/src/main/java/com/database/atypon/DBMS/controller/UpdateController.java`
- Create: `DBMS/src/main/resources/templates/update.html`
- Modify: `DBMS/src/main/resources/templates/dashboard.html` (nav link)
- Modify: `DBMS/src/main/java/com/database/atypon/DBMS/config/WebConfig.java` (protect `/update`, `/update/**`)

**Interfaces:**
- Consumes: `WriteService.updateDocument` (Task 5); `ReadService.readById` (returns pretty JSON string of the current doc, includes `_version`); session attributes `token`, `nodeURL`; `AuthInterceptor`.
- Produces: pages `GET /update`, `POST /update/fetch`, `POST /update/save`.

- [ ] **Step 1: Protect the routes.** In `WebConfig.addInterceptors`, add `"/update"` and `"/update/**"` to the `addPathPatterns(...)` list.

- [ ] **Step 2: Create `UpdateController`.** It fetches the current document, splits off `_version` into a hidden field, and on save re-attaches it as the expected version. On conflict it re-fetches so the user can re-apply against the latest:

```java
package com.database.atypon.DBMS.controller;

import com.database.atypon.DBMS.service.ReadService;
import com.database.atypon.DBMS.service.WriteService;
import lombok.AllArgsConstructor;
import org.json.JSONObject;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;

@Controller
@AllArgsConstructor
@RequestMapping("/update")
public class UpdateController {

    private final WriteService writeService;
    private final ReadService readService;

    @GetMapping
    public String page(HttpServletRequest request) {
        request.getSession();
        return "update";
    }

    @PostMapping("/fetch")
    public String fetch(@RequestParam String database, @RequestParam String schema,
                        @RequestParam String id, HttpServletRequest request, Model model) {
        loadForEdit(database, schema, id, request, model);
        return "update";
    }

    @PostMapping("/save")
    public String save(@RequestParam String database, @RequestParam String schema,
                       @RequestParam String id, @RequestParam String document,
                       @RequestParam int version, HttpServletRequest request, Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        model.addAttribute("f_id", id);
        try {
            int newVersion = writeService.updateDocument(database, schema, id, document, version, token, nodeURL);
            model.addAttribute("updateMessage", "Updated to version " + newVersion);
            loadForEdit(database, schema, id, request, model); // reload with the new version
        } catch (Exception e) {
            model.addAttribute("updateError", e.getMessage());
            loadForEdit(database, schema, id, request, model); // reload current (resolves a conflict)
        }
        return "update";
    }

    private void loadForEdit(String database, String schema, String id, HttpServletRequest request, Model model) {
        String token = (String) request.getSession().getAttribute("token");
        String nodeURL = (String) request.getSession().getAttribute("nodeURL");
        model.addAttribute("f_database", database);
        model.addAttribute("f_schema", schema);
        model.addAttribute("f_id", id);
        try {
            JSONObject doc = new JSONObject(readService.readById(database, schema, id, token, nodeURL));
            int version = doc.optInt("_version", 1);
            doc.remove("_version");
            model.addAttribute("docJson", doc.toString(2));
            model.addAttribute("version", version);
            model.addAttribute("loaded", true);
        } catch (Exception e) {
            model.addAttribute("updateError", "Could not read document " + id + ": " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Create `update.html`:**

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title>Update Document</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@4.3.1/dist/css/bootstrap.min.css"
          integrity="sha384-ggOyR0iXCbMQv3Xipma34MD+dH/1fQ784/j6cY/iJTQUOhcWr7x9JvoRxT2MZw1T" crossorigin="anonymous">
</head>
<body class="bg-light">
<nav class="navbar navbar-dark bg-dark">
    <span class="navbar-brand mb-0 h1">Update Document</span>
    <a class="btn btn-outline-light btn-sm" th:href="@{/dashboard}">&larr; Dashboard</a>
</nav>
<div class="container-fluid py-4">
    <div class="card border-info mb-4" style="max-width:40rem">
        <div class="card-header bg-info text-white">Fetch a document to edit</div>
        <div class="card-body">
            <form method="post" th:action="@{/update/fetch}">
                <div class="form-row">
                    <div class="form-group col"><label>Database</label>
                        <input class="form-control" name="database" th:value="${f_database}" required/></div>
                    <div class="form-group col"><label>Schema</label>
                        <input class="form-control" name="schema" th:value="${f_schema}" required/></div>
                    <div class="form-group col"><label>Id</label>
                        <input class="form-control" name="id" th:value="${f_id}" required/></div>
                </div>
                <button type="submit" class="btn btn-info btn-block">Fetch</button>
            </form>
        </div>
    </div>

    <p th:if="${updateMessage}" class="alert alert-success" th:text="${updateMessage}"></p>
    <p th:if="${updateError}" class="alert alert-danger" th:text="${updateError}"></p>

    <div class="card border-success" style="max-width:40rem" th:if="${loaded}">
        <div class="card-header bg-success text-white">
            Edit &mdash; version <span class="badge badge-light" th:text="${version}"></span>
        </div>
        <div class="card-body">
            <form method="post" th:action="@{/update/save}">
                <input type="hidden" name="database" th:value="${f_database}"/>
                <input type="hidden" name="schema" th:value="${f_schema}"/>
                <input type="hidden" name="id" th:value="${f_id}"/>
                <input type="hidden" name="version" th:value="${version}"/>
                <label>Document (JSON)</label>
                <textarea class="form-control" name="document" rows="10" th:text="${docJson}"></textarea>
                <button type="submit" class="btn btn-success btn-block mt-2">Save</button>
            </form>
        </div>
    </div>
</div>
</body>
</html>
```

- [ ] **Step 4: Add the dashboard link.** In `dashboard.html`, in the nav `div` that holds the Read/Index links, add:

```html
        <a class="btn btn-outline-dark" th:href="@{/update}">Update Document &rarr;</a>
```

- [ ] **Step 5: Build, run the cluster, validate live.** Rebuild Node + DBMS (JDK 17), run Node (8080) → BootstrappingNode (8079) → DBMS (8078). In the browser at `http://localhost:8078` (login `mahmoud`/`admin`):
  - Create database + schema, insert a document (or use an existing one).
  - Go to **Update Document**, fetch it (shows version N), edit a field, Save → "Updated to version N+1".
  - Save again with the now-stale page (re-open an old tab or re-use the old version) → conflict message, form reloads at the current version.
  - If the field is indexed, query it on the Secondary Index page and confirm the new value returns the doc and the old value does not.

Verify with curl (scriptable check, matching the live flow):

```bash
# login, create db+schema, insert, then update and re-read
curl -s -c cj -o /dev/null -X POST http://localhost:8078/login --data "username=mahmoud&password=admin"
curl -s -b cj -X POST "http://localhost:8080/admin/database/create?databaseName=m2db" -H "Authorization:internal" >/dev/null
curl -s -X POST "http://localhost:8080/write/schema/new?database=m2db" -H "Authorization:internal" -H "Content-Type:application/json" -d '{"schemaName":"t","schema":{"n":"Integer"}}'
curl -s -X POST "http://localhost:8080/write/document/new?database=m2db&schema=t" -H "Authorization:internal" -H "Content-Type:application/json" -d '{"n":1}'
# update doc 0 at version 1 -> expect SUCCESS, _version 2
curl -s -X POST "http://localhost:8080/write/document/update?database=m2db&schema=t&id=0" -H "Authorization:internal" -H "Content-Type:application/json" -d '{"n":2,"_version":1}'
# stale update at version 1 again -> expect ERROR version conflict
curl -s -X POST "http://localhost:8080/write/document/update?database=m2db&schema=t&id=0" -H "Authorization:internal" -H "Content-Type:application/json" -d '{"n":3,"_version":1}'
```
Expected: first update returns SUCCESS; second returns ERROR "Version conflict: document is at version 2".

- [ ] **Step 6: Commit**

```bash
git add DBMS/src/main/java/com/database/atypon/DBMS/controller/UpdateController.java DBMS/src/main/resources/templates/update.html DBMS/src/main/resources/templates/dashboard.html DBMS/src/main/java/com/database/atypon/DBMS/config/WebConfig.java
git commit -m "feat(gateway): fetch-then-edit Update Document page with optimistic conflict handling"
```

---

## Notes for the executor

- After each Node task, `cd Node && mvn -q test` under JDK 17 must stay green.
- `Response.getContent()` is `Object`; the update success path puts an `Integer` (new version) and the conflict path puts the current version `Integer` — cast via `((Number) r.getContent()).intValue()`.
- Replication correctness depends on broadcasting the document **with the new `_version` stamped** (Task 4, Step 5) so peers `applyUpdate` verbatim and converge to the same version.
- The live Docker/local run and browser validation is the executor-driven part of Task 6; the unit-testable logic is Tasks 1–4.
