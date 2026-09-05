package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BPlusTreeTest {

    private static byte[] intKey(int v) {
        return KeyCodec.encode(KeyType.INTEGER, v, 0);
    }

    @Test
    void emptyTreeContainsNothing(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("a.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            assertThat(tree.contains(intKey(5))).isFalse();
        }
    }

    @Test
    void insertAndContainsWithoutSplit(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("b.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            tree.insert(intKey(10));
            tree.insert(intKey(5));
            tree.insert(intKey(20));
            assertThat(tree.contains(intKey(5))).isTrue();
            assertThat(tree.contains(intKey(10))).isTrue();
            assertThat(tree.contains(intKey(20))).isTrue();
            assertThat(tree.contains(intKey(99))).isFalse();
        }
    }

    @Test
    void leafSplitGrowsRoot(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("c.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 5; v++) tree.insert(intKey(v)); // 5 > maxKeys(4) forces a split
            assertThat(pager.get(Pager.META_PAGE_ID).metaRoot()).isNotEqualTo(1); // root moved off the first leaf
            assertThat(pager.get(pager.get(Pager.META_PAGE_ID).metaRoot()).isLeaf()).isFalse(); // root is now internal
            for (int v = 1; v <= 5; v++) assertThat(tree.contains(intKey(v))).isTrue();
        }
    }

    @Test
    void manyInsertsForceMultipleLevels(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("d.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 60; v++) tree.insert(intKey(v));
            for (int v = 1; v <= 60; v++) assertThat(tree.contains(intKey(v))).as("v=" + v).isTrue();
            assertThat(tree.contains(intKey(0))).isFalse();
            assertThat(tree.contains(intKey(61))).isFalse();
        }
    }

    @Test
    void shuffledInsertsAllFound(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("e.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            List<Integer> vs = new ArrayList<>();
            for (int v = 1; v <= 40; v++) vs.add(v);
            Collections.shuffle(vs, new java.util.Random(42));
            for (int v : vs) tree.insert(intKey(v));
            for (int v = 1; v <= 40; v++) assertThat(tree.contains(intKey(v))).as("v=" + v).isTrue();
        }
    }
}
