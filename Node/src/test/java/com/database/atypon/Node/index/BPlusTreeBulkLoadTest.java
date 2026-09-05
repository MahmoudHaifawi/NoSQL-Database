package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BPlusTreeBulkLoadTest {

    private static List<byte[]> sortedIntKeys(int n) {
        List<byte[]> keys = new ArrayList<>(n);
        for (int v = 0; v < n; v++) keys.add(KeyCodec.encode(KeyType.INTEGER, v, v)); // already ascending
        return keys;
    }

    @Test
    void bulkLoadBuildsValidTreeWithAllKeys(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("bl.idx").toFile())) {
            BPlusTree tree = BPlusTree.bulkLoad(pager, KeyType.INTEGER, sortedIntKeys(1000), 6);
            assertThatCode(tree::validate).doesNotThrowAnyException();
            for (int v = 0; v < 1000; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, v))).as("v=" + v).isTrue();
            }
            assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 1000, 1000))).isFalse();
        }
    }

    @Test
    void bulkLoadRangeQueriesMatch(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("blq.idx").toFile())) {
            BPlusTree tree = BPlusTree.bulkLoad(pager, KeyType.INTEGER, sortedIntKeys(300), 5);
            List<Integer> expected = new ArrayList<>();
            for (int v = 50; v <= 120; v++) expected.add(v);
            assertThat(tree.queryDocIds(BPlusTree.Op.BETWEEN, 50, 120, true, 0, -1)).isEqualTo(expected);
        }
    }

    @Test
    void bulkLoadEmpty(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("ble.idx").toFile())) {
            BPlusTree tree = BPlusTree.bulkLoad(pager, KeyType.INTEGER, new ArrayList<>(), 4);
            tree.validate();
            assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 1, 1))).isFalse();
        }
    }

    @Test
    void bulkLoadPersistsAcrossReopen(@TempDir Path dir) throws Exception {
        File f = dir.resolve("blp.idx").toFile();
        try (Pager pager = new Pager(f)) {
            BPlusTree.bulkLoad(pager, KeyType.INTEGER, sortedIntKeys(200), 4);
        }
        try (Pager pager = new Pager(f)) {
            BPlusTree tree = BPlusTree.open(pager, 4);
            tree.validate();
            for (int v = 0; v < 200; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, v))).isTrue();
            }
        }
    }

    @Test
    void bulkLoadTrailingRemainderProducesValidTree(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("blr.idx").toFile())) {
            BPlusTree tree = BPlusTree.bulkLoad(pager, KeyType.INTEGER, sortedIntKeys(13), 3);
            tree.validate();
            for (int v = 0; v < 13; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, v))).as("v=" + v).isTrue();
            }
            assertThat(tree.queryDocIds(BPlusTree.Op.BETWEEN, 3, 9, true, 0, -1))
                    .isEqualTo(java.util.List.of(3, 4, 5, 6, 7, 8, 9));
        }
    }
}
