package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BPlusTreeValidateTest {

    @Test
    void invariantsHoldAfterManyInserts(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("v.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 100; v++) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, 0));
            assertThatCode(tree::validate).doesNotThrowAnyException();
        }
    }

    @Test
    void oracleMembershipOverThousandShuffledKeys(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("o.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 6);
            List<Integer> vs = new ArrayList<>();
            for (int v = 0; v < 1000; v++) vs.add(v);
            Collections.shuffle(vs, new Random(7));
            for (int v : vs) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, 0));
            tree.validate();
            for (int v = 0; v < 1000; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, 0))).as("present v=" + v).isTrue();
            }
            for (int v = 1000; v < 1020; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, 0))).as("absent v=" + v).isFalse();
            }
        }
    }

    @Test
    void duplicateValuesDistinctDocIdsAllStored(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("dup.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int docId = 1; docId <= 20; docId++) tree.insert(KeyCodec.encode(KeyType.INTEGER, 5, docId));
            tree.validate();
            for (int docId = 1; docId <= 20; docId++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 5, docId))).isTrue();
            }
            assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, 5, 99))).isFalse();
        }
    }

    @Test
    void stringKeysRoundTripThroughSplits(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("s.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.STRING, 4);
            String[] words = {"apple","banana","cherry","date","fig","grape","kiwi","lemon",
                    "mango","nectar","orange","pear","quince","raspberry","straw","tangerine",
                    "ugli","vanilla","watermelon","xigua","yam","zucchini"};
            for (String w : words) tree.insert(KeyCodec.encode(KeyType.STRING, w, 0));
            tree.validate();
            for (String w : words) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.STRING, w, 0))).as(w).isTrue();
            }
            assertThat(tree.contains(KeyCodec.encode(KeyType.STRING, "missing", 0))).isFalse();
        }
    }

    @Test
    void persistsAcrossReopen(@TempDir Path dir) throws Exception {
        File f = dir.resolve("p.idx").toFile();
        try (Pager pager = new Pager(f)) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 30; v++) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, 0));
            tree.flush();
        }
        try (Pager pager = new Pager(f)) {
            BPlusTree tree = BPlusTree.open(pager, 4);
            tree.validate();
            for (int v = 1; v <= 30; v++) {
                assertThat(tree.contains(KeyCodec.encode(KeyType.INTEGER, v, 0))).as("v=" + v).isTrue();
            }
        }
    }

    @Test
    void validateDetectsBrokenLeafChain(@TempDir Path dir) throws Exception {
        try (Pager pager = new Pager(dir.resolve("chain.idx").toFile())) {
            BPlusTree tree = BPlusTree.create(pager, KeyType.INTEGER, 4);
            for (int v = 1; v <= 20; v++) tree.insert(KeyCodec.encode(KeyType.INTEGER, v, 0)); // forces several leaves
            tree.validate(); // intact chain is fine
            // corrupt: prematurely terminate the leftmost leaf's sibling link
            int keySize = KeyCodec.keySize(KeyType.INTEGER);
            int pid = pager.get(Pager.META_PAGE_ID).metaRoot();
            Page node = pager.get(pid);
            while (!node.isLeaf()) { pid = node.child(0, keySize); node = pager.get(pid); }
            node.setRightSibling(0);
            pager.markDirty(pid);
            org.assertj.core.api.Assertions.assertThatThrownBy(tree::validate)
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
