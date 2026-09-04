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
