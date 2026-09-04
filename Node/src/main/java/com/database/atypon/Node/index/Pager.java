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
