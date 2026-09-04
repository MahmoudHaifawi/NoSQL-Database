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
