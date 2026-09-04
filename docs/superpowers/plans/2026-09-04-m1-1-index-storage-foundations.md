# M1·1 — B+-tree Storage Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure, unit-testable storage primitives the disk-paged B+-tree index needs — an LRU buffer-pool map, a composite-key codec, a fixed 4 KB page format, and a `Pager` that reads/writes pages through the pool with dirty write-back.

**Architecture:** A new self-contained package `com.database.atypon.Node.index` with four classes and no Spring or HTTP dependencies. `KeyCodec` encodes `(fieldValue, docId)` into fixed-width bytes and compares by decoding per type. `Page` is a 4 KB byte buffer with typed accessors for META/INTERNAL/LEAF layouts. `LruCache<K,V>` is a capacity-bounded LRU with an eviction callback. `Pager` layers these: it maps page ids to 4 KB file offsets, caches pages in an `LruCache<Integer,Page>`, and flushes dirty pages on eviction and on `flushAll()`. Later sub-plans build `BPlusTree` and `IndexService` on top.

**Tech Stack:** Java 17, JUnit 5 + AssertJ (via existing `spring-boot-starter-test`), `java.nio.ByteBuffer`, `java.io.RandomAccessFile`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-04-capstone-completion-roadmap.md` §5 (M1 — B+-tree indexing), especially §5.2 data model, §5.3 page format, §5.4 buffer pool.

## Global Constraints

- Java **17**; Spring Boot **2.7.5**. Do not bump versions or add dependencies.
- **Build with JDK 17:** the machine default `JAVA_HOME` is JDK 26, which breaks Spring Boot 2.7.5 tests. Every Maven run: PowerShell `$env:JAVA_HOME="C:\Program Files\Java\jdk-17"`; Git Bash `export JAVA_HOME="/c/Program Files/Java/jdk-17"`. `mvn` (3.9.9) is on PATH.
- All new code lives in `Node/src/main/java/com/database/atypon/Node/index/`; tests in `Node/src/test/java/com/database/atypon/Node/index/`.
- **Page size is 4096 bytes; page ids and doc ids are 4-byte ints.** Composite key = `fieldValue ‖ docId(4B)`; value widths: Integer 4, Double 8, Boolean 1, String capped 64.
- Comparison decodes the value per type (NOT raw memcmp) then tie-breaks by docId.
- Append this trailer to every commit message:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- End each task by running `cd Node && mvn -q test` under JDK 17 (full suite stays green; M0 has 16 tests).

---

### Task 1: LruCache<K,V>

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/index/LruCache.java`
- Test: `Node/src/test/java/com/database/atypon/Node/index/LruCacheTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `LruCache<K,V>(int capacity, java.util.function.BiConsumer<K,V> onEvict)` with `V get(K)`, `void put(K,V)`, `V remove(K)`, `boolean containsKey(K)`, `int size()`, `void clear()`. `onEvict` fires only for capacity-driven LRU eviction (not `remove`/`clear`).

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LruCacheTest {

    @Test
    void evictsLeastRecentlyUsedAndFiresCallback() {
        List<String> evicted = new ArrayList<>();
        LruCache<Integer, String> cache = new LruCache<>(2, (k, v) -> evicted.add(v));
        cache.put(1, "a");
        cache.put(2, "b");
        cache.get(1);       // touch 1 -> 2 is now least-recently-used
        cache.put(3, "c");  // evicts 2 ("b")
        assertThat(evicted).containsExactly("b");
        assertThat(cache.get(2)).isNull();
        assertThat(cache.get(1)).isEqualTo("a");
        assertThat(cache.get(3)).isEqualTo("c");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void removeAndClearDoNotFireEviction() {
        List<String> evicted = new ArrayList<>();
        LruCache<Integer, String> cache = new LruCache<>(2, (k, v) -> evicted.add(v));
        cache.put(1, "a");
        assertThat(cache.remove(1)).isEqualTo("a");
        cache.put(2, "b");
        cache.clear();
        assertThat(evicted).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new LruCache<Integer, String>(0, (k, v) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=LruCacheTest test`
