# M1·3 — Range Queries, ORDER BY / Pagination, Sorted Bulk-Load Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the query surface to `BPlusTree` — ascending range scans over the leaf chain, operator queries (EQ / GT / GTE / LT / LTE / BETWEEN) with ORDER BY (ASC/DESC) + offset/limit pagination — and a sorted bottom-up bulk-load constructor.

**Architecture:** Range scans locate the start leaf (`findLeaf` for a lower bound, else `leftmostLeaf`) and walk `rightSibling` collecting composite keys until the upper bound is passed — the linked-leaf traversal the M1·2 review verified. Operators translate to a `[loKey, hiKey]` composite range using `Integer.MIN_VALUE`/`MAX_VALUE` as docId sentinels (so "value = v" spans all docIds of v). ORDER BY DESC reverses the ascending collection; pagination slices it. Bulk-load packs pre-sorted keys into leaves bottom-up, linking siblings, then builds internal levels until one root.

**Tech Stack:** Java 17, JUnit 5 + AssertJ. No new deps. Builds on M1·1/M1·2 `com.database.atypon.Node.index`.

**Spec:** `docs/superpowers/specs/2026-09-04-capstone-completion-roadmap.md` §5 (§5.7 sorted bulk-load, §5.9 query surface). `IndexService` + endpoints + write-hook + UI are the NEXT sub-plan (M1·4).

## Global Constraints

- Java **17**; Spring Boot **2.7.5**; no new dependencies.
- **Build with JDK 17** (`JAVA_HOME=C:\Program Files\Java\jdk-17`; default JDK 26 breaks Spring Boot 2.7.5). `mvn` 3.9.9 on PATH.
- All code in `Node/src/main/java/com/database/atypon/Node/index/`; tests in the matching test dir.
- **Composite key order is (value asc, docId asc)** and comparison is signed on docId — so `Integer.MIN_VALUE`/`MAX_VALUE` are valid low/high docId sentinels (real docIds are >= 0).
- Reuse the existing private `findLeaf(byte[])`, `leftmostLeaf()`, `keySize`, `keyType`, `pager`, `rootId()`, `validateMaxKeys(...)`, `defaultMaxKeys(...)` in `BPlusTree` — do not duplicate them.
- Append this trailer to every commit message: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- End each task with `cd Node && mvn -q test` under JDK 17 (full suite stays green; M1·2 leaves 46 tests).

---

