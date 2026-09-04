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

    @Test
    void dirtyPagePersistsOnCloseWithoutExplicitFlush(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File f = dir.resolve("closeflush.idx").toFile();
        try (Pager pager = new Pager(f)) {
            int id = pager.allocate();
            Page p = pager.get(id);
            p.initLeaf();
            p.setRightSibling(99);
            pager.markDirty(id);
            // NOTE: no explicit flushAll() — close() must persist it
        }
        try (Pager pager = new Pager(f)) {
            Page reloaded = pager.get(0);
            assertThat(reloaded.isLeaf()).isTrue();
            assertThat(reloaded.rightSibling()).isEqualTo(99);
        }
    }
}
