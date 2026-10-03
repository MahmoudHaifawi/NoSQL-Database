package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BPlusTreeQueryTest {

    /** Insert values 0..n-1 with docId == value, so decoded docIds equal the values. */
    private BPlusTree treeOfValues(Pager pager, int n) throws Exception {
        BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 6);
        for (int v = 0; v < n; v++) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, v));
        return tree;
    }

    private static List<Integer> range(int lo, int hi) {
        List<Integer> out = new ArrayList<>();
        for (int v = lo; v <= hi; v++) out.add(v);
        return out;
    }

    @Test
    void betweenReturnsInclusiveAscending(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("q.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 500);
            List<Integer> ids = tree.queryDocIds(BPlusTree.Op.BETWEEN, 100, 200, true, 0, -1);
            assertThat(ids).isEqualTo(range(100, 200));
        }
    }

    @Test
    void comparisonOperators(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("c.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 50);
            assertThat(tree.queryDocIds(BPlusTree.Op.GT, 47, null, true, 0, -1)).isEqualTo(range(48, 49));
            assertThat(tree.queryDocIds(BPlusTree.Op.GTE, 47, null, true, 0, -1)).isEqualTo(range(47, 49));
            assertThat(tree.queryDocIds(BPlusTree.Op.LT, 3, null, true, 0, -1)).isEqualTo(range(0, 2));
            assertThat(tree.queryDocIds(BPlusTree.Op.LTE, 3, null, true, 0, -1)).isEqualTo(range(0, 3));
        }
    }

    @Test
    void equalityReturnsAllDocIdsOfAValue(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("eq.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int docId = 1; docId <= 10; docId++) tree.insert(KeyCodec.encode(KeyType.INTEGER, 5, docId));
            tree.insert(KeyCodec.encode(KeyType.INTEGER, 4, 0));
            tree.insert(KeyCodec.encode(KeyType.INTEGER, 6, 0));
            assertThat(tree.queryDocIds(BPlusTree.Op.EQ, 5, null, true, 0, -1)).isEqualTo(range(1, 10));
        }
    }

    @Test
    void descendingAndPagination(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("p.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 100);
            // BETWEEN 10..19 has 10 values; DESC = 19,18,...,10
            assertThat(tree.queryDocIds(BPlusTree.Op.BETWEEN, 10, 19, false, 0, -1))
                    .isEqualTo(List.of(19, 18, 17, 16, 15, 14, 13, 12, 11, 10));
            // ASC, offset 3, limit 4 -> 13,14,15,16
            assertThat(tree.queryDocIds(BPlusTree.Op.BETWEEN, 10, 19, true, 3, 4))
                    .isEqualTo(List.of(13, 14, 15, 16));
        }
    }

    @Test
    void emptyAndUnboundedRanges(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("u.idx").toFile())) {
            BPlusTree tree = treeOfValues(pager, 30);
            assertThat(tree.queryDocIds(BPlusTree.Op.GT, 100, null, true, 0, -1)).isEmpty();
            assertThat(tree.queryDocIds(BPlusTree.Op.LTE, 4, null, true, 0, -1)).isEqualTo(range(0, 4));
            assertThat(tree.scanRange(null, true, null, true)).hasSize(30); // full scan
        }
    }
}
