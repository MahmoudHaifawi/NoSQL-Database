package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BPlusTreeDeleteTest {

    private static byte[] k(int v) {
        return KeyCodec.encode(KeyType.INTEGER, v, 0);
    }

    @Test
    void deleteRemovesKeyLeavingSiblings(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("a.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            tree.insert(k(10));
            tree.insert(k(5));
            tree.insert(k(20));
            assertThat(tree.delete(k(10))).isTrue();
            assertThat(tree.contains(k(10))).isFalse();
            assertThat(tree.contains(k(5))).isTrue();
            assertThat(tree.contains(k(20))).isTrue();
        }
    }

    @Test
    void deleteAbsentReturnsFalse(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("b.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            tree.insert(k(5));
            assertThat(tree.delete(k(99))).isFalse();
            assertThat(tree.contains(k(5))).isTrue();
        }
    }

    @Test
    void deleteMinKeyAfterSplitKeepsOthersAndValidates(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("c.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 5; v++) tree.insert(k(v)); // forces a split
            assertThat(tree.delete(k(1))).isTrue();          // remove a leaf minimum
            assertThat(tree.contains(k(1))).isFalse();
            for (int v = 2; v <= 5; v++) assertThat(tree.contains(k(v))).as("v=" + v).isTrue();
            tree.validate();
        }
    }

    @Test
    void deleteAllThenValidateAndReinsert(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("d.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 10; v++) tree.insert(k(v));
            for (int v = 1; v <= 10; v++) assertThat(tree.delete(k(v))).as("del " + v).isTrue();
            for (int v = 1; v <= 10; v++) assertThat(tree.contains(k(v))).isFalse();
            tree.validate();                 // empty/under-full leaves are still valid
            tree.insert(k(42));              // tree remains usable
            assertThat(tree.contains(k(42))).isTrue();
        }
    }

    @Test
    void oracleRandomDeletes(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("e.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 40; v++) tree.insert(k(v));
            List<Integer> toDelete = new ArrayList<>();
            for (int v = 1; v <= 40; v++) if (v % 3 == 0) toDelete.add(v);
            Collections.shuffle(toDelete, new java.util.Random(7));
            for (int v : toDelete) assertThat(tree.delete(k(v))).isTrue();
            for (int v = 1; v <= 40; v++) {
                assertThat(tree.contains(k(v))).as("v=" + v).isEqualTo(v % 3 != 0);
            }
            tree.validate();
        }
    }
}
