package com.database.atypon.Node.services.index;

import com.database.atypon.Node.index.BPlusTree;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Spring-managed, concurrency-safe facade over {@link IndexService}. A single global
 * ReadWriteLock serializes index mutations (create/drop/onInsert) against readers
 * (query/list) so a REST query never observes a half-written index while a document
 * write maintains it. Writes are rare (per the design), so a global lock is adequate;
 * per-index locking is a possible future optimization.
 */
@Service
public class IndexManager {

    private final IndexService indexService;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public IndexManager() {
        this(Paths.get("./data"));
    }

    public IndexManager(Path dataRoot) {
        this.indexService = new IndexService(dataRoot);
    }

    public void createIndex(String db, String schema, String field) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.createIndex(db, schema, field);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void dropIndex(String db, String schema, String field) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.dropIndex(db, schema, field);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onInsert(String db, String schema, int docId, JSONObject doc) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.onInsert(db, schema, docId, doc);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Patches every index on the schema after a document update: removes the old composite key and
     * inserts the new one (incremental, using B+-tree key deletion). Runs under the write lock so
     * readers never see a half-applied index.
     */
    public void onUpdate(String db, String schema, int docId, JSONObject oldDoc, JSONObject newDoc) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.onUpdate(db, schema, docId, oldDoc, newDoc);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void onDelete(String db, String schema, int docId, JSONObject doc) throws IOException {
        lock.writeLock().lock();
        try {
            indexService.onDelete(db, schema, docId, doc);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<String> listIndexes(String db, String schema) throws IOException {
        lock.readLock().lock();
        try {
            return indexService.listIndexes(db, schema);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Integer> query(String db, String schema, String field,
                               BPlusTree.Op op, Object value, Object high,
                               boolean ascending, int offset, int limit) throws IOException {
        lock.readLock().lock();
        try {
            return indexService.query(db, schema, field, op, value, high, ascending, offset, limit);
        } finally {
            lock.readLock().unlock();
        }
    }
}
