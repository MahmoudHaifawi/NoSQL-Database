package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import com.database.atypon.Node.index.KeyCodec;
import com.database.atypon.Node.index.KeyType;
import com.database.atypon.Node.index.Page;
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

    /**
     * Maintain every index defined on this schema after a document is written.
     * Not atomic across multiple indexes: if a later field's insert fails, earlier indexes are
     * already updated; since indexes are derived from records, a partial failure is recoverable
     * by dropping and recreating the affected index.
     */
    public void onInsert(String db, String schema, int docId, JSONObject doc) throws IOException {
        for (String field : listIndexes(db, schema)) {
            if (!doc.has(field)) {
                continue;
            }
            try (Pager pager = new Pager(indexFile(db, schema, field).toFile())) {
                BPlusTree tree = BPlusTree.open(pager);
                KeyType keyType = indexKeyType(pager);
                tree.insert(KeyCodec.encode(keyType, coerce(keyType, doc.get(field)), docId));
                tree.flush();
            }
        }
    }

    private KeyType indexKeyType(Pager pager) throws IOException {
        Page meta = pager.get(Pager.META_PAGE_ID);
        if (!meta.hasValidMagic()) {
            throw new IllegalStateException("corrupt or empty index file");
        }
        return meta.metaKeyType();
    }

    /** Coerce a raw request value (JSON number/String/Boolean) to the index's key type. */
    private Object coerce(KeyType keyType, Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("query value must not be null");
        }
        switch (keyType) {
            case INTEGER: return (raw instanceof Number) ? ((Number) raw).intValue() : Integer.parseInt(raw.toString());
            case DOUBLE:  return (raw instanceof Number) ? ((Number) raw).doubleValue() : Double.parseDouble(raw.toString());
            case BOOLEAN: return (raw instanceof Boolean) ? raw : Boolean.parseBoolean(raw.toString());
            case STRING:  return raw.toString();
            default: throw new IllegalStateException("unknown key type: " + keyType);
        }
    }
}
