# M1·2 — B+-tree Core (search / insert / split) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `BPlusTree` on top of the M1·1 `Pager`/`Page`/`KeyCodec`: create/open a tree, point `contains(key)` search, and `insert(key)` with full leaf + internal node splitting and root growth — plus a structural `validate()` invariant checker.

**Architecture:** A single `BPlusTree` class over a `Pager`. The tree's key width (`keySize`) is derived ONCE from the META page's key type and threaded into every `Page` accessor (node pages do not self-describe their width — this is a hard rule from the M1·1 review). Insert descends recursively; a full node splits and returns a `(separatorKey, newRightPageId)` pair to its parent (copy-up for leaves, push-up for internal nodes); when the root splits, a new internal root is created and META's root pointer is updated. A test-only branch factor makes multi-level splits cheap to exercise.

**Tech Stack:** Java 17, JUnit 5 + AssertJ (existing `spring-boot-starter-test`). No new dependencies. Builds on M1·1 `com.database.atypon.Node.index` classes.

**Spec:** `docs/superpowers/specs/2026-09-04-capstone-completion-roadmap.md` §5 (esp. §5.2 composite key, §5.3 page/leaf-link layout). Range/ORDER-BY queries and sorted bulk-load are the NEXT sub-plan (M1·3) — NOT in scope here. Delete is out of scope for M1.

## Global Constraints

- Java **17**; Spring Boot **2.7.5**; no new dependencies.
- **Build with JDK 17:** default `JAVA_HOME` is JDK 26 (breaks Spring Boot 2.7.5). PowerShell `$env:JAVA_HOME="C:\Program Files\Java\jdk-17"`; Git Bash `export JAVA_HOME="/c/Program Files/Java/jdk-17"`. `mvn` 3.9.9 on PATH.
- All code in `Node/src/main/java/com/database/atypon/Node/index/`; tests in `Node/src/test/java/com/database/atypon/Node/index/`.
- **Thread the single META keySize everywhere.** `BPlusTree` computes `keySize = KeyCodec.keySize(keyType)` once (keyType from the META page) and passes it to every `Page` accessor. NEVER recompute a per-node width from `valueWidth`.
- **Composite keys are unique** (value ‖ docId), so inserts never collide; no duplicate-key merge logic is needed.
- **`insert` marks pages dirty promptly** (Pager pages are not pinned) but does NOT flush; the caller flushes via `BPlusTree.flush()` or `Pager.close()`.
- Append this trailer to every commit message: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- End each task by running `cd Node && mvn -q test` under JDK 17 (full suite stays green; M1·1 leaves 34 tests).

---

### Task 1: BPlusTree — create / open / contains / insert with splitting

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java`
- Test: `Node/src/test/java/com/database/atypon/Node/index/BPlusTreeTest.java`

**Interfaces:**
- Consumes: `Pager` (`allocate`, `get`, `markDirty`, `flushAll`, `pageCountOnDisk`, `META_PAGE_ID`), `Page`, `KeyType`, `KeyCodec` (from M1·1).
- Produces:
  - `static BPlusTree create(Pager, KeyType)` and `create(Pager, KeyType, int maxKeys)` — initialize META (page 0) + an empty root leaf (page 1) in an empty pager.
  - `static BPlusTree open(Pager)` and `open(Pager, int maxKeys)` — attach to an existing index file (validates META magic).
  - `boolean contains(byte[] compositeKey)`; `void insert(byte[] compositeKey)`; `void flush()`.
  - `maxKeys` is the split threshold (a node holding more than `maxKeys` keys splits). Default = `min(Page.maxLeafKeys(keySize), Page.maxInternalKeys(keySize))`; the `int maxKeys` overload lets tests force small nodes (>= 3).

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BPlusTreeTest {

    private static byte[] intKey(int v) {
        return KeyCodec.encode(KeyType.INTEGER, v, 0);
    }

    @Test
    void emptyTreeContainsNothing(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("a.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            assertThat(tree.contains(intKey(5))).isFalse();
        }
    }

    @Test
    void insertAndContainsWithoutSplit(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("b.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            tree.insert(intKey(10));
            tree.insert(intKey(5));
            tree.insert(intKey(20));
            assertThat(tree.contains(intKey(5))).isTrue();
            assertThat(tree.contains(intKey(10))).isTrue();
            assertThat(tree.contains(intKey(20))).isTrue();
            assertThat(tree.contains(intKey(99))).isFalse();
        }
    }

    @Test
    void leafSplitGrowsRoot(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("c.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 5; v++) tree.insert(intKey(v)); // 5 > maxKeys(4) forces a split
            assertThat(pager.get(Pager.META_PAGE_ID).metaRoot()).isNotEqualTo(1); // root moved off the first leaf
            assertThat(pager.get(pager.get(Pager.META_PAGE_ID).metaRoot()).isLeaf()).isFalse(); // root is now internal
            for (int v = 1; v <= 5; v++) assertThat(tree.contains(intKey(v))).isTrue();
        }
    }

    @Test
    void manyInsertsForceMultipleLevels(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("d.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 60; v++) tree.insert(intKey(v));
            for (int v = 1; v <= 60; v++) assertThat(tree.contains(intKey(v))).as("v=" + v).isTrue();
            assertThat(tree.contains(intKey(0))).isFalse();
            assertThat(tree.contains(intKey(61))).isFalse();
        }
    }

    @Test
    void shuffledInsertsAllFound(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("e.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            List<Integer> vs = new ArrayList<>();
            for (int v = 1; v <= 40; v++) vs.add(v);
            Collections.shuffle(vs, new java.util.Random(42));
            for (int v : vs) tree.insert(intKey(v));
            for (int v = 1; v <= 40; v++) assertThat(tree.contains(intKey(v))).as("v=" + v).isTrue();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=BPlusTreeTest test`
