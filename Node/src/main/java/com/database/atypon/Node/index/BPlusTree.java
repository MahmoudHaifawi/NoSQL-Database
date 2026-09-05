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
        int max = defaultMaxKeys(keyType);
        if (maxKeys < 3 || maxKeys > max) {
            throw new IllegalArgumentException("maxKeys must be in [3, " + max + "], was " + maxKeys);
        }
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