### Task 1: Range scan + operator query + ORDER BY / pagination

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java` (add `Op` enum + `scanRange` / `query` / `queryDocIds`)
- Test: `Node/src/test/java/com/database/atypon/Node/index/BPlusTreeQueryTest.java`

**Interfaces:**
- Consumes: existing `BPlusTree` internals (`findLeaf`, `leftmostLeaf`, `keySize`, `keyType`, `pager`), `KeyCodec.encode/compare/decodeDocId`, `Page`.
- Produces:
  - `enum BPlusTree.Op { EQ, GT, GTE, LT, LTE, BETWEEN }`
  - `List<byte[]> scanRange(byte[] loKey, boolean loInclusive, byte[] hiKey, boolean hiInclusive)` — composite keys in ascending order; `null` bound = unbounded.
  - `List<byte[]> query(Op op, Object value, Object high)` — `high` used only for `BETWEEN`.
  - `List<Integer> queryDocIds(Op op, Object value, Object high, boolean ascending, int offset, int limit)` — docIds after ordering + `offset`/`limit` (`limit < 0` = unlimited).

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BPlusTreeQueryTest {

    /** Insert values 0..n-1 with docId == value, so decoded docIds equal the values. */
    private BPlusTree treeOfValues(Pager pager, int n) throws Exception {
        BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 6);
        for (int v = 0; v < n; v++) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, v));
        return tree;
    }

    private static List<Integer> range(int lo, int hi) {
        List<Integer> out = new ArrayList<>();
        for (int v = lo; v <= hi; v++) out.add(v);
        return out;
    }

    @Test
    void betweenReturnsInclusiveAscending(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("q.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 500);
            List<Integer> ids = tree.queryDocIds(BPlusTree.Op.BETWEEN, 100, 200, true, 0, -1);
            assertThat(ids).isEqualTo(range(100, 200));
        }
    }

    @Test
    void comparisonOperators(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("c.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 50);
            assertThat(tree.queryDocIds(BPlusTree.Op.GT, 47, null, true, 0, -1)).isEqualTo(range(48, 49));
            assertThat(tree.queryDocIds(BPlusTree.Op.GTE, 47, null, true, 0, -1)).isEqualTo(range(47, 49));
            assertThat(tree.queryDocIds(BPlusTree.Op.LT, 3, null, true, 0, -1)).isEqualTo(range(0, 2));
            assertThat(tree.queryDocIds(BPlusTree.Op.LTE, 3, null, true, 0, -1)).isEqualTo(range(0, 3));
        }
    }

    @Test
    void equalityReturnsAllDocIdsOfAValue(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("eq.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int docId = 1; docId <= 10; docId++) tree.insert(KeyCodec.encode(KeyType.INTEGER, 5, docId));
            tree.insert(KeyCodec.encode(KeyType.INTEGER, 4, 0));
            tree.insert(KeyCodec.encode(KeyType.INTEGER, 6, 0));
            assertThat(tree.queryDocIds(BPlusTree.Op.EQ, 5, null, true, 0, -1)).isEqualTo(range(1, 10));
        }
    }

    @Test
    void descendingAndPagination(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("p.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 100);
            // BETWEEN 10..19 has 10 values; DESC = 19,18,...,10
            assertThat(tree.queryDocIds(BPlusTree.Op.BETWEEN, 10, 19, false, 0, -1))
                    .isEqualTo(List.of(19, 18, 17, 16, 15, 14, 13, 12, 11, 10));
            // ASC, offset 3, limit 4 -> 13,14,15,16
            assertThat(tree.queryDocIds(BPlusTree.Op.BETWEEN, 10, 19, true, 3, 4))
                    .isEqualTo(List.of(13, 14, 15, 16));
        }
    }

    @Test
    void emptyAndUnboundedRanges(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("u.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 30);
            assertThat(tree.queryDocIds(BPlusTree.Op.GT, 100, null, true, 0, -1)).isEmpty();
            assertThat(tree.queryDocIds(BPlusTree.Op.LTE, 4, null, true, 0, -1)).isEqualTo(range(0, 4));
            assertThat(tree.scanRange(null, true, null, true)).hasSize(30); // full scan
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=BPlusTreeQueryTest test`
Expected: FAIL — `Op`/`scanRange`/`query`/`queryDocIds` do not exist.

- [ ] **Step 3: Add the query methods to BPlusTree**

Add to `BPlusTree` (alongside the existing methods; `Op` as a public nested enum):

```java
    public enum Op { EQ, GT, GTE, LT, LTE, BETWEEN }

    private static final int MIN_DOC = Integer.MIN_VALUE;
    private static final int MAX_DOC = Integer.MAX_VALUE;

    /** Composite keys within [loKey, hiKey] (honoring inclusivity), ascending. Null bound = unbounded. */
    public List<byte[]> scanRange(byte[] loKey, boolean loInclusive, byte[] hiKey, boolean hiInclusive) throws IOException {
        List<byte[]> out = new ArrayList<>();
        int leafId = (loKey == null) ? leftmostLeaf() : findLeaf(loKey);
        while (leafId != 0) {
            Page leaf = pager.get(leafId);
            int n = leaf.numKeys();
            for (int i = 0; i < n; i++) {
                byte[] k = leaf.leafKey(i, keySize);
                if (loKey != null) {
                    int c = KeyCodec.compare(keyType, k, loKey);
                    if (c < 0 || (c == 0 && !loInclusive)) continue;
                }
                if (hiKey != null) {
                    int c = KeyCodec.compare(keyType, k, hiKey);
                    if (c > 0 || (c == 0 && !hiInclusive)) return out; // ascending: nothing more matches
                }
                out.add(k);
            }
            leafId = leaf.rightSibling();
        }
        return out;
    }

    /** Translate an operator query to a composite range and return the matching keys ascending. */
    public List<byte[]> query(Op op, Object value, Object high) throws IOException {
        switch (op) {
            case EQ:
                return scanRange(key(value, MIN_DOC), true, key(value, MAX_DOC), true);
            case GT:
                return scanRange(key(value, MAX_DOC), false, null, true);
            case GTE:
                return scanRange(key(value, MIN_DOC), true, null, true);
            case LT:
                return scanRange(null, true, key(value, MIN_DOC), false);
            case LTE:
                return scanRange(null, true, key(value, MAX_DOC), true);
            case BETWEEN:
                return scanRange(key(value, MIN_DOC), true, key(high, MAX_DOC), true);
            default:
                throw new IllegalArgumentException("unknown op: " + op);
        }
    }

    /** DocIds for a query, ordered ASC/DESC over the field, then offset/limit paginated (limit < 0 = unlimited). */
    public List<Integer> queryDocIds(Op op, Object value, Object high, boolean ascending, int offset, int limit) throws IOException {
        List<byte[]> keys = query(op, value, high);
        if (!ascending) java.util.Collections.reverse(keys);
        List<Integer> ids = new ArrayList<>();
        for (int i = Math.max(0, offset); i < keys.size(); i++) {
            if (limit >= 0 && ids.size() >= limit) break;
            ids.add(KeyCodec.decodeDocId(keyType, keys.get(i)));
        }
        return ids;
    }

    private byte[] key(Object value, int docId) {
        return KeyCodec.encode(keyType, value, docId);
    }
```

