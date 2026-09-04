package com.database.atypon.Node.index;

/** The four indexable field types and their fixed value widths in bytes. */
public enum KeyType {
    INTEGER(4), DOUBLE(8), BOOLEAN(1), STRING(64);

    private final int valueWidth;

    KeyType(int valueWidth) { this.valueWidth = valueWidth; }

    public int valueWidth() { return valueWidth; }

    public static KeyType fromSchemaType(String schemaType) {
        switch (schemaType) {
            case "Integer": return INTEGER;
            case "Double":  return DOUBLE;
            case "Boolean": return BOOLEAN;
            case "String":  return STRING;
            default: throw new IllegalArgumentException("Unsupported index key type: " + schemaType);
        }
    }
}
