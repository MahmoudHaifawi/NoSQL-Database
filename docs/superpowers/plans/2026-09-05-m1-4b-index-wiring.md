# M1·4b — Index Wiring (Service bean + REST endpoints + write-hook) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make indexing a live feature on the data node: a Spring `IndexManager` @Service (concurrency-safe wrapper over `IndexService`), REST endpoints to create/list/drop indexes and run field queries (with cluster broadcast for create/drop), and a write-hook so every document write maintains the indexes.

**Architecture:** `IndexManager` wraps `IndexService(Paths.get("./data"))` behind a single global `ReadWriteLock` (writes = createIndex/dropIndex/onInsert take the write lock; reads = query/list take the read lock). Adequate because writes are rare (per the brief); per-index locking is a noted future optimization. `IndexController` exposes admin create/list/drop + a user query endpoint, following the existing auth + broadcast conventions (broadcast to peers only when the caller token is `ADMIN`; peers receive `internal` and don't re-broadcast — each node builds its own index from its own replica). `WriteOperation.createDocument` calls `indexManager.onInsert(...)` once for the freshly-assigned docId, inside its existing `synchronized` block — and since document writes are already broadcast to every node, indexes are maintained cluster-wide automatically.

**Tech Stack:** Java 17, Spring Boot 2.7.6 (spring-web MVC), `org.json`, JUnit 5 + AssertJ (+ Mockito for the write-hook interaction test). No new deps. Builds on the finished `IndexService` (M1·4a) and `com.database.atypon.Node.index` engine.

**Spec:** `docs/superpowers/specs/2026-09-04-capstone-completion-roadmap.md` §5.8 (write hook) / §5.9 (API). The query UI page in the DBMS gateway is M1·4c.

## Global Constraints

- Java **17**; Spring Boot **2.7.5/2.7.6**; no new dependencies.
- **Build with JDK 17** (`JAVA_HOME=C:\Program Files\Java\jdk-17`; default JDK 26 breaks Spring Boot). `mvn` 3.9.9 on PATH.
- Reuse existing conventions: `Response`/`ResponseType`, `Vector<Response>` controller returns, `AuthenticationService.isAdminToken/isUserToken/isInternalToken`, `Token.ADMIN/USER/INTERNAL`, the broadcast-only-when-token==ADMIN pattern (see `AdminController.createDatabase`).
- **Exactly-once maintenance:** the write-hook fires `onInsert` for the new docId only, inside `WriteOperation.createDocument`'s `synchronized` block (no updates/deletes exist yet — those are M2/M3).
- Append this trailer to every commit message: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- End each task with `cd Node && mvn -q test` under JDK 17 (full suite stays green; M1·4a leaves 67 tests).

---

### Task 1: IndexManager @Service (concurrency-safe wrapper)

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java`
- Test: `Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerTest.java`

**Interfaces:**
- Consumes: `IndexService` (M1·4a), `com.database.atypon.Node.index.BPlusTree.Op`, `org.json.JSONObject`.
- Produces: `@Service IndexManager` with a no-arg constructor (production `dataRoot="./data"`) and an `IndexManager(Path dataRoot)` constructor (tests). Methods delegate to `IndexService` under a global `ReadWriteLock`: `createIndex(db,schema,field)`, `dropIndex(db,schema,field)`, `onInsert(db,schema,int docId,JSONObject doc)` (write lock); `listIndexes(db,schema)`, `query(db,schema,field,Op,value,high,ascending,offset,limit)` (read lock). All throw `IOException`.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=IndexManagerTest test`
Expected: FAIL — `IndexManager` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Spring-managed, concurrency-safe facade over {@link IndexService}. A single global
 * ReadWriteLock serializes index mutations (create/drop/onInsert) against readers
 * (query/list) so a REST query never observes a half-written index while a document
 * write maintains it. Writes are rare (per the design), so a global lock is adequate;
 * per-index locking is a possible future optimization.
 */
@Service
public class IndexManager {

    private final IndexService indexService;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public IndexManager() {
        this(Paths.get("./data"));
    }

    public IndexManager(Path dataRoot) {
        this.indexService = new IndexService(dataRoot);
    }

    public void createIndex(String db, String schema, String field) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.createIndex(db, schema, field);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void dropIndex(String db, String schema, String field) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.dropIndex(db, schema, field);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onInsert(String db, String schema, int docId, JSONObject doc) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.onInsert(db, schema, docId, doc);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> listIndexes(String db, String schema) throws IOException {
        lock.readLock().lock();
        try {
            return indexService.listIndexes(db, schema);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Integer> query(String db, String schema, String field,
                               BPlusTree.Op op, Object value, Object high,
                               boolean ascending, int offset, int limit) throws IOException {
        lock.readLock().lock();
        try {
            return indexService.query(db, schema, field, op, value, high, ascending, offset, limit);
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=IndexManagerTest test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/services/index/IndexManager.java Node/src/test/java/com/database/atypon/Node/services/index/IndexManagerTest.java
git commit -m "feat(index): add IndexManager @Service with global read/write locking"
```

---

### Task 2: IndexController — create/list/drop + query endpoints (with broadcast)

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/controllers/index/IndexController.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/model/Node.java` (add `createIndex`/`dropIndex` peer calls)
- Test: `Node/src/test/java/com/database/atypon/Node/controllers/index/IndexControllerTest.java`

**Interfaces:**
- Consumes: `IndexManager`, `AuthenticationService`, `Token`, `Network`, `Node`, `Response`/`ResponseType`, `BPlusTree.Op`.
- Produces endpoints (all return `Vector<Response>` except query which returns a `Response`):
  - `POST /admin/index/create?database=&schema=&field=` (admin token; local create then broadcast if token==ADMIN)
  - `GET  /admin/index/list?database=&schema=` (admin token)
  - `POST /admin/index/drop?database=&schema=&field=` (admin token; local drop then broadcast if token==ADMIN)
  - `POST /user/index/query` (user token) — body `{database,schema,field,op,value,high,order,limit,offset}`, returns a `Response` whose content is the ordered/paginated docId list.
- `Node.createIndex(db,schema,field)` / `Node.dropIndex(db,schema,field)` — `internal`-auth POSTs to the peer's create/drop endpoints, mirroring `Node.createSchema`.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.controllers.index;

import com.database.atypon.Node.services.authentication.AuthenticationService;
import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class IndexControllerTest {

    private IndexController controllerOn(Path root) throws IOException {
        Path schemas = root.resolve("shop").resolve("schemas");
        Files.createDirectories(schemas);
        Files.writeString(schemas.resolve("users.json"),
                new JSONObject().put("info", new JSONObject().put("schemaName", "users"))
                        .put("schema", new JSONObject().put("Age", "Integer")).toString());
        Path recs = root.resolve("shop").resolve("users-records");
        Files.createDirectories(recs);
        Files.writeString(recs.resolve("0.json"), new JSONObject().put("Age", 30).toString());
        Files.writeString(recs.resolve("1.json"), new JSONObject().put("Age", 25).toString());
        return new IndexController(new IndexManager(root), new AuthenticationService());
    }

    @Test
    void createRejectsNonAdmin(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        // "user" token is not admin -> error, no index created
        assertThat(c.createIndex("shop", "users", "Age", "user").get(0).getResponseType())
                .isEqualTo(ResponseType.ERROR);
    }

    @Test
    void createListQueryWithInternalToken(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        // "internal" is admin-authorized but not ADMIN, so no broadcast (Network.nodes untouched)
        assertThat(c.createIndex("shop", "users", "Age", "internal").get(0).getResponseType())
                .isEqualTo(ResponseType.SUCCESS);
        assertThat(c.listIndexes("shop", "users", "internal").get(0).getResponseType())
                .isEqualTo(ResponseType.SUCCESS);

        HashMap<String, Object> q = new HashMap<>();
        q.put("database", "shop"); q.put("schema", "users"); q.put("field", "Age");
        q.put("op", "GTE"); q.put("value", 25); q.put("order", "ASC"); q.put("limit", -1); q.put("offset", 0);
        Response r = c.query(q, "user");
        assertThat(r.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(r.getContent().toString()).contains("1").contains("0"); // docIds for Age>=25
    }

    @Test
    void queryRejectsNonUser(@TempDir Path root) throws Exception {
        IndexController c = controllerOn(root);
        HashMap<String, Object> q = new HashMap<>();
        q.put("database", "shop"); q.put("schema", "users"); q.put("field", "Age"); q.put("op", "EQ"); q.put("value", 30);
        assertThat(c.query(q, "").getResponseType()).isEqualTo(ResponseType.ERROR);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=IndexControllerTest test`
Expected: FAIL — `IndexController` does not exist.

- [ ] **Step 3: Add the peer calls to Node.java, then the controller**

In `Node.java`, add (mirroring the existing `createSchema` peer call — `internal` auth, parse the `Vector<LinkedHashMap>` result):

```java
    public Response createIndex(String database, String schema, String field) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add("authorization", "internal");
        HttpEntity entity = new HttpEntity(headers);
        try {
            Vector<LinkedHashMap<String, String>> response = restTemplate.postForObject(getURL()
                    + "/admin/index/create?database=" + database + "&schema=" + schema + "&field=" + field,
                    entity, Vector.class);
            return new Response(ResponseType.valueOf(response.get(0).get("responseType")), response.get(0).get("message"));
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, this.name + ": " + e.getMessage());
        }
    }

    public Response dropIndex(String database, String schema, String field) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.add("authorization", "internal");
        HttpEntity entity = new HttpEntity(headers);
        try {
            Vector<LinkedHashMap<String, String>> response = restTemplate.postForObject(getURL()
                    + "/admin/index/drop?database=" + database + "&schema=" + schema + "&field=" + field,
                    entity, Vector.class);
            return new Response(ResponseType.valueOf(response.get(0).get("responseType")), response.get(0).get("message"));
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, this.name + ": " + e.getMessage());
        }
    }
```

Create `IndexController.java`:

```java
package com.database.atypon.Node.controllers.index;

import com.database.atypon.Node.index.BPlusTree;
import com.database.atypon.Node.model.Network;
import com.database.atypon.Node.model.Node;
import com.database.atypon.Node.services.authentication.AuthenticationService;
import com.database.atypon.Node.services.index.IndexManager;
import com.database.atypon.Node.utils.Token;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Vector;

@RestController
public class IndexController {

    private final IndexManager indexManager;
    private final AuthenticationService authenticationService;

    public IndexController(IndexManager indexManager, AuthenticationService authenticationService) {
        this.indexManager = indexManager;
        this.authenticationService = authenticationService;
    }

    @PostMapping(value = "/admin/index/create", produces = "application/json")
    public Vector<Response> createIndex(@RequestParam String database, @RequestParam String schema,
                                        @RequestParam String field, @RequestHeader("authorization") String token) {
        if (!authenticationService.isAdminToken(token)) {
            return one(new Response(ResponseType.ERROR, "You are not an admin"));
        }
        Vector<Response> responses = new Vector<>();
        try {
            indexManager.createIndex(database, schema, field);
            responses.add(new Response(ResponseType.SUCCESS, "Index created on " + schema + "." + field));
        } catch (Exception e) {
            responses.add(new Response(ResponseType.ERROR, e.getMessage()));
        }
        if (!token.equals(Token.ADMIN)) {
            return responses;
        }
        for (Node node : Network.nodes) {
            responses.add(node.createIndex(database, schema, field));
        }
        return responses;
    }

    @PostMapping(value = "/admin/index/drop", produces = "application/json")
    public Vector<Response> dropIndex(@RequestParam String database, @RequestParam String schema,
                                      @RequestParam String field, @RequestHeader("authorization") String token) {
        if (!authenticationService.isAdminToken(token)) {
            return one(new Response(ResponseType.ERROR, "You are not an admin"));
        }
        Vector<Response> responses = new Vector<>();
        try {
            indexManager.dropIndex(database, schema, field);
            responses.add(new Response(ResponseType.SUCCESS, "Index dropped on " + schema + "." + field));
        } catch (Exception e) {
            responses.add(new Response(ResponseType.ERROR, e.getMessage()));
        }
        if (!token.equals(Token.ADMIN)) {
            return responses;
        }
        for (Node node : Network.nodes) {
            responses.add(node.dropIndex(database, schema, field));
        }
        return responses;
    }

    @GetMapping(value = "/admin/index/list", produces = "application/json")
    public Vector<Response> listIndexes(@RequestParam String database, @RequestParam String schema,
                                        @RequestHeader("authorization") String token) {
        if (!authenticationService.isAdminToken(token)) {
            return one(new Response(ResponseType.ERROR, "You are not an admin"));
        }
        try {
            List<String> fields = indexManager.listIndexes(database, schema);
            return one(new Response(ResponseType.SUCCESS, "Indexed fields", fields.toString()));
        } catch (Exception e) {
            return one(new Response(ResponseType.ERROR, e.getMessage()));
        }
    }

    @PostMapping(value = "/user/index/query", produces = "application/json")
    public Response query(@RequestBody HashMap<String, Object> body, @RequestHeader("authorization") String token) {
        if (!authenticationService.isUserToken(token)) {
            return new Response(ResponseType.ERROR, "You are not a user");
        }
        try {
            String database = (String) body.get("database");
            String schema = (String) body.get("schema");
            String field = (String) body.get("field");
            BPlusTree.Op op = BPlusTree.Op.valueOf(((String) body.get("op")).toUpperCase());
            Object value = body.get("value");
            Object high = body.get("high");
            boolean ascending = !"DESC".equalsIgnoreCase(String.valueOf(body.getOrDefault("order", "ASC")));
            int limit = body.containsKey("limit") ? ((Number) body.get("limit")).intValue() : -1;
            int offset = body.containsKey("offset") ? ((Number) body.get("offset")).intValue() : 0;
            List<Integer> ids = indexManager.query(database, schema, field, op, value, high, ascending, offset, limit);
            return new Response(ResponseType.SUCCESS, "Query results", ids.toString());
        } catch (Exception e) {
            return new Response(ResponseType.ERROR, e.getMessage());
        }
    }

    private Vector<Response> one(Response r) {
        Vector<Response> v = new Vector<>();
        v.add(r);
        return v;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=IndexControllerTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/controllers/index/IndexController.java Node/src/main/java/com/database/atypon/Node/model/Node.java Node/src/test/java/com/database/atypon/Node/controllers/index/IndexControllerTest.java
git commit -m "feat(index): add IndexController create/list/drop/query endpoints with broadcast"
```

---

### Task 3: Write-hook — maintain indexes on document writes

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java` (inject `IndexManager`; call `onInsert` after a document is written)
- Test: `Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationIndexHookTest.java`

**Interfaces:**
- Consumes: `IndexManager` (constructor-injected into `WriteOperation`).
- Produces: after `WriteOperation.createDocument` writes `<nextId>.json` and bumps `nextId`, it calls `indexManager.onInsert(database, schema, nextId, documentJSON)` inside the same `synchronized` block (exactly once for the new docId).

- [ ] **Step 1: Write the failing test**

`WriteOperation` currently has a no-arg-ish constructor (`@Component`, no deps). After this task it takes an `IndexManager`. This test uses a Mockito spy/mock to assert the hook fires with the right docId, running against a temp working area created under the module's `./data` and cleaned up.

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=WriteOperationIndexHookTest test`
Expected: FAIL — `WriteOperation` has no `IndexManager` constructor / no `onInsert` call.

- [ ] **Step 3: Wire the hook into WriteOperation**

Add a constructor-injected `IndexManager` field to `WriteOperation` and invoke `onInsert` after the document file is written. In `WriteOperation.java`:
- Add field + constructor:
```java
    private final com.database.atypon.Node.services.index.IndexManager indexManager;

    public WriteOperation(com.database.atypon.Node.services.index.IndexManager indexManager) {
        this.indexManager = indexManager;
    }
```
- In `createDocument`, immediately after `fileWriter.write();` (the doc write) and before `return new Response(ResponseType.SUCCESS, ...)`, add:
```java
                try {
                    indexManager.onInsert(database, schema, nextId, documentJSON);
                } catch (Exception indexError) {
                    // indexes are derived/rebuildable; don't fail the write on a maintenance error
                    log.error("index maintenance failed for {}.{} doc {}", database, schema, nextId, indexError);
                }
```
(Add an SLF4J `log` field to `WriteOperation` if not present: `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WriteOperation.class);`. `nextId` and `documentJSON` are already in scope in `createDocument`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=WriteOperationIndexHookTest test`
Expected: PASS (1 test).

- [ ] **Step 5: Run the full suite and commit**

Run: `cd Node && mvn -q test` — expected all green (67 + 2 IndexManager + 3 IndexController + 1 write-hook = 73). If `-q` hides the summary, re-run without `-q`.

```bash
git add Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java Node/src/test/java/com/database/atypon/Node/operations/write/WriteOperationIndexHookTest.java
git commit -m "feat(index): maintain indexes on document write via IndexManager hook"
```

---

## Definition of done (M1·4b)

- `IndexManager` @Service serializes index create/drop/onInsert against query/list with a global ReadWriteLock; a concurrent query during an insert stays consistent.
- `IndexController` exposes admin create/list/drop and a user query endpoint, honoring the existing auth roles and broadcasting create/drop to peers (each node builds its own index from its replica); peers receive `internal` and don't re-broadcast.
- `WriteOperation.createDocument` maintains every index on the schema for the new docId (exactly-once), and a maintenance error is logged without failing the write (indexes are rebuildable).
- Full suite green on JDK 17. The DBMS gateway query UI page is M1·4c.