Expected: FAIL — `BPlusTree` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.database.atypon.Node.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A disk-paged B+-tree over a {@link Pager}. Stores composite keys (value ‖ docId,
 * from {@link KeyCodec}); every key is unique so inserts never collide. The key width
 * is derived once from the META page's key type and threaded into every Page accessor —
 * node pages do not self-describe their width.
 *
 * <p>{@code insert} marks touched pages dirty but does not flush; call {@link #flush()}
 * (or close the Pager) to persist. Pages are not pinned, so each mutated page is marked
 * dirty immediately.
 */
public class BPlusTree {

    private final Pager pager;
    private final KeyType keyType;
    private final int keySize;
    private final int maxKeys;

    /** Result of a node split bubbling up to the parent: separator key + new right sibling page id. */
    private static final class Split {
        final byte[] key;
        final int rightPageId;
        Split(byte[] key, int rightPageId) { this.key = key; this.rightPageId = rightPageId; }
    }

    private BPlusTree(Pager pager, KeyType keyType, int maxKeys) {
        if (maxKeys < 3) throw new IllegalArgumentException("maxKeys must be >= 3");
        this.pager = pager;
        this.keyType = keyType;
        this.keySize = KeyCodec.keySize(keyType);
        this.maxKeys = maxKeys;
    }

    public static int defaultMaxKeys(KeyType keyType) {
        int ks = KeyCodec.keySize(keyType);
        return Math.min(Page.maxLeafKeys(ks), Page.maxInternalKeys(ks));
    }

    public static BPlusTree create(Pager pager, KeyType keyType) throws IOException {
        return create(pager, keyType, defaultMaxKeys(keyType));
    }

    public static BPlusTree create(Pager pager, KeyType keyType, int maxKeys) throws IOException {
        int metaId = pager.allocate();  // 0
        int rootId = pager.allocate();  // 1
        Page root = pager.get(rootId);
        root.initLeaf();
        pager.markDirty(rootId);
        Page meta = pager.get(metaId);
        meta.initMeta(keyType, rootId, pager.pageCountOnDisk());
        pager.markDirty(metaId);
        pager.flushAll();
        return new BPlusTree(pager, keyType, maxKeys);
    }

    public static BPlusTree open(Pager pager) throws IOException {
        KeyType kt = requireValidMeta(pager).metaKeyType();
        return new BPlusTree(pager, kt, defaultMaxKeys(kt));
    }

    public static BPlusTree open(Pager pager, int maxKeys) throws IOException {
        return new BPlusTree(pager, requireValidMeta(pager).metaKeyType(), maxKeys);
    }

    private static Page requireValidMeta(Pager pager) throws IOException {
        Page meta = pager.get(Pager.META_PAGE_ID);
        if (!meta.hasValidMagic()) {
            throw new IllegalStateException("not a valid index file (bad META magic)");
        }
        return meta;
    }

    public void flush() throws IOException { pager.flushAll(); }

    private int rootId() throws IOException {
        return pager.get(Pager.META_PAGE_ID).metaRoot();
    }

    // ---- search ----

    public boolean contains(byte[] key) throws IOException {
        Page leaf = pager.get(findLeaf(key));
        return leafIndexOf(leaf, key) >= 0;
    }

    private int findLeaf(byte[] key) throws IOException {
        int pid = rootId();
        Page p = pager.get(pid);
        while (!p.isLeaf()) {
            pid = p.child(routeChild(p, key), keySize);
            p = pager.get(pid);
        }
        return pid;
    }

    /** Child index to descend into: the number of separators <= key. */
    private int routeChild(Page internal, byte[] key) {
        int n = internal.numKeys();
        int i = 0;
        while (i < n && KeyCodec.compare(keyType, key, internal.internalKey(i, keySize)) >= 0) {
            i++;
        }
        return i;
    }

    private int leafIndexOf(Page leaf, byte[] key) {
        int n = leaf.numKeys();
        for (int i = 0; i < n; i++) {
            if (KeyCodec.compare(keyType, key, leaf.leafKey(i, keySize)) == 0) return i;
        }
        return -1;
    }

    // ---- insert ----

    public void insert(byte[] key) throws IOException {
        int rid = rootId();
        Split split = insertInto(rid, key);
        if (split != null) {
            int newRootId = pager.allocate();
            Page newRoot = pager.get(newRootId);
            newRoot.initInternal();
            newRoot.setChild(0, rid, keySize);
            newRoot.setInternalKey(0, split.key, keySize);
            newRoot.setChild(1, split.rightPageId, keySize);
            newRoot.setNumKeys(1);
            pager.markDirty(newRootId);
            Page meta = pager.get(Pager.META_PAGE_ID);
            meta.setMetaRoot(newRootId);
            meta.setMetaPageCount(pager.pageCountOnDisk());
            pager.markDirty(Pager.META_PAGE_ID);
        }
    }

    private Split insertInto(int pageId, byte[] key) throws IOException {
        Page p = pager.get(pageId);
        if (p.isLeaf()) {
            return insertIntoLeaf(pageId, p, key);
        }
        int ci = routeChild(p, key);
        int childId = p.child(ci, keySize);
        Split childSplit = insertInto(childId, key);
        if (childSplit == null) return null;
        return insertKeyChildIntoInternal(pageId, ci, childSplit.key, childSplit.rightPageId);
    }

    private Split insertIntoLeaf(int pageId, Page leaf, byte[] key) throws IOException {
        List<byte[]> keys = readLeafKeys(leaf);
        keys.add(insertionPos(keys, key), key);
        if (keys.size() <= maxKeys) {
            writeLeafKeys(leaf, keys);
            pager.markDirty(pageId);
            return null;
        }
        int total = keys.size();
        int leftCount = (total + 1) / 2;
        List<byte[]> leftKeys = new ArrayList<>(keys.subList(0, leftCount));
        List<byte[]> rightKeys = new ArrayList<>(keys.subList(leftCount, total));
        int rightId = pager.allocate();
        Page right = pager.get(rightId);
        right.initLeaf();
        right.setRightSibling(leaf.rightSibling());
        leaf.setRightSibling(rightId);
        writeLeafKeys(leaf, leftKeys);
        writeLeafKeys(right, rightKeys);
        pager.markDirty(pageId);
        pager.markDirty(rightId);
        return new Split(rightKeys.get(0).clone(), rightId); // copy-up first key of right
    }

    private Split insertKeyChildIntoInternal(int pageId, int keyPos, byte[] sepKey, int newChildId) throws IOException {
        Page node = pager.get(pageId);
        List<byte[]> keys = readInternalKeys(node);
        List<Integer> children = readInternalChildren(node);
        keys.add(keyPos, sepKey);
        children.add(keyPos + 1, newChildId);
        if (keys.size() <= maxKeys) {
            writeInternal(node, keys, children);
            pager.markDirty(pageId);
            return null;
        }
        int total = keys.size();
        int mid = total / 2;                       // middle separator moves UP
        byte[] upKey = keys.get(mid).clone();
        List<byte[]> leftKeys = new ArrayList<>(keys.subList(0, mid));
        List<byte[]> rightKeys = new ArrayList<>(keys.subList(mid + 1, total));
        List<Integer> leftChildren = new ArrayList<>(children.subList(0, mid + 1));
        List<Integer> rightChildren = new ArrayList<>(children.subList(mid + 1, children.size()));
        int rightId = pager.allocate();
        Page right = pager.get(rightId);
        right.initInternal();
        writeInternal(node, leftKeys, leftChildren);
        writeInternal(right, rightKeys, rightChildren);
        pager.markDirty(pageId);
        pager.markDirty(rightId);
        return new Split(upKey, rightId);
    }

    private int insertionPos(List<byte[]> keys, byte[] key) {
        int i = 0;
        while (i < keys.size() && KeyCodec.compare(keyType, key, keys.get(i)) >= 0) i++;
        return i;
    }

    // ---- page <-> list helpers ----

    private List<byte[]> readLeafKeys(Page leaf) {
        int n = leaf.numKeys();
        List<byte[]> ks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ks.add(leaf.leafKey(i, keySize));
        return ks;
    }

    private void writeLeafKeys(Page leaf, List<byte[]> keys) {
        for (int i = 0; i < keys.size(); i++) leaf.setLeafKey(i, keys.get(i));
        leaf.setNumKeys(keys.size());
    }

    private List<byte[]> readInternalKeys(Page node) {
        int n = node.numKeys();
        List<byte[]> ks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ks.add(node.internalKey(i, keySize));
        return ks;
    }

    private List<Integer> readInternalChildren(Page node) {
        int n = node.numKeys();
        List<Integer> cs = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) cs.add(node.child(i, keySize));
        return cs;
    }

    private void writeInternal(Page node, List<byte[]> keys, List<Integer> children) {
        node.setChild(0, children.get(0), keySize);
        for (int i = 0; i < keys.size(); i++) {
            node.setInternalKey(i, keys.get(i), keySize);
            node.setChild(i + 1, children.get(i + 1), keySize);
        }
        node.setNumKeys(keys.size());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=BPlusTreeTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java Node/src/test/java/com/database/atypon/Node/index/BPlusTreeTest.java
git commit -m "feat(index): add BPlusTree with search and insert (leaf/internal splits, root grow)"
```

---

### Task 2: validate() invariants + oracle / string-key / persistence tests

**Files:**
- Modify: `Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java` (add `validate()`)
- Test: `Node/src/test/java/com/database/atypon/Node/index/BPlusTreeValidateTest.java`

**Interfaces:**
- Consumes: everything from Task 1.
- Produces: `void validate()` — throws `IllegalStateException` if any B+-tree invariant is violated: keys strictly ascending within every node; every key inside its subtree's `[lo, hi)` separator bounds; all leaves at the same depth; an internal node has `numKeys + 1` children.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BPlusTreeValidateTest {

    @Test
    void invariantsHoldAfterManyInserts(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("v.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 100; v++) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, 0));
            assertThatCode(tree::validate).doesNotThrowAnyException();
        }
    }

    @Test
    void oracleMembershipOverThousandShuffledKeys(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("o.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 6);
            List<Integer> vs = new ArrayList<>();
            for (int v = 0; v < 1000; v++) vs.add(v);
            Collections.shuffle(vs, new Random(7));
            for (int v : vs) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, 0));
            tree.validate();
            for (int v = 0; v < 1000; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, 0))).as("present v=" + v).isTrue();
            }
            for (int v = 1000; v < 1020; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, 0))).as("absent v=" + v).isFalse();
            }
        }
    }

    @Test
    void duplicateValuesDistinctDocIdsAllStored(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("dup.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int docId = 1; docId <= 20; docId++) tree.insert(KeyCodec.encode(KeyType.INTEGER, 5, docId));
            tree.validate();
            for (int docId = 1; docId <= 20; docId++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 5, docId))).isTrue();
            }
            assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 5, 99))).isFalse();
        }
    }

    @Test
    void stringKeysRoundTripThroughSplits(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("s.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.STRING, 4);
            String[] words = {"apple","banana","cherry","date","fig","grape","kiwi","lemon",
                    "mango","nectar","orange","pear","quince","raspberry","straw","tangerine",
                    "ugli","vanilla","watermelon","xigua","yam","zucchini"};
            for (String w : words) tree.insert(KeyCodec.encode(KeyType.STRING, w, 0));
            tree.validate();
            for (String w : words) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.STRING, w, 0))).as(w).isTrue();
            }
            assertThat(tree.contains(KeyCodec.encode(KeyType.STRING, "missing", 0))).isFalse();
        }
    }

    @Test
    void persistsAcrossReopen(@TempDir Path dir) throws Exception {
        File f = dir.resolve("p.idx").toFile();
        try (Pager pager = new Pager(f)) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 30; v++) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, 0));
            tree.flush();
        }
        try (Pager pager = new Pager(f)) {
            BPlusTree tree = BPlusTree.open(pager, 4);
            tree.validate();
            for (int v = 1; v <= 30; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, 0))).as("v=" + v).isTrue();
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=BPlusTreeValidateTest test`
Expected: FAIL — `validate()` does not exist.

- [ ] **Step 3: Add `validate()` to BPlusTree**

Add these methods to `BPlusTree` (alongside the existing ones):

```java
    /** Verify B+-tree structural invariants; throws IllegalStateException on any violation. */
    public void validate() throws IOException {
        List<Integer> leafDepths = new ArrayList<>();
        validateNode(rootId(), 0, null, null, leafDepths);
        for (int d : leafDepths) {
            if (d != leafDepths.get(0)) {
                throw new IllegalStateException("leaves at differing depths: " + leafDepths);
            }
        }
    }

    private void validateNode(int pageId, int depth, byte[] lo, byte[] hi, List<Integer> leafDepths) throws IOException {
        Page p = pager.get(pageId);
        int n = p.numKeys();
        for (int i = 0; i < n; i++) {
            byte[] k = keyAt(p, i);
            if (i > 0 && KeyCodec.compare(keyType, keyAt(p, i - 1), k) >= 0) {
                throw new IllegalStateException("keys not strictly ascending in page " + pageId);
            }
            if (lo != null && KeyCodec.compare(keyType, k, lo) < 0) {
                throw new IllegalStateException("key below lower separator bound in page " + pageId);
            }
            if (hi != null && KeyCodec.compare(keyType, k, hi) >= 0) {
                throw new IllegalStateException("key at/above upper separator bound in page " + pageId);
            }
        }
        if (p.isLeaf()) {
            leafDepths.add(depth);
            return;
        }
        for (int i = 0; i <= n; i++) {
            byte[] childLo = (i == 0) ? lo : p.internalKey(i - 1, keySize);
            byte[] childHi = (i == n) ? hi : p.internalKey(i, keySize);
            validateNode(p.child(i, keySize), depth + 1, childLo, childHi, leafDepths);
        }
    }

    private byte[] keyAt(Page p, int i) {
        return p.isLeaf() ? p.leafKey(i, keySize) : p.internalKey(i, keySize);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=BPlusTreeValidateTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full suite and commit**

Run: `cd Node && mvn -q test` — expected all green (34 from M1·1 + 10 new BPlusTree tests). If `-q` hides the summary, re-run without `-q` and confirm `BUILD SUCCESS`.

```bash
git add Node/src/main/java/com/database/atypon/Node/index/BPlusTree.java Node/src/test/java/com/database/atypon/Node/index/BPlusTreeValidateTest.java
git commit -m "feat(index): add BPlusTree.validate() invariant checker + oracle/string/persistence tests"
```

---

## Definition of done (M1·2)

- `BPlusTree` creates/opens a tree, does point `contains`, and `insert` with full leaf + internal splits and root growth — keySize threaded from META throughout.
- Composite keys with equal values but different docIds all coexist (non-unique index works).
- `validate()` confirms structural invariants (ascending keys, separator bounds, uniform leaf depth) after bulk inserts.
- Oracle test (1000 shuffled keys) and STRING-key-through-splits test pass; the tree persists across pager reopen.
- Full suite green on JDK 17. Range/ORDER-BY queries and sorted bulk-load remain for M1·3.
