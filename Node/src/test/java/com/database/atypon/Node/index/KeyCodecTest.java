package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyCodecTest {

    @Test
    void keySizesIncludeDocId() {
        assertThat(KeyCodec.keySize(KeyType.INTEGER)).isEqualTo(8);   // 4 + 4
        assertThat(KeyCodec.keySize(KeyType.DOUBLE)).isEqualTo(12);   // 8 + 4
        assertThat(KeyCodec.keySize(KeyType.BOOLEAN)).isEqualTo(5);   // 1 + 4
        assertThat(KeyCodec.keySize(KeyType.STRING)).isEqualTo(68);   // 64 + 4
    }

    @Test
    void integerEncodesDocIdAndOrdersSigned() {
        byte[] five = KeyCodec.encode(KeyType.INTEGER, 5, 1);
        byte[] ten = KeyCodec.encode(KeyType.INTEGER, 10, 1);
        byte[] neg = KeyCodec.encode(KeyType.INTEGER, -3, 1);
        assertThat(KeyCodec.decodeDocId(KeyType.INTEGER, five)).isEqualTo(1);
        assertThat(KeyCodec.compare(KeyType.INTEGER, five, ten)).isNegative();
        assertThat(KeyCodec.compare(KeyType.INTEGER, neg, five)).isNegative(); // -3 < 5 signed
        assertThat(KeyCodec.compare(KeyType.INTEGER, five, five)).isZero();
    }

    @Test
    void duplicateValuesOrderByDocId() {
        byte[] a = KeyCodec.encode(KeyType.INTEGER, 7, 2);
        byte[] b = KeyCodec.encode(KeyType.INTEGER, 7, 5);
        assertThat(KeyCodec.compare(KeyType.INTEGER, a, b)).isNegative();
    }

    @Test
    void stringOrdersLexicographicallyWithPrefix() {
        byte[] a = KeyCodec.encode(KeyType.STRING, "apple", 1);
        byte[] ab = KeyCodec.encode(KeyType.STRING, "apples", 1);
        byte[] b = KeyCodec.encode(KeyType.STRING, "banana", 1);
        assertThat(KeyCodec.compare(KeyType.STRING, a, ab)).isNegative(); // "apple" < "apples"
        assertThat(KeyCodec.compare(KeyType.STRING, a, b)).isNegative();  // "apple" < "banana"
    }

    @Test
    void doubleAndBooleanOrder() {
        assertThat(KeyCodec.compare(KeyType.DOUBLE,
                KeyCodec.encode(KeyType.DOUBLE, 1.5, 1),
                KeyCodec.encode(KeyType.DOUBLE, 2.5, 1))).isNegative();
        assertThat(KeyCodec.compare(KeyType.BOOLEAN,
                KeyCodec.encode(KeyType.BOOLEAN, false, 1),
                KeyCodec.encode(KeyType.BOOLEAN, true, 1))).isNegative();
    }

    @Test
    void fromSchemaTypeMapsAndRejects() {
        assertThat(KeyType.fromSchemaType("Integer")).isEqualTo(KeyType.INTEGER);
        assertThat(KeyType.fromSchemaType("String")).isEqualTo(KeyType.STRING);
        assertThatThrownBy(() -> KeyType.fromSchemaType("Date"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
