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
