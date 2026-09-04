# M0 — Foundations & Refactor Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the `Node` module up to a senior Java baseline — CI + tests, real logging, no swallowed exceptions or `null` returns, extracted constants, and fixes for the outright concurrency bugs — behind a characterization-test safety net, before any feature work.

**Architecture:** Establish CI first so every later change is test-protected. Add characterization tests around pure logic (PathBuilder, Validators) so refactors are provably behavior-preserving. Then fix the real bugs: the fire-and-forget broadcast (returns before tasks finish + leaks its executor) via a small tested `Broadcaster` utility, and `ReadService`'s shared-singleton thread state + `null` error return. Finish with dead-code and DI hygiene (the `Cache` @Component/singleton conflict). Feature-owned rewrites (Cache internals → M1, auth → M4, broadcast semantics → M7) get only a minimal correct fix here.

**Tech Stack:** Java 17, Spring Boot 2.7.5, JUnit 5 + AssertJ + Mockito (via `spring-boot-starter-test`), `org.json:json`, SLF4J/Logback (bundled with Spring Boot), Maven (module wrappers `mvnw`; CI uses the runner's preinstalled Maven), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-04-capstone-completion-roadmap.md` (see §3 "M0 — Foundations & refactor baseline" and §4 boy-scout standard).

## Global Constraints

- Java **17**; Spring Boot **2.7.5** (do not bump versions in M0).
- **No new runtime dependencies.** SLF4J/Logback ship with Spring Boot; tests use the existing `spring-boot-starter-test`.
- Scope is the **`Node` module only**, plus repo-wide CI and `.gitignore`. `DBMS` and `BootstrappingNode` get their own foundations pass when M4/M5 work in them.
- **Behavior-preserving:** every refactor must keep the characterization tests (Task 2) green. The only intentional behavior changes are the two bug fixes (Tasks 5 and 6), each covered by a new test.
- Package root: `com.database.atypon.Node`. Follow existing constructor-injection style.
- Do **not** untrack `Node/data/` — it holds seed data (the predetermined admin in `info.json`).
- Every task ends by running the module test suite: `cd Node && ./mvnw -q test` (or `mvn -q test`). On Windows PowerShell use `.\mvnw.cmd -q test`; the `./mvnw` form assumes Git Bash.
- **Error-model scope:** M0 fixes the concrete offenders only — `null` returns (Task 6) and swallowed/printed exceptions (Tasks 4–5). A unified `@RestControllerAdvice` global handler with HTTP status codes is **deferred to controller rework (M2/M4)**, because introducing status codes now would change the always-200 API contract and break M0's behavior-preserving guarantee.

---

### Task 1: CI pipeline + .gitignore hygiene + green baseline

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `.gitignore` (repo root)
- Test: n/a (deliverable is a passing build/CI)

**Interfaces:**
- Consumes: nothing.
- Produces: a CI workflow that runs `mvn -B -ntp verify` per module on every push/PR; a baseline confirmation that `Node` builds and its existing `contextLoads` test passes.

- [ ] **Step 1: Confirm the baseline builds and tests pass**

Run: `cd Node && ./mvnw -q test`
Expected: BUILD SUCCESS, `NodeApplicationTests.contextLoads` passes. If the context fails to load because a bean eagerly reads `./data`, stop and report — that is a real finding to fix before proceeding.

- [ ] **Step 2: Add the CI workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI
on:
  push:
    branches: ["**"]
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        module: [Node, BootstrappingNode, DBMS]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - name: Build & test ${{ matrix.module }}
        working-directory: ${{ matrix.module }}
        run: mvn -B -ntp verify
```

- [ ] **Step 3: Add ignore rules and untrack the IDE folder**

Ensure these lines exist in the root `.gitignore` (append any that are missing; do not remove existing lines):

```gitignore
# IDE
.idea/
*.iml
# Build output
target/
**/target/
```

Then untrack the committed IDE folder (leave `Node/data/` tracked):

Run: `git rm -r --cached .idea` and `git rm --cached NoSQL-Databaseb.iml`
Expected: files removed from the index only (still present on disk).

- [ ] **Step 4: Verify all three modules build locally**

Run: `cd Node && mvn -q -ntp test` then repeat in `BootstrappingNode` and `DBMS`.
Expected: BUILD SUCCESS in each. (If `BootstrappingNode`/`DBMS` fail for pre-existing reasons, note it; M0's contract is that `Node` is green and CI is wired — record the others as findings.)

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml .gitignore
git rm -r --cached .idea NoSQL-Databaseb.iml
git commit -m "ci: add GitHub Actions build/test workflow and ignore IDE/build artifacts"
```

---

### Task 2: Characterization tests for PathBuilder and Validators

**Files:**
- Test: `Node/src/test/java/com/database/atypon/Node/utils/PathBuilderTest.java`
- Test: `Node/src/test/java/com/database/atypon/Node/utils/ValidatorsTest.java`

**Interfaces:**
- Consumes: `PathBuilder` static methods; `Validators.validateSchema(JSONObject, String)`.
- Produces: a green safety net that later tasks (constants extraction, logging) must not break.

These tests document **current** behavior, so they pass against today's code. That is intentional — they are the refactor safety net.

- [ ] **Step 1: Write the PathBuilder characterization test**

```java
package com.database.atypon.Node.utils;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PathBuilderTest {

    @Test
    void buildsDocumentPath() {
        assertThat(PathBuilder.getPathToDocument("shop", "users", "3"))
                .isEqualTo("./data/shop/users-records/3.json");
    }

    @Test
    void buildsSchemaPath() {
        assertThat(PathBuilder.getPathToSchema("shop", "users"))
                .isEqualTo("./data/shop/schemas/users.json");
    }

    @Test
    void buildsAffinityAndRecordsPaths() {
        assertThat(PathBuilder.getPathToAffinity("shop", "users"))
                .isEqualTo("./data/shop/affinities/users.json");
        assertThat(PathBuilder.getPathToAllDocuments("shop", "users"))
                .isEqualTo("./data/shop/users-records/");
    }

    @Test
    void buildsInfoAndRootPaths() {
        assertThat(PathBuilder.getInfoPath()).isEqualTo("./data/info.json");
        assertThat(PathBuilder.getRootPath()).isEqualTo("./data/");
    }
}
```

- [ ] **Step 2: Write the Validators characterization test**

```java
package com.database.atypon.Node.utils;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorsTest {

    @Test
    void acceptsSupportedTypes() throws Exception {
        JSONObject schema = new JSONObject()
                .put("Name", "String").put("Age", "Integer")
                .put("Active", "Boolean").put("Gpa", "Double");
        assertThat(Validators.validateSchema(schema, "users")).isTrue();
    }

    @Test
    void rejectsUnsupportedType() {
        JSONObject schema = new JSONObject().put("When", "Date");
        assertThatThrownBy(() -> Validators.validateSchema(schema, "events"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Invalid type");
    }

    @Test
    void rejectsEmptySchema() {
        assertThatThrownBy(() -> Validators.validateSchema(new JSONObject(), "x"))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("empty");
    }
}
```

- [ ] **Step 3: Run the tests to verify they pass against current code**

Run: `cd Node && ./mvnw -q test`
Expected: PASS (both new test classes green; they characterize existing behavior).

- [ ] **Step 4: Commit**

```bash
git add Node/src/test/java/com/database/atypon/Node/utils/PathBuilderTest.java Node/src/test/java/com/database/atypon/Node/utils/ValidatorsTest.java
git commit -m "test: add characterization tests for PathBuilder and Validators"
```

---

### Task 3: Extract magic-string constants

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/utils/JsonKeys.java`
- Modify: `Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java` (uses `"info"`, `"nextId"`, `"schema"`, `"schemaName"`)
- Modify: `Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java` (uses `"schemaName"`, `"schema"`, `"Node"`)

**Interfaces:**
- Consumes: nothing new.
- Produces: `JsonKeys` with `public static final String INFO, NEXT_ID, SCHEMA, SCHEMA_NAME, NODE;` — later milestones reuse these.

Behavior-preserving refactor; Task 2 tests must stay green.

- [ ] **Step 1: Create the constants holder**

```java
package com.database.atypon.Node.utils;

/** JSON field keys used across schema, info, and affinity documents. */
public final class JsonKeys {
    private JsonKeys() {}

    public static final String INFO = "info";
    public static final String NEXT_ID = "nextId";
    public static final String SCHEMA = "schema";
    public static final String SCHEMA_NAME = "schemaName";
    public static final String NODE = "Node";
}
```

- [ ] **Step 2: Replace the literals in WriteOperation and WriteService**

In `WriteOperation.java`, replace `"info"` → `JsonKeys.INFO`, `"nextId"` → `JsonKeys.NEXT_ID`, `"schemaName"` → `JsonKeys.SCHEMA_NAME`, `"schema"` → `JsonKeys.SCHEMA` (add `import com.database.atypon.Node.utils.JsonKeys;`). Also fix the typo `pahToSchema` → `pathToSchema`.
In `WriteService.java`, replace `"schemaName"` → `JsonKeys.SCHEMA_NAME`, `"schema"` → `JsonKeys.SCHEMA`, and the affinity `"Node"` key → `JsonKeys.NODE`.

- [ ] **Step 3: Run tests to verify behavior is unchanged**

Run: `cd Node && ./mvnw -q test`
Expected: PASS (PathBuilderTest + ValidatorsTest still green; compilation clean).

- [ ] **Step 4: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/utils/JsonKeys.java Node/src/main/java/com/database/atypon/Node/operations/write/WriteOperation.java Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java
git commit -m "refactor: extract JSON field-name constants and fix pathToSchema typo"
```

---

### Task 4: Replace System.out / printStackTrace with SLF4J logging

**Files:**
- Modify: `Node/.../services/read/ReadService.java` (lines ~59, 61, 67, 97; remove commented block ~72-74)
- Modify: `Node/.../controllers/write/WriteController.java` (line 68 println, line 91 printStackTrace)
- Modify: `Node/.../controllers/NetworkController.java` (line 28 print)
- Modify: `Node/.../services/write/WriteService.java` (line 92 printStackTrace)
- Test: reuse existing suite (mechanical change; verified by a static check + green tests)

**Interfaces:**
- Consumes: `org.slf4j.Logger`, `org.slf4j.LoggerFactory` (bundled).
- Produces: no `System.out.print*` or `printStackTrace()` left in `Node/src/main`.

- [ ] **Step 1: Add a logger to each class and replace the calls**

In each modified class add a field:

```java
private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(<ClassName>.class);
```

Then replace:
- `System.out.println(x)` → `log.debug(...)` (e.g. `log.debug("Reading file {}", file.getName());`).
- `System.out.print(node.getName() + "  ")` in `NetworkController` → `log.debug("peer {}", node.getName());`.
- `e.printStackTrace()` → `log.error("<context message>", e);` (e.g. in `WriteService`: `log.error("Broadcast task failed", e);`).
- Delete the commented-out `try/catch` block in `ReadService` (the `//` lines around 72-74).

- [ ] **Step 2: Static check — no console logging remains**

Run: `grep -rnE "System\.out\.print|printStackTrace" Node/src/main/java`
Expected: no matches.

- [ ] **Step 3: Run tests**

Run: `cd Node && ./mvnw -q test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node
git commit -m "refactor: replace System.out/printStackTrace with SLF4J logging"
```

---

### Task 5: Fix the broadcast bug with a tested Broadcaster utility

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/utils/concurrent/Broadcaster.java`
- Test: `Node/src/test/java/com/database/atypon/Node/utils/concurrent/BroadcasterTest.java`
- Modify: `Node/.../services/write/WriteService.java` (`broadcastSchema`, `broadcastDocument`)
- Modify: `Node/.../services/admin/AdminService.java` (`broadcastDatabase`)

**Interfaces:**
- Consumes: `Response`, `ResponseType`.
- Produces: `Broadcaster.broadcast(List<Supplier<Response>> tasks) -> List<Response>` — runs every task on a pool, **blocks until all finish**, shuts the pool down, and maps any thrown exception to a `Response(ResponseType.ERROR, ...)`. Returns one response per task.

The current bug: `WriteService`/`AdminService` submit tasks with `executor.execute(...)` then `return res` immediately, so callers get an empty/partial list and the executor is never shut down.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.utils.concurrent;

import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcasterTest {

    @Test
    void waitsForEveryTaskAndReturnsAllResults() {
        List<Supplier<Response>> tasks = List.of(
                slowOk("a"), slowOk("b"), slowOk("c"));

        List<Response> results = Broadcaster.broadcast(tasks);

        assertThat(results).hasSize(3);
        assertThat(results).allMatch(r -> r.getResponseType() == ResponseType.SUCCESS);
    }

    @Test
    void mapsThrownExceptionToErrorResponse() {
        List<Supplier<Response>> tasks = List.of(
                slowOk("a"),
                () -> { throw new RuntimeException("boom"); });

        List<Response> results = Broadcaster.broadcast(tasks);

        assertThat(results).hasSize(2);
        assertThat(results).anyMatch(r -> r.getResponseType() == ResponseType.ERROR);
    }

    private static Supplier<Response> slowOk(String msg) {
        return () -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            return new Response(ResponseType.SUCCESS, msg);
        };
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Node && ./mvnw -q -Dtest=BroadcasterTest test`
Expected: FAIL — `Broadcaster` does not exist (compilation error).

- [ ] **Step 3: Implement Broadcaster**

```java
package com.database.atypon.Node.utils.concurrent;

import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Runs a set of tasks in parallel, waits for all of them, and returns one result each. */
public final class Broadcaster {
    private Broadcaster() {}

    private static final int MAX_THREADS = 4;

    public static List<Response> broadcast(List<Supplier<Response>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return new ArrayList<>();
        }
        int poolSize = Math.min(MAX_THREADS, tasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Callable<Response>> callables = new ArrayList<>(tasks.size());
            for (Supplier<Response> task : tasks) {
                callables.add(task::get);
            }
            List<Future<Response>> futures = executor.invokeAll(callables); // blocks until all done
            List<Response> results = new ArrayList<>(futures.size());
            for (Future<Response> future : futures) {
                results.add(resultOf(future));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of(new Response(ResponseType.ERROR, "Broadcast interrupted"));
        } finally {
            executor.shutdown();
        }
    }

    private static Response resultOf(Future<Response> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            return new Response(ResponseType.ERROR, "Broadcast task failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(ResponseType.ERROR, "Broadcast task interrupted");
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Node && ./mvnw -q -Dtest=BroadcasterTest test`
Expected: PASS (both tests).

- [ ] **Step 5: Wire Broadcaster into WriteService**

Replace the bodies of `broadcastSchema` and `broadcastDocument` so they build one `Supplier<Response>` per peer and return `Broadcaster.broadcast(...)`. Example for `broadcastDocument`:

```java
public List<Response> broadcastDocument(String database, String schema, HashMap<String, Object> document) {
    List<Supplier<Response>> tasks = new ArrayList<>();
    for (Node node : Network.nodes) {
        tasks.add(() -> node.createDocument(database, schema, document));
    }
    return Broadcaster.broadcast(tasks);
}
```

Apply the same shape to `broadcastSchema` (calling `node.createSchema(database, schema)`). Remove the old `ThreadPoolExecutor` imports/fields. Add `import java.util.function.Supplier;`, `import java.util.ArrayList;`, and `import com.database.atypon.Node.utils.concurrent.Broadcaster;`.

- [ ] **Step 6: Wire Broadcaster into AdminService.broadcastDatabase**

```java
public List<Response> broadcastDatabase(String databaseName) {
    List<Supplier<Response>> tasks = new ArrayList<>();
    for (Node node : Network.nodes) {
        tasks.add(() -> node.addDatabase(databaseName));
    }
    return Broadcaster.broadcast(tasks);
}
```

Remove the `ThreadPoolExecutor`/`LinkedBlockingQueue`/`TimeUnit` imports. Leave `broadcastUser` as-is (it is already synchronous).

- [ ] **Step 7: Run the full suite**

Run: `cd Node && ./mvnw -q test`
Expected: PASS (all tests; compilation clean).

- [ ] **Step 8: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/utils/concurrent/Broadcaster.java Node/src/test/java/com/database/atypon/Node/utils/concurrent/BroadcasterTest.java Node/src/main/java/com/database/atypon/Node/services/write/WriteService.java Node/src/main/java/com/database/atypon/Node/services/admin/AdminService.java
git commit -m "fix: broadcast now awaits all replicas and no longer leaks its executor"
```

---

### Task 6: Fix ReadService — no null returns, no shared thread state

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/services/read/ReadService.java`
- Test: `Node/src/test/java/com/database/atypon/Node/services/read/ReadServiceTest.java`

**Interfaces:**
- Consumes: `Cache` (constructor arg, unchanged), `FileReader`, `Response`, `ResponseType`.
- Produces: `ReadService.readDocumentsInDirectory(File folder) -> Response` (package-private, testable): reads every `*.json` file in `folder` in parallel via a **local** executor, returns `Response(SUCCESS, json)` on success and `Response(ERROR, msg)` (never `null`) on failure. Removes the `threads` instance field and the "Threads are busy" rejection.

The current bugs: `fetchAll` returns `null` on error; the reader threads live in a shared instance field on a singleton, so concurrent reads clobber each other and are rejected with "Threads are busy".

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.services.read;

import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ReadServiceTest {

    // Cache is not exercised by the read paths under test (document caching lands in M1),
    // so a null cache keeps this a focused unit test, independent of the Cache task's ordering.
    private final ReadService service = new ReadService(null);

    @Test
    void readsAllDocumentsInADirectory(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("0.json"), new JSONObject().put("Name", "A").toString());
        Files.writeString(dir.resolve("1.json"), new JSONObject().put("Name", "B").toString());

        Response response = service.readDocumentsInDirectory(dir.toFile());

        assertThat(response.getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(response.getContent().toString()).contains("0.json").contains("1.json");
    }

    @Test
    void concurrentReadsBothSucceed(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("0.json"), new JSONObject().put("Name", "A").toString());
        File folder = dir.toFile();

        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> service.readDocumentsInDirectory(folder));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> service.readDocumentsInDirectory(folder));

        assertThat(a.get().getResponseType()).isEqualTo(ResponseType.SUCCESS);
        assertThat(b.get().getResponseType()).isEqualTo(ResponseType.SUCCESS);
    }

    @Test
    void missingDirectoryReturnsErrorNotNull() {
        Response response = service.readDocumentsInDirectory(new File("does-not-exist-xyz"));
        assertThat(response).isNotNull();
        assertThat(response.getResponseType()).isEqualTo(ResponseType.ERROR);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Node && ./mvnw -q -Dtest=ReadServiceTest test`
Expected: FAIL — `readDocumentsInDirectory` does not exist (and the current code path returns `null` / rejects concurrency).

- [ ] **Step 3: Rewrite the read internals**

Replace the shared-state read logic in `ReadService`. Remove the `threads` instance field, `isThreadsBusy`, `startReadingThreads`, `joinReadingThreads`, and `initializeReadingThreads`. Add a package-private `readDocumentsInDirectory(File folder)` that uses a **local** fixed pool, and have `fetchAll` delegate to it:

```java
Response fetchAll(String databaseName, String schemaName) {
    String documentsPath = PathBuilder.getPathToAllDocuments(databaseName, schemaName);
    return readDocumentsInDirectory(new File(documentsPath));
}

Response readDocumentsInDirectory(File folder) {
    File[] files = folder.listFiles(File::isFile);
    if (files == null) {
        return new Response(ResponseType.ERROR, "Documents directory not found");
    }
    JSONObject result = new JSONObject();
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(NUMBER_OF_THREADS, Math.max(1, files.length)));
    try {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (File file : files) {
            tasks.add(() -> {
                FileReader reader = new FileReader(file);
                reader.read();
                synchronized (result) {
                    result.put(file.getName(), reader.getContent());
                }
                return null;
            });
        }
        executor.invokeAll(tasks); // blocks until all reads complete
        return new Response(ResponseType.SUCCESS, "Documents fetched successfully", result.toString());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Response(ResponseType.ERROR, "Read interrupted");
    } finally {
        executor.shutdown();
    }
}
```

Keep `NUMBER_OF_THREADS = 4`. Update imports (`java.util.concurrent.*`, `java.util.ArrayList`, `java.util.List`, `java.util.concurrent.Callable`). Ensure `fetchById` still returns an ERROR `Response` (it already does) — never `null`. Make `fetchAll` and `readDocumentsInDirectory` package-private so the test in the same package can call them.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Node && ./mvnw -q -Dtest=ReadServiceTest test`
Expected: PASS (all three tests).

