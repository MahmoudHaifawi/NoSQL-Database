# M1·4a — IndexService (engine ↔ records integration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the B+-tree engine to the node's on-disk document store: an `IndexService` that creates an index on a `(schema, field)` by sorted bulk-load from existing records, lists/drops indexes, queries with value coercion, and maintains indexes on new inserts.

**Architecture:** The **index files are the registry** — an index on `(schema, field)` exists iff `<dataRoot>/<db>/indexes/<schema>.<field>.idx` exists; its key type is read from the file's META page. No separate catalog to keep in sync (a derived-structure design). `IndexService` takes a configurable `dataRoot` (production `./data`, tests a temp dir), so it is a plain unit-testable POJO here; the Spring `@Service` wrapper, REST endpoints, write-hook, and UI come in M1·4b. Request values are coerced to the index's `KeyType` before encoding.

**Tech Stack:** Java 17, `org.json`, `java.nio.file`, JUnit 5 + AssertJ. No new deps. Builds on the `com.database.atypon.Node.index` engine (M1·1–M1·3).

**Spec:** `docs/superpowers/specs/2026-09-04-capstone-completion-roadmap.md` §5.7/§5.8/§5.9. REST endpoints + write-hook + UI are M1·4b.

## Global Constraints

- Java **17**; Spring Boot **2.7.5**; no new dependencies.
- **Build with JDK 17** (`JAVA_HOME=C:\Program Files\Java\jdk-17`; default JDK 26 breaks Spring Boot 2.7.5). `mvn` 3.9.9 on PATH.
- New class in `Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java`; tests in the matching test dir. Do NOT modify the empty `services/read/IndexingService.java` (removed/superseded in M1·4b) or any existing file.
- **Index file layout:** `<dataRoot>/<db>/indexes/<schema>.<field>.idx`. Assumes schema and field names contain no `.` (they are simple identifiers in this project).
- **Value coercion:** request values (JSON number/string/boolean) are coerced to the index's `KeyType` before `KeyCodec.encode`.
- Use the **no-arg `BPlusTree.bulkLoad`/`open`** (default `maxKeys`) so create and reopen agree on the branch factor.
- On-disk record layout (written by the existing `WriteOperation`): records at `<dataRoot>/<db>/<schema>-records/<id>.json` (one JSON object per file, filename is the integer docId); schema at `<dataRoot>/<db>/schemas/<schema>.json` = `{"info":{...}, "schema":{field:type}}`.
- Append this trailer to every commit message: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- End each task with `cd Node && mvn -q test` under JDK 17 (full suite stays green; M1·3 leaves 57 tests).

---

### Task 1: IndexService — createIndex / list / drop

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java`
- Test: `Node/src/test/java/com/database/atypon/Node/services/index/IndexServiceTest.java`

**Interfaces:**
- Consumes: `com.database.atypon.Node.index.{BPlusTree, Pager, Page, KeyType, KeyCodec}`; `org.json.JSONObject`; `java.nio.file.*`.
- Produces: `IndexService(java.nio.file.Path dataRoot)`; `boolean indexExists(db,schema,field)`; `void createIndex(db,schema,field)` (bulk-load from records); `List<String> listIndexes(db,schema)`; `void dropIndex(db,schema,field)`. All throw `IOException`.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=IndexServiceTest test`
Expected: FAIL — `IndexService` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import com.database.atypon.Node.index.KeyCodec;
import com.database.atypon.Node.index.KeyType;
import com.database.atypon.Node.index.Pager;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages per-(schema, field) B+-tree indexes for a node's document store.
 * The index files ARE the registry: an index exists iff its .idx file exists, and its
 * key type is read from the file's META page. {@code dataRoot} is the node's data directory
 * (production {@code ./data}); a configurable root keeps this class unit-testable.
 */
public class IndexService {

    private static final String IDX_SUFFIX = ".idx";
    private final Path dataRoot;

    public IndexService(Path dataRoot) {
        this.dataRoot = dataRoot;
    }

    private Path indexDir(String db) {
        return dataRoot.resolve(db).resolve("indexes");
    }

    private Path indexFile(String db, String schema, String field) {
        return indexDir(db).resolve(schema + "." + field + IDX_SUFFIX);
    }

    private Path schemaFile(String db, String schema) {
        return dataRoot.resolve(db).resolve("schemas").resolve(schema + ".json");
    }

    private Path recordsDir(String db, String schema) {
        return dataRoot.resolve(db).resolve(schema + "-records");
    }

    public boolean indexExists(String db, String schema, String field) {
        return Files.exists(indexFile(db, schema, field));
    }

    private KeyType fieldKeyType(String db, String schema, String field) throws IOException {
        JSONObject schemaJson = new JSONObject(Files.readString(schemaFile(db, schema))).getJSONObject("schema");
        if (!schemaJson.has(field)) {
            throw new IllegalArgumentException("field '" + field + "' is not in schema '" + schema + "'");
        }
        return KeyType.fromSchemaType(schemaJson.getString(field));
    }