Ensure `java.util.List`/`java.util.ArrayList` are imported (they already are for the insert helpers).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=BPlusTreeQueryTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java Node/src/test/java/com/database/atypon/Node/index/BPlusTreeQueryTest.java
git commit -m "feat(index): add range scan + operator queries with ORDER BY/pagination"
```

---

### Task 2: Sorted bulk-load

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java` (add static `bulkLoad`)
- Test: `Node/src/test/java/com/database/atypon/Node/index/BPlusTreeBulkLoadTest.java`

**Interfaces:**
- Consumes: `Pager`, `Page`, `KeyType`, `KeyCodec`, existing `validateMaxKeys`, `defaultMaxKeys`, the private `BPlusTree` constructor.
- Produces: `static BPlusTree bulkLoad(Pager pager, KeyType keyType, List<byte[]> sortedKeys, int maxKeys)` — builds a valid B+-tree bottom-up from PRE-SORTED, unique composite keys into an empty pager (META page 0, then packed leaves, then internal levels). A convenience `bulkLoad(Pager, KeyType, List<byte[]>)` uses `defaultMaxKeys`.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BPlusTreeBulkLoadTest {

    private static List<byte[]> sortedIntKeys(int n) {
        List<byte[]> keys = new ArrayList<>(n);
        for (int v = 0; v < n; v++) keys.add(KeyCodec.encode(KeyType.INTEGER, v, v)); // already ascending
        return keys;
    }

    @Test
    void bulkLoadBuildsValidTreeWithAllKeys(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("bl.idx").toFile())) {
            BPlusTree tree = BPlusTree.bulkLoad(pager, KeyType.INTEGER, sortedIntKeys(1000), 6);
            assertThatCode(tree::validate).doesNotThrowAnyException();
            for (int v = 0; v < 1000; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, v))).as("v=" + v).isTrue();
            }
            assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 1000, 1000))).isFalse();
        }
    }

    @Test
    void bulkLoadRangeQueriesMatch(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("blq.idx").toFile())) {
            BPlusTree tree = BPlusTree.bulkLoad(pager, KeyType.INTEGER, sortedIntKeys(300), 5);
            List<Integer> expected = new ArrayList<>();
            for (int v = 50; v <= 120; v++) expected.add(v);
            assertThat(tree.queryDocIds(BPlusTree.Op.BETWEEN, 50, 120, true, 0, -1)).isEqualTo(expected);
        }
    }

    @Test
    void bulkLoadEmpty(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("ble.idx").toFile())) {
            BPlusTree tree = BPlusTree.bulkLoad(pager, KeyType.INTEGER, new ArrayList<>(), 4);
            tree.validate();
            assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 1, 1))).isFalse();
        }
    }

    @Test
    void bulkLoadPersistsAcrossReopen(@TempDir Path dir) throws Exception {
        File f = dir.resolve("blp.idx").toFile();
        try (Pager pager = new Pager(f)) {
            BPlusTree.bulkLoad(pager, KeyType.INTEGER, sortedIntKeys(200), 4);
        }
        try (Pager pager = new Pager(f)) {
            BPlusTree tree = BPlusTree.open(pager, 4);
            tree.validate();
            for (int v = 0; v < 200; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, v))).isTrue();
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=BPlusTreeBulkLoadTest test`
Expected: FAIL — `bulkLoad` does not exist.

- [ ] **Step 3: Add `bulkLoad` to BPlusTree**

```java
    public static BPlusTree bulkLoad(Pager pager, KeyType keyType, List<byte[]> sortedKeys) throws IOException {
        return bulkLoad(pager, keyType, sortedKeys, defaultMaxKeys(keyType));
    }

    /** Build a B+-tree bottom-up from pre-sorted, unique composite keys into an empty pager. */
    public static BPlusTree bulkLoad(Pager pager, KeyType keyType, List<byte[]> sortedKeys, int maxKeys) throws IOException {
        validateMaxKeys(keyType, maxKeys);
        int keySize = KeyCodec.keySize(keyType);
        int metaId = pager.allocate(); // 0

        if (sortedKeys.isEmpty()) {
            int rootId = pager.allocate();
            Page root = pager.get(rootId);
            root.initLeaf();
            pager.markDirty(rootId);
            writeMeta(pager, metaId, keyType, rootId);
            pager.flushAll();
            return new BPlusTree(pager, keyType, maxKeys);
        }

        // Level 0: pack leaves, linking siblings.
        List<byte[]> firstKeys = new ArrayList<>();
        List<Integer> pageIds = new ArrayList<>();
        int prevLeafId = 0;
        for (int i = 0; i < sortedKeys.size(); i += maxKeys) {
            int end = Math.min(i + maxKeys, sortedKeys.size());
            int leafId = pager.allocate();
            Page leaf = pager.get(leafId);
            leaf.initLeaf();
            for (int j = i; j < end; j++) leaf.setLeafKey(j - i, sortedKeys.get(j));
            leaf.setNumKeys(end - i);
            pager.markDirty(leafId);
            if (prevLeafId != 0) {
                Page prev = pager.get(prevLeafId);
                prev.setRightSibling(leafId);
                pager.markDirty(prevLeafId);
            }
            prevLeafId = leafId;
            firstKeys.add(sortedKeys.get(i).clone());
            pageIds.add(leafId);
        }

        // Build internal levels until a single root remains.
        while (pageIds.size() > 1) {
            List<byte[]> parentFirstKeys = new ArrayList<>();
            List<Integer> parentPageIds = new ArrayList<>();
            int count = pageIds.size();
            for (int i = 0; i < count; i += (maxKeys + 1)) {
                int end = Math.min(i + (maxKeys + 1), count);
                int nodeId = pager.allocate();
                Page node = pager.get(nodeId);
                node.initInternal();
                node.setChild(0, pageIds.get(i), keySize);
                int seps = 0;
                for (int j = i + 1; j < end; j++) {
                    node.setInternalKey(seps, firstKeys.get(j), keySize);
                    node.setChild(seps + 1, pageIds.get(j), keySize);
                    seps++;
                }
                node.setNumKeys(seps);
                pager.markDirty(nodeId);
                parentFirstKeys.add(firstKeys.get(i).clone());
                parentPageIds.add(nodeId);
            }
            firstKeys = parentFirstKeys;
            pageIds = parentPageIds;
        }

        writeMeta(pager, metaId, keyType, pageIds.get(0));
        pager.flushAll();
        return new BPlusTree(pager, keyType, maxKeys);
    }

    private static void writeMeta(Pager pager, int metaId, KeyType keyType, int rootId) throws IOException {
        Page meta = pager.get(metaId);
        meta.initMeta(keyType, rootId, pager.pageCountOnDisk());
        pager.markDirty(metaId);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=BPlusTreeBulkLoadTest test`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full suite and commit**

Run: `cd Node && mvn -q test` — expected all green (46 from M1·2 + 9 new: 5 query + 4 bulk-load). If `-q` hides the summary, re-run without `-q` and confirm `BUILD SUCCESS`.

```bash
git add Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java Node/src/test/java/com/database/atypon/Node/index/BPlusTreeBulkLoadTest.java
git commit -m "feat(index): add sorted bottom-up bulk-load"
```

---

## Definition of done (M1·3)

- `scanRange` walks the leaf chain and returns composite keys ascending within a `[lo, hi]` range; `query` maps EQ/GT/GTE/LT/LTE/BETWEEN to composite ranges via docId sentinels; `queryDocIds` applies ORDER BY (ASC/DESC) + offset/limit.
- Operator results verified against expected value ranges, including all-docIds-of-a-value (EQ), DESC, and pagination.
- `bulkLoad` builds a `validate()`-passing tree from 1000 pre-sorted keys; all keys found; range queries match; persists across reopen; empty input yields an empty root leaf.
- Full suite green on JDK 17. `IndexService` + endpoints + write-hook + query UI remain for M1·4.
