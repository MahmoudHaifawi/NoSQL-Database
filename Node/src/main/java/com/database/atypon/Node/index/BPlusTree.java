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
        validateMaxKeys(keyType, maxKeys);
        this.pager = pager;
        this.keyType = keyType;
        this.keySize = KeyCodec.keySize(keyType);
        this.maxKeys = maxKeys;
    }

    public static int defaultMaxKeys(KeyType keyType) {
        int ks = KeyCodec.keySize(keyType);
        return Math.min(Page.maxLeafKeys(ks), Page.maxInternalKeys(ks));
    }

    private static void validateMaxKeys(KeyType keyType, int maxKeys) {
        int max = defaultMaxKeys(keyType);
        if (maxKeys < 3 || maxKeys > max) {
            throw new IllegalArgumentException("maxKeys must be in [3, " + max + "], was " + maxKeys);
        }
    }

    public static BPlusTree create(Pager pager, KeyType keyType) throws IOException {
        return create(pager, keyType, defaultMaxKeys(keyType));
    }

    public static BPlusTree create(Pager pager, KeyType keyType, int maxKeys) throws IOException {
        validateMaxKeys(keyType, maxKeys);
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

    /**
     * Precondition: the composite key must be unique (value ‖ docId); re-inserting an
     * existing key creates a duplicate and breaks tree invariants — callers must delete
     * before re-inserting.
     */
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

    // ---- validate ----

    /** Verify B+-tree structural invariants; throws IllegalStateException on any violation. */
    public void validate() throws IOException {
        List<Integer> leafDepths = new ArrayList<>();
        validateNode(rootId(), 0, null, null, leafDepths);
        for (int d : leafDepths) {
            if (d != leafDepths.get(0)) {
                throw new IllegalStateException("leaves at differing depths: " + leafDepths);
            }
        }
        validateLeafChain(leafDepths.size());
    }

    /** Walk the leaf sibling chain from the leftmost leaf: must visit exactly
     *  {@code expectedLeaves} leaves once each, in globally ascending key order,
     *  terminating at the null sentinel (0). */
    private void validateLeafChain(int expectedLeaves) throws IOException {
        int leafId = leftmostLeaf();
        byte[] prevKey = null;
        int visited = 0;
        while (leafId != 0) {
            Page leaf = pager.get(leafId);
            if (!leaf.isLeaf()) {
                throw new IllegalStateException("sibling chain reached non-leaf page " + leafId);
            }
            int n = leaf.numKeys();
            for (int i = 0; i < n; i++) {
                byte[] k = leaf.leafKey(i, keySize);
                if (prevKey != null && KeyCodec.compare(keyType, prevKey, k) >= 0) {
                    throw new IllegalStateException("leaf-chain keys not strictly ascending across siblings");
                }
                prevKey = k;
            }
            visited++;
            if (visited > expectedLeaves) {
                throw new IllegalStateException("leaf sibling chain has a cycle or extra leaves");
            }
            leafId = leaf.rightSibling();
        }
        if (visited != expectedLeaves) {
            throw new IllegalStateException("leaf chain visited " + visited + " of " + expectedLeaves + " leaves");
        }
    }

    private int leftmostLeaf() throws IOException {
        int pid = rootId();
        Page p = pager.get(pid);
        while (!p.isLeaf()) {
            pid = p.child(0, keySize);
            p = pager.get(pid);
        }
        return pid;
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

    // ---- query ----

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

    // ---- bulk load ----

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
            int childrenPerNode = maxKeys + 1;
            int i = 0;
            while (i < count) {
                int remaining = count - i;
                int take;
                if (remaining <= childrenPerNode) {
                    take = remaining;
                } else if (remaining - childrenPerNode == 1) {
                    take = childrenPerNode - 1; // leave >= 2 children for the next node (no single-child node)
                } else {
                    take = childrenPerNode;
                }
                int end = i + take;
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
                i = end;
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
}
