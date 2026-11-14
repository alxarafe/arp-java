package org.alxarafe.arp.dictionary;

public enum ScalarType {
    BOOLEAN, STRING, TEXT, INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64,
    DECIMAL, UUID, DATE, TIME, DATETIME, ENUM, BINARY;

    boolean integer() { return name().startsWith("INT") || name().startsWith("UINT"); }
    boolean numeric() { return integer() || this == DECIMAL; }
    boolean text() { return this == STRING || this == TEXT || this == ENUM; }
}