Expected: FAIL — `LruCache` does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.database.atypon.Node.index;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Fixed-capacity LRU map. When an entry is evicted to stay within capacity,
 * {@code onEvict} is invoked with it — the Pager uses this to flush a dirty page
 * before it drops out of the buffer pool. {@code remove}/{@code clear} do NOT fire it.
 */
public class LruCache<K, V> {

    private final int capacity;
    private final BiConsumer<K, V> onEvict;
    private final LinkedHashMap<K, V> map;

    public LruCache(int capacity, BiConsumer<K, V> onEvict) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.onEvict = onEvict;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                if (size() > LruCache.this.capacity) {
                    if (LruCache.this.onEvict != null) {
                        LruCache.this.onEvict.accept(eldest.getKey(), eldest.getValue());
                    }
                    return true;
                }
                return false;
            }
        };
    }

    public V get(K key) { return map.get(key); }
    public void put(K key, V value) { map.put(key, value); }
    public V remove(K key) { return map.remove(key); }
    public boolean containsKey(K key) { return map.containsKey(key); }
    public int size() { return map.size(); }
    public void clear() { map.clear(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=LruCacheTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/index/LruCache.java Node/src/test/java/com/database/atypon/Node/index/LruCacheTest.java
git commit -m "feat(index): add generic capacity-bounded LruCache with eviction callback"
```

---

### Task 2: KeyType + KeyCodec

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/index/KeyType.java`
- Create: `Node/src/main/java/com/database/atypon/Node/index/KeyCodec.java`
- Test: `Node/src/test/java/com/database/atypon/Node/index/KeyCodecTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum KeyType { INTEGER(4), DOUBLE(8), BOOLEAN(1), STRING(64) }` with `int valueWidth()` and `static KeyType fromSchemaType(String)` (maps `"Integer"/"Double"/"Boolean"/"String"`).
  - `KeyCodec` (static): `int DOC_ID_WIDTH = 4`; `int keySize(KeyType)`; `byte[] encode(KeyType, Object value, int docId)`; `int decodeDocId(KeyType, byte[])`; `int compare(KeyType, byte[] a, byte[] b)`.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyCodecTest {

    @Test
    void keySizesIncludeDocId() {
        assertThat(KeyCodec.keySize(KeyType.INTEGER)).isEqualTo(8);   // 4 + 4
        assertThat(KeyCodec.keySize(KeyType.DOUBLE)).isEqualTo(12);   // 8 + 4
        assertThat(KeyCodec.keySize(KeyType.BOOLEAN)).isEqualTo(5);   // 1 + 4
        assertThat(KeyCodec.keySize(KeyType.STRING)).isEqualTo(68);   // 64 + 4
    }

    @Test
    void integerEncodesDocIdAndOrdersSigned() {
        byte[] five = KeyCodec.encode(KeyType.INTEGER, 5, 1);
        byte[] ten = KeyCodec.encode(KeyType.INTEGER, 10, 1);
        byte[] neg = KeyCodec.encode(KeyType.INTEGER, -3, 1);
        assertThat(KeyCodec.decodeDocId(KeyType.INTEGER, five)).isEqualTo(1);
        assertThat(KeyCodec.compare(KeyType.INTEGER, five, ten)).isNegative();
        assertThat(KeyCodec.compare(KeyType.INTEGER, neg, five)).isNegative(); // -3 < 5 signed
        assertThat(KeyCodec.compare(KeyType.INTEGER, five, five)).isZero();
    }

    @Test
    void duplicateValuesOrderByDocId() {
        byte[] a = KeyCodec.encode(KeyType.INTEGER, 7, 2);
        byte[] b = KeyCodec.encode(KeyType.INTEGER, 7, 5);
        assertThat(KeyCodec.compare(KeyType.INTEGER, a, b)).isNegative();
    }

    @Test
    void stringOrdersLexicographicallyWithPrefix() {
        byte[] a = KeyCodec.encode(KeyType.STRING, "apple", 1);
        byte[] ab = KeyCodec.encode(KeyType.STRING, "apples", 1);
        byte[] b = KeyCodec.encode(KeyType.STRING, "banana", 1);
        assertThat(KeyCodec.compare(KeyType.STRING, a, ab)).isNegative(); // "apple" < "apples"
        assertThat(KeyCodec.compare(KeyType.STRING, a, b)).isNegative();  // "apple" < "banana"
    }

    @Test
    void doubleAndBooleanOrder() {
        assertThat(KeyCodec.compare(KeyType.DOUBLE,
                KeyCodec.encode(KeyType.DOUBLE, 1.5, 1),
                KeyCodec.encode(KeyType.DOUBLE, 2.5, 1))).isNegative();
        assertThat(KeyCodec.compare(KeyType.BOOLEAN,
                KeyCodec.encode(KeyType.BOOLEAN, false, 1),
                KeyCodec.encode(KeyType.BOOLEAN, true, 1))).isNegative();
    }

    @Test
    void fromSchemaTypeMapsAndRejects() {
        assertThat(KeyType.fromSchemaType("Integer")).isEqualTo(KeyType.INTEGER);
        assertThat(KeyType.fromSchemaType("String")).isEqualTo(KeyType.STRING);
        assertThatThrownBy(() -> KeyType.fromSchemaType("Date"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=KeyCodecTest test`
Expected: FAIL — `KeyType`/`KeyCodec` do not exist.

- [ ] **Step 3: Write minimal implementation**

`KeyType.java`:

```java
package com.database.atypon.Node.index;

/** The four indexable field types and their fixed value widths in bytes. */
public enum KeyType {
    INTEGER(4), DOUBLE(8), BOOLEAN(1), STRING(64);

    private final int valueWidth;

    KeyType(int valueWidth) { this.valueWidth = valueWidth; }

    public int valueWidth() { return valueWidth; }

    public static KeyType fromSchemaType(String schemaType) {
        switch (schemaType) {
            case "Integer": return INTEGER;
            case "Double":  return DOUBLE;
            case "Boolean": return BOOLEAN;
            case "String":  return STRING;
            default: throw new IllegalArgumentException("Unsupported index key type: " + schemaType);
        }
    }
}
```

`KeyCodec.java`:

```java
package com.database.atypon.Node.index;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encodes a composite index key = (field value, docId) into a fixed-width byte[]:
 * [value bytes (KeyType.valueWidth)] [docId (4 bytes, big-endian)].
 * Keys compare by decoding the value per type, then by docId — so equal values
 * order by docId, making a non-unique index a sorted set of unique composite keys.
 */
public final class KeyCodec {

    private KeyCodec() {}

    public static final int DOC_ID_WIDTH = 4;

    public static int keySize(KeyType type) {
        return type.valueWidth() + DOC_ID_WIDTH;
    }

    public static byte[] encode(KeyType type, Object value, int docId) {
        byte[] key = new byte[keySize(type)];
        ByteBuffer buf = ByteBuffer.wrap(key); // big-endian by default
        switch (type) {
            case INTEGER:
                buf.putInt(((Number) value).intValue());
                break;
            case DOUBLE:
                buf.putDouble(((Number) value).doubleValue());
                break;
            case BOOLEAN:
                buf.put((byte) (((Boolean) value) ? 1 : 0));
                break;
            case STRING:
                byte[] s = ((String) value).getBytes(StandardCharsets.UTF_8);
                int n = Math.min(s.length, type.valueWidth());
                System.arraycopy(s, 0, key, 0, n); // remaining value bytes stay 0 (padding)
                break;
        }
        buf.position(type.valueWidth());
        buf.putInt(docId);
        return key;
    }

    public static int decodeDocId(KeyType type, byte[] key) {
        return ByteBuffer.wrap(key, type.valueWidth(), DOC_ID_WIDTH).getInt();
    }

    public static int compare(KeyType type, byte[] a, byte[] b) {
        int cmp;
        switch (type) {
            case INTEGER:
                cmp = Integer.compare(ByteBuffer.wrap(a).getInt(), ByteBuffer.wrap(b).getInt());
                break;
            case DOUBLE:
                cmp = Double.compare(ByteBuffer.wrap(a).getDouble(), ByteBuffer.wrap(b).getDouble());
                break;
            case BOOLEAN:
                cmp = Byte.compare(a[0], b[0]);
                break;
            case STRING:
                cmp = compareUnsigned(a, b, type.valueWidth());
                break;
            default:
                throw new IllegalStateException("unreachable");
        }
        if (cmp != 0) return cmp;
        return Integer.compare(decodeDocId(type, a), decodeDocId(type, b));
    }

    private static int compareUnsigned(byte[] a, byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            int av = a[i] & 0xFF;
            int bv = b[i] & 0xFF;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=KeyCodecTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/index/KeyType.java Node/src/main/java/com/database/atypon/Node/index/KeyCodec.java Node/src/test/java/com/database/atypon/Node/index/KeyCodecTest.java
git commit -m "feat(index): add KeyType and composite-key KeyCodec (encode/decode/compare)"
```

---

### Task 3: Page (4 KB META / LEAF / INTERNAL layout)

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/index/Page.java`
- Test: `Node/src/test/java/com/database/atypon/Node/index/PageTest.java`

**Interfaces:**
- Consumes: `KeyType`, `KeyCodec.keySize`.
- Produces: `Page` with constants `PAGE_SIZE=4096`, `TYPE_INTERNAL=1`, `TYPE_LEAF=2`; constructors `Page()` (zeroed) and `Page(byte[])`; `byte[] bytes()`. META: `initMeta(KeyType,int root,int pageCount)`, `hasValidMagic()`, `metaKeyType()`, `metaKeySize()`, `metaRoot()/setMetaRoot(int)`, `metaPageCount()/setMetaPageCount(int)`, `metaDirty()/setMetaDirty(boolean)`. Node: `type()`, `isLeaf()`, `numKeys()/setNumKeys(int)`, `initLeaf()`, `initInternal()`. Leaf: `rightSibling()/setRightSibling(int)`, `leafKey(int i,int keySize)`, `setLeafKey(int i,byte[] key)`, `static int maxLeafKeys(int keySize)`. Internal: `child(int i,int keySize)`, `setChild(int i,int child,int keySize)`, `internalKey(int i,int keySize)`, `setInternalKey(int i,byte[] key,int keySize)`, `static int maxInternalKeys(int keySize)`.

**Layout (big-endian):** META page 0 = `[magic:4][keyTypeOrdinal:1][keySize:4][rootPageId:4][pageCount:4][dirty:1]`. LEAF = `[type:1][numKeys:2][rightSibling:4]` then `numKeys × key(keySize)`. INTERNAL = `[type:1][numKeys:2][child0:4]` then `numKeys × ( key(keySize) child(4) )`. So internal has `numKeys` separator keys and `numKeys+1` children.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PageTest {

    @Test
    void metaRoundTripsThroughBytes() {
        Page p = new Page();
        p.initMeta(KeyType.INTEGER, 1, 2);
        p.setMetaDirty(true);
        Page reloaded = new Page(p.bytes());
        assertThat(reloaded.hasValidMagic()).isTrue();
        assertThat(reloaded.metaKeyType()).isEqualTo(KeyType.INTEGER);
        assertThat(reloaded.metaKeySize()).isEqualTo(KeyCodec.keySize(KeyType.INTEGER));
        assertThat(reloaded.metaRoot()).isEqualTo(1);
        assertThat(reloaded.metaPageCount()).isEqualTo(2);
        assertThat(reloaded.metaDirty()).isTrue();
    }

    @Test
    void blankPageHasNoValidMagic() {
        assertThat(new Page().hasValidMagic()).isFalse();
    }

    @Test
    void leafStoresKeysAndSibling() {
        int keySize = KeyCodec.keySize(KeyType.INTEGER);
        Page leaf = new Page();
        leaf.initLeaf();
        leaf.setRightSibling(7);
        leaf.setLeafKey(0, KeyCodec.encode(KeyType.INTEGER, 10, 1));
        leaf.setLeafKey(1, KeyCodec.encode(KeyType.INTEGER, 20, 2));
        leaf.setNumKeys(2);
        Page reloaded = new Page(leaf.bytes());
        assertThat(reloaded.isLeaf()).isTrue();
        assertThat(reloaded.numKeys()).isEqualTo(2);
        assertThat(reloaded.rightSibling()).isEqualTo(7);
        assertThat(KeyCodec.compare(KeyType.INTEGER, reloaded.leafKey(0, keySize),
                KeyCodec.encode(KeyType.INTEGER, 10, 1))).isZero();
        assertThat(KeyCodec.compare(KeyType.INTEGER, reloaded.leafKey(1, keySize),
                KeyCodec.encode(KeyType.INTEGER, 20, 2))).isZero();
    }

    @Test
    void internalStoresChildrenAndSeparatorKeys() {
        int keySize = KeyCodec.keySize(KeyType.INTEGER);
        Page node = new Page();
        node.initInternal();
        node.setChild(0, 100, keySize);
        node.setInternalKey(0, KeyCodec.encode(KeyType.INTEGER, 50, 0), keySize);
        node.setChild(1, 200, keySize);
        node.setNumKeys(1);
        Page reloaded = new Page(node.bytes());
        assertThat(reloaded.isLeaf()).isFalse();
        assertThat(reloaded.type()).isEqualTo(Page.TYPE_INTERNAL);
        assertThat(reloaded.numKeys()).isEqualTo(1);
        assertThat(reloaded.child(0, keySize)).isEqualTo(100);
        assertThat(reloaded.child(1, keySize)).isEqualTo(200);
        assertThat(KeyCodec.compare(KeyType.INTEGER, reloaded.internalKey(0, keySize),
                KeyCodec.encode(KeyType.INTEGER, 50, 0))).isZero();
    }

    @Test
    void fanOutBoundsArePositiveAndFit() {
        int keySize = KeyCodec.keySize(KeyType.INTEGER);
        assertThat(Page.maxLeafKeys(keySize)).isGreaterThan(100);
        assertThat(Page.maxInternalKeys(keySize)).isGreaterThan(100);
        // last leaf key must fit inside the page
        assertThat(7 + Page.maxLeafKeys(keySize) * keySize).isLessThanOrEqualTo(Page.PAGE_SIZE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=PageTest test`
Expected: FAIL — `Page` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.database.atypon.Node.index;

import java.nio.ByteBuffer;

/**
 * A fixed 4 KB page. Page 0 of an index file is the META page; every other page is
 * an INTERNAL or LEAF B+-tree node. All multi-byte fields are big-endian.
 */
public class Page {

    public static final int PAGE_SIZE = 4096;
    public static final int MAGIC = 0x42504C53; // "BPLS"

    public static final byte TYPE_INTERNAL = 1;
    public static final byte TYPE_LEAF = 2;

    // META layout offsets
    private static final int META_MAGIC = 0;
    private static final int META_KEY_TYPE = 4;
    private static final int META_KEY_SIZE = 5;
    private static final int META_ROOT = 9;
    private static final int META_PAGE_COUNT = 13;
    private static final int META_DIRTY = 17;

    // node common
    private static final int NODE_TYPE = 0;
    private static final int NODE_NUM_KEYS = 1;
    // leaf
    private static final int LEAF_RIGHT_SIBLING = 3;
    private static final int LEAF_HEADER = 7;         // type(1)+numKeys(2)+sibling(4)
    // internal
    private static final int INTERNAL_CHILD0 = 3;     // type(1)+numKeys(2), then child0(4)
    private static final int INTERNAL_HEADER = 7;     // through child0

    private final byte[] data;
    private final ByteBuffer buf;

    public Page() {
        this.data = new byte[PAGE_SIZE];
        this.buf = ByteBuffer.wrap(data);
    }

    public Page(byte[] data) {
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("page must be " + PAGE_SIZE + " bytes, was " + data.length);
        }
        this.data = data;
        this.buf = ByteBuffer.wrap(data);
    }

    public byte[] bytes() { return data; }

    // ---- META ----
    public void initMeta(KeyType keyType, int rootPageId, int pageCount) {
        buf.putInt(META_MAGIC, MAGIC);
        data[META_KEY_TYPE] = (byte) keyType.ordinal();
        buf.putInt(META_KEY_SIZE, KeyCodec.keySize(keyType));
        buf.putInt(META_ROOT, rootPageId);
        buf.putInt(META_PAGE_COUNT, pageCount);
        data[META_DIRTY] = 0;
    }

    public boolean hasValidMagic() { return buf.getInt(META_MAGIC) == MAGIC; }
    public KeyType metaKeyType() { return KeyType.values()[data[META_KEY_TYPE]]; }
    public int metaKeySize() { return buf.getInt(META_KEY_SIZE); }
    public int metaRoot() { return buf.getInt(META_ROOT); }
    public void setMetaRoot(int p) { buf.putInt(META_ROOT, p); }
    public int metaPageCount() { return buf.getInt(META_PAGE_COUNT); }
    public void setMetaPageCount(int c) { buf.putInt(META_PAGE_COUNT, c); }
    public boolean metaDirty() { return data[META_DIRTY] != 0; }
    public void setMetaDirty(boolean d) { data[META_DIRTY] = (byte) (d ? 1 : 0); }

    // ---- node common ----
    public byte type() { return data[NODE_TYPE]; }
    public boolean isLeaf() { return data[NODE_TYPE] == TYPE_LEAF; }
    public int numKeys() { return buf.getShort(NODE_NUM_KEYS) & 0xFFFF; }
    public void setNumKeys(int n) { buf.putShort(NODE_NUM_KEYS, (short) n); }
    public void initLeaf() { data[NODE_TYPE] = TYPE_LEAF; setNumKeys(0); setRightSibling(0); }
    public void initInternal() { data[NODE_TYPE] = TYPE_INTERNAL; setNumKeys(0); }

    // ---- leaf ----
    public int rightSibling() { return buf.getInt(LEAF_RIGHT_SIBLING); }
    public void setRightSibling(int p) { buf.putInt(LEAF_RIGHT_SIBLING, p); }

    public byte[] leafKey(int i, int keySize) {
        byte[] k = new byte[keySize];
        System.arraycopy(data, LEAF_HEADER + i * keySize, k, 0, keySize);
        return k;
    }
    public void setLeafKey(int i, byte[] key) {
        System.arraycopy(key, 0, data, LEAF_HEADER + i * key.length, key.length);
    }
    public static int maxLeafKeys(int keySize) { return (PAGE_SIZE - LEAF_HEADER) / keySize; }

    // ---- internal ----
    public int child(int i, int keySize) {
        if (i == 0) return buf.getInt(INTERNAL_CHILD0);
        int off = INTERNAL_HEADER + (i - 1) * (keySize + 4) + keySize;
        return buf.getInt(off);
    }
    public void setChild(int i, int child, int keySize) {
        if (i == 0) { buf.putInt(INTERNAL_CHILD0, child); return; }
        int off = INTERNAL_HEADER + (i - 1) * (keySize + 4) + keySize;
        buf.putInt(off, child);
    }
    public byte[] internalKey(int i, int keySize) {
        int off = INTERNAL_HEADER + i * (keySize + 4);
        byte[] k = new byte[keySize];
        System.arraycopy(data, off, k, 0, keySize);
        return k;
    }
    public void setInternalKey(int i, byte[] key, int keySize) {
        int off = INTERNAL_HEADER + i * (keySize + 4);
        System.arraycopy(key, 0, data, off, keySize);
    }
    public static int maxInternalKeys(int keySize) { return (PAGE_SIZE - INTERNAL_HEADER) / (keySize + 4); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=PageTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add Node/src/main/java/com/database/atypon/Node/index/Page.java Node/src/test/java/com/database/atypon/Node/index/PageTest.java
git commit -m "feat(index): add 4KB Page with META/LEAF/INTERNAL layouts and accessors"
```

---

### Task 4: Pager (buffer pool over an index file)

**Files:**
- Create: `Node/src/main/java/com/database/atypon/Node/index/Pager.java`
- Test: `Node/src/test/java/com/database/atypon/Node/index/PagerTest.java`

**Interfaces:**
- Consumes: `Page`, `LruCache`.
- Produces: `Pager implements java.io.Closeable`. Constructors `Pager(java.io.File)` (pool = `DEFAULT_POOL_PAGES=128`) and `Pager(java.io.File, int poolPages)`. Methods (all `synchronized`): `int allocate()` (append a blank page, return its id), `Page get(int pageId)`, `void markDirty(int pageId)`, `void flushAll()`, `int pageCountOnDisk()`, `void close()`. Constant `META_PAGE_ID=0`. On eviction a dirty page is written back before it leaves the pool.

- [ ] **Step 1: Write the failing test**

```java
package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PagerTest {

    @Test
    void allocateWriteFlushThenReopenAndRead(@TempDir Path dir) throws Exception {
        File f = dir.resolve("test.idx").toFile();
        try (Pager pager = new Pager(f)) {
            int meta = pager.allocate();               // page 0
            assertThat(meta).isEqualTo(Pager.META_PAGE_ID);
            Page p = pager.get(meta);
            p.initMeta(KeyType.INTEGER, 1, 5);
            pager.markDirty(meta);
            pager.flushAll();
        }
        try (Pager pager = new Pager(f)) {
            assertThat(pager.pageCountOnDisk()).isEqualTo(1);
            Page reloaded = pager.get(Pager.META_PAGE_ID);
            assertThat(reloaded.hasValidMagic()).isTrue();
            assertThat(reloaded.metaRoot()).isEqualTo(1);
            assertThat(reloaded.metaPageCount()).isEqualTo(5);
        }
    }

    @Test
    void allocateReturnsSequentialIds(@TempDir Path dir) throws Exception {
        File f = dir.resolve("seq.idx").toFile();
        try (Pager pager = new Pager(f)) {
            assertThat(pager.allocate()).isEqualTo(0);
            assertThat(pager.allocate()).isEqualTo(1);
            assertThat(pager.allocate()).isEqualTo(2);
            assertThat(pager.pageCountOnDisk()).isEqualTo(3);
        }
    }

    @Test
    void evictionWritesBackDirtyPages(@TempDir Path dir) throws Exception {
        File f = dir.resolve("evict.idx").toFile();
        // pool of 1 page forces eviction on the second distinct page access
        try (Pager pager = new Pager(f, 1)) {
            int a = pager.allocate();   // 0
            int b = pager.allocate();   // 1
            Page pa = pager.get(a);
            pa.initLeaf();
            pa.setRightSibling(42);
            pager.markDirty(a);
            pager.get(b);               // touches b -> evicts a, which must flush to disk
        }
        try (Pager pager = new Pager(f)) {
            Page pa = pager.get(0);
            assertThat(pa.isLeaf()).isTrue();
            assertThat(pa.rightSibling()).isEqualTo(42); // survived via eviction write-back
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Node && mvn -q -Dtest=PagerTest test`
Expected: FAIL — `Pager` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.database.atypon.Node.index;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashSet;
import java.util.Set;

/**
 * Reads/writes fixed 4 KB pages to an index file through an LRU buffer pool.
 * A page marked dirty is written back on eviction and on {@link #flushAll()}.
 * All public methods are synchronized on this Pager.
 */
public class Pager implements Closeable {

    public static final int DEFAULT_POOL_PAGES = 128;
    public static final int META_PAGE_ID = 0;

    private final RandomAccessFile file;
    private final LruCache<Integer, Page> pool;
    private final Set<Integer> dirty = new HashSet<>();

    public Pager(File path) throws IOException {
        this(path, DEFAULT_POOL_PAGES);
    }

    public Pager(File path, int poolPages) throws IOException {
        this.file = new RandomAccessFile(path, "rw");
        this.pool = new LruCache<>(poolPages, (id, page) -> {
            try {
                if (dirty.remove(id)) {
                    writeToDisk(id, page);
                }
            } catch (IOException e) {
                throw new RuntimeException("flush on eviction failed for page " + id, e);
            }
        });
    }

    public synchronized int pageCountOnDisk() throws IOException {
        return (int) (file.length() / Page.PAGE_SIZE);
    }

    public synchronized int allocate() throws IOException {
        int id = pageCountOnDisk();
        Page blank = new Page();
        writeToDisk(id, blank); // extend the file so its length reflects the new page
        pool.put(id, blank);
        return id;
    }

    public synchronized Page get(int pageId) throws IOException {
        Page cached = pool.get(pageId);
        if (cached != null) {
            return cached;
        }
        byte[] data = new byte[Page.PAGE_SIZE];
        file.seek((long) pageId * Page.PAGE_SIZE);
        file.readFully(data);
        Page page = new Page(data);
        pool.put(pageId, page);
        return page;
    }

    public synchronized void markDirty(int pageId) {
        dirty.add(pageId);
    }

    public synchronized void flushAll() throws IOException {
        for (Integer id : new HashSet<>(dirty)) {
            Page p = pool.get(id);
            if (p != null) {
                writeToDisk(id, p);
            }
        }
        dirty.clear();
        file.getFD().sync();
    }

    private void writeToDisk(int pageId, Page page) throws IOException {
        file.seek((long) pageId * Page.PAGE_SIZE);
        file.write(page.bytes());
    }

    @Override
    public synchronized void close() throws IOException {
        file.close();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Node && mvn -q -Dtest=PagerTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full suite and commit**

Run: `cd Node && mvn -q test` — expected: all green (16 M0 tests + the new index tests).

```bash
git add Node/src/main/java/com/database/atypon/Node/index/Pager.java Node/src/test/java/com/database/atypon/Node/index/PagerTest.java
git commit -m "feat(index): add Pager buffer pool with dirty write-back over an index file"
```

---

## Definition of done (M1·1)

- New `com.database.atypon.Node.index` package with `LruCache`, `KeyType`, `KeyCodec`, `Page`, `Pager`.
- Composite keys encode/decode/compare correctly (incl. signed ints, doubles, strings by prefix, duplicate-by-docId).
- 4 KB pages round-trip META/LEAF/INTERNAL through raw bytes; fan-out bounds fit the page.
- `Pager` allocates sequential pages, caches through an LRU pool, and write-backs dirty pages on eviction and `flushAll` (verified by reopening the file).
- Full suite green on JDK 17. No Spring/HTTP touched; `IndexingService` still the empty M0 stub (filled in M1·4).