    public void createIndex(String db, String schema, String field) throws IOException {
        if (indexExists(db, schema, field)) {
            throw new IllegalStateException("index already exists on " + schema + "." + field);
        }
        KeyType keyType = fieldKeyType(db, schema, field);
        List<byte[]> keys = new ArrayList<>();
        Path recs = recordsDir(db, schema);
        if (Files.isDirectory(recs)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(recs, "*.json")) {
                for (Path rec : stream) {
                    String name = rec.getFileName().toString();
                    int docId = Integer.parseInt(name.substring(0, name.length() - ".json".length()));
                    JSONObject doc = new JSONObject(Files.readString(rec));
                    if (doc.has(field)) {
                        keys.add(KeyCodec.encode(keyType, doc.get(field), docId));
                    }
                }
            }
        }
        keys.sort((a, b) -> KeyCodec.compare(keyType, a, b));
        Files.createDirectories(indexDir(db));
        try (Pager pager = new Pager(indexFile(db, schema, field).toFile())) {
            BPlusTree.bulkLoad(pager, keyType, keys);
        }
    }

    public List<String> listIndexes(String db, String schema) throws IOException {
        List<String> fields = new ArrayList<>();
        Path dir = indexDir(db);
        if (!Files.isDirectory(dir)) {
            return fields;
        }
        String prefix = schema + ".";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*" + IDX_SUFFIX)) {
            for (Path p : stream) {
                String n = p.getFileName().toString();
                fields.add(n.substring(prefix.length(), n.length() - IDX_SUFFIX.length()));
            }
        }
        return fields;
    }

    public void dropIndex(String db, String schema, String field) throws IOException {
        Files.deleteIfExists(indexFile(db, schema, field));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=IndexServiceTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java Node/src/test/java/com/database/atypon/Node/services/index/IndexServiceTest.java
git commit -m "feat(index): add IndexService createIndex/list/drop over the record store"
```

---

### Task 2: IndexService — query (with value coercion) + onInsert maintenance

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java` (add `query`, `onInsert`, `coerce`, `indexKeyType`)
- Test: `Node/src/test/java/com/database/atypon/Node/services/index/IndexServiceQueryTest.java`

**Interfaces:**
- Consumes: Task 1's IndexService + `BPlusTree.open`, `BPlusTree.Op`, `queryDocIds`, `Page.metaKeyType`, `Pager.META_PAGE_ID`.
- Produces: `List<Integer> query(db, schema, field, BPlusTree.Op op, Object value, Object high, boolean ascending, int offset, int limit)` — docIds ordered + paginated, value(s) coerced to the index key type; `void onInsert(db, schema, int docId, JSONObject doc)` — maintain every index on that schema.

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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=IndexServiceQueryTest test`
Expected: FAIL — `query`/`onInsert` do not exist.

- [ ] **Step 3: Add query + onInsert to IndexService**

Add these methods to `IndexService` (and the imports `com.database.atypon.Node.index.Page` and `com.database.atypon.Node.index.KeyType` are already present):

```java
    /** Query an index; docIds ordered (ASC/DESC over the field) then offset/limit paginated. */
    public List<Integer> query(String db, String schema, String field,
                               BPlusTree.Op op, Object value, Object high,
                               boolean ascending, int offset, int limit) throws IOException {
        if (!indexExists(db, schema, field)) {
            throw new IllegalStateException("no index on " + schema + "." + field);
        }
        try (Pager pager = new Pager(indexFile(db, schema, field).toFile())) {
            BPlusTree tree = BPlusTree.open(pager);
            KeyType keyType = indexKeyType(pager);
            Object v = coerce(keyType, value);
            Object h = (op == BPlusTree.Op.BETWEEN) ? coerce(keyType, high) : null;
            return tree.queryDocIds(op, v, h, ascending, offset, limit);
        }
    }

    /** Maintain every index defined on this schema after a document is written. */
    public void onInsert(String db, String schema, int docId, JSONObject doc) throws IOException {
        for (String field : listIndexes(db, schema)) {
            if (!doc.has(field)) {
                continue;
            }
            try (Pager pager = new Pager(indexFile(db, schema, field).toFile())) {
                BPlusTree tree = BPlusTree.open(pager);
                KeyType keyType = indexKeyType(pager);
                tree.insert(KeyCodec.encode(keyType, doc.get(field), docId));
                tree.flush();
            }
        }
    }

    private KeyType indexKeyType(Pager pager) throws IOException {
        com.database.atypon.Node.index.Page meta = pager.get(Pager.META_PAGE_ID);
        if (!meta.hasValidMagic()) {
            throw new IllegalStateException("corrupt or empty index file");
        }
        return meta.metaKeyType();
    }

    /** Coerce a raw request value (JSON number/String/Boolean) to the index's key type. */
    private Object coerce(KeyType keyType, Object raw) {
        switch (keyType) {
            case INTEGER: return (raw instanceof Number) ? ((Number) raw).intValue() : Integer.parseInt(raw.toString());
            case DOUBLE:  return (raw instanceof Number) ? ((Number) raw).doubleValue() : Double.parseDouble(raw.toString());
            case BOOLEAN: return (raw instanceof Boolean) ? raw : Boolean.parseBoolean(raw.toString());
            case STRING:  return raw.toString();
            default: throw new IllegalStateException("unknown key type: " + keyType);
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=IndexServiceQueryTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full suite and commit**

Run: `cd Node && mvn -q test` — expected all green (57 + 8 new IndexService tests). If `-q` hides the summary, re-run without `-q` and confirm `BUILD SUCCESS`.

```bash
git add Node/src/main/java/com/database/atypon/Node/services/index/IndexService.java Node/src/test/java/com/database/atypon/Node/services/index/IndexServiceQueryTest.java
git commit -m "feat(index): add IndexService query (value coercion) + onInsert maintenance"
```

---

## Definition of done (M1·4a)

- `IndexService` creates an index on `(schema, field)` by scanning records and sorted bulk-load; the index file at `<db>/indexes/<schema>.<field>.idx` is the registry.
- `listIndexes`/`dropIndex`/`indexExists` work off the filesystem; duplicate/unknown-field creation is rejected.
- `query` returns correct docIds for all operators, ORDER BY, and pagination, coercing request values (incl. String → number) to the index key type.
- `onInsert` maintains every index on a schema when a new document is written; a subsequent query reflects it.
- Full suite green on JDK 17. REST endpoints, the `WriteOperation` write-hook, and the query UI page are M1·4b.