- [ ] **Step 5: Run the full suite**

Run: `cd Node && ./mvnw -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/services/read/ReadService.java Node/src/test/java/com/database/atypon/Node/services/read/ReadServiceTest.java
git commit -m "fix: ReadService uses a local executor and returns errors instead of null"
```

---

### Task 7: Dead-code removal + Cache DI hygiene

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/controllers/read/ReadController.java` (remove unused `responses` field + unused `Queue` import)
- Modify: `Node/src/main/java/com/database/atypon/Node/utils/cache/Cache.java` (remove the hand-rolled singleton; keep it a plain Spring component)
- Test: `Node/src/test/java/com/database/atypon/Node/utils/cache/CacheTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Cache` as a normal Spring-managed bean (no `getInstance()`, no `volatile instance`), constructor public/package. Method bodies stay as the documented stubs (real LRU is M1). `ReadController` no longer holds request state in a field.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.utils.cache;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheTest {

    @Test
    void isConstructableWithoutStaticSingleton() throws Exception {
        Cache cache = new Cache();
        assertThat(cache).isNotNull();
        // the hand-rolled singleton accessor must be gone
        assertThatThrownBy(() -> Cache.class.getDeclaredMethod("getInstance"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void addRejectsNulls() {
        Cache cache = new Cache();
        assertThatThrownBy(() -> cache.add(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Node && ./mvnw -q -Dtest=CacheTest test`
