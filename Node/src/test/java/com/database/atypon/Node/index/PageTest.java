package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PageTest {

    @Test
    void metaRoundTripsThroughBytes() {
        Page p = new Page();
        p.initMeta(KeyType.INTEGER, 1, 2);
        p.setMetaDirty(true);
        Page reloaded = new Page(p.bytes());
        assertThat(reloaded.hasValidMagic()).isTrue();
        assertThat(reloaded.metaKeyType()).isEqualTo(KeyType.INTEGER);
        assertThat(reloaded.metaKeySize()).isEqualTo(KeyCodec.keySize(KeyType.INTEGER));
        assertThat(reloaded.metaRoot()).isEqualTo(1);
        assertThat(reloaded.metaPageCount()).isEqualTo(2);
        assertThat(reloaded.metaDirty()).isTrue();
    }

    @Test
    void blankPageHasNoValidMagic() {
        assertThat(new Page().hasValidMagic()).isFalse();
    }

    @Test
    void leafStoresKeysAndSibling() {
        int keySize = KeyCodec.keySize(KeyType.INTEGER);
        Page leaf = new Page();
        leaf.initLeaf();
        leaf.setRightSibling(7);
        leaf.setLeafKey(0, KeyCodec.encode(KeyType.INTEGER, 10, 1));
        leaf.setLeafKey(1, KeyCodec.encode(KeyType.INTEGER, 20, 2));
        leaf.setNumKeys(2);
        Page reloaded = new Page(leaf.bytes());
        assertThat(reloaded.isLeaf()).isTrue();
        assertThat(reloaded.numKeys()).isEqualTo(2);
        assertThat(reloaded.rightSibling()).isEqualTo(7);
        assertThat(KeyCodec.compare(KeyType.INTEGER, reloaded.leafKey(0, keySize),
                KeyCodec.encode(KeyType.INTEGER, 10, 1))).isZero();
        assertThat(KeyCodec.compare(KeyType.INTEGER, reloaded.leafKey(1, keySize),
                KeyCodec.encode(KeyType.INTEGER, 20, 2))).isZero();
    }

    @Test
    void internalStoresChildrenAndSeparatorKeys() {
        int keySize = KeyCodec.keySize(KeyType.INTEGER);
        Page node = new Page();
        node.initInternal();
        node.setChild(0, 100, keySize);
        node.setInternalKey(0, KeyCodec.encode(KeyType.INTEGER, 50, 0), keySize);
        node.setChild(1, 200, keySize);
        node.setNumKeys(1);
        Page reloaded = new Page(node.bytes());
        assertThat(reloaded.isLeaf()).isFalse();
        assertThat(reloaded.type()).isEqualTo(Page.TYPE_INTERNAL);
        assertThat(reloaded.numKeys()).isEqualTo(1);
        assertThat(reloaded.child(0, keySize)).isEqualTo(100);
        assertThat(reloaded.child(1, keySize)).isEqualTo(200);
        assertThat(KeyCodec.compare(KeyType.INTEGER, reloaded.internalKey(0, keySize),
                KeyCodec.encode(KeyType.INTEGER, 50, 0))).isZero();
    }

    @Test
    void fanOutBoundsArePositiveAndFit() {
        int keySize = KeyCodec.keySize(KeyType.INTEGER);
        assertThat(Page.maxLeafKeys(keySize)).isGreaterThan(100);
        assertThat(Page.maxInternalKeys(keySize)).isGreaterThan(100);
        // last leaf key must fit inside the page
        assertThat(7 + Page.maxLeafKeys(keySize) * keySize).isLessThanOrEqualTo(Page.PAGE_SIZE);
    }
}