Expected: FAIL — `new Cache()` is private and `getInstance` still exists.

- [ ] **Step 3: Simplify Cache to a plain Spring component**

Edit `Cache.java`: delete `private static volatile Cache instance;` and the `getInstance()` method; change the constructor from `private Cache()` to `public Cache()`. Keep the fields and the stub method bodies unchanged (LRU lands in M1). The class stays annotated `@Component implements CacheInterface`.

- [ ] **Step 4: Remove the dead field in ReadController**

Delete the `private Queue<Response> responses;` field and its unused `import java.util.Queue;` from `ReadController`. The constructor and endpoints are unchanged.

- [ ] **Step 5: Run the tests**

Run: `cd Node && ./mvnw -q test`
Expected: PASS (CacheTest green; full suite green; app still compiles with `Cache` injected as a bean).

- [ ] **Step 6: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/utils/cache/Cache.java Node/src/main/java/com/database/atypon/Node/controllers/read/ReadController.java Node/src/test/java/com/database/atypon/Node/utils/cache/CacheTest.java
git commit -m "refactor: make Cache a plain Spring bean and drop dead ReadController field"
```

---

## Definition of done (M0)

- CI workflow runs `mvn verify` per module on push/PR; `Node` is green.
- `.idea/` and build output are gitignored and untracked.
- No `System.out.print*` or `printStackTrace()` in `Node/src/main`.
- Broadcasts wait for all replicas and never leak an executor (`WriteService`, `AdminService`).
- `ReadService` has no shared thread state and never returns `null`.
- `Cache` is a plain Spring bean (no hand-rolled singleton); dead `ReadController` field gone; `pahToSchema` typo fixed; magic JSON keys are constants.
- Characterization tests (PathBuilder, Validators) plus new unit tests (Broadcaster, ReadService, Cache) all pass.
