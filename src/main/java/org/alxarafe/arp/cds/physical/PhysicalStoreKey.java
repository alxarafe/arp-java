package org.alxarafe.arp.cds.physical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Connection/application are non-secret stable host aliases, not credentials or connection strings.
 */
public record PhysicalStoreKey(String application, String connection, String provider, String physicalId,
        String dialect, String schema, String table) {
    public PhysicalStoreKey {
        for (String id : List.of(application, connection, provider, physicalId)) {
            if (!id.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                throw new IllegalArgumentException("Use non-secret registered aliases, not connection strings");
            }
        }
        if (!List.of("postgresql", "mariadb").contains(dialect) || schema.isEmpty() || table.isEmpty()
                || schema.indexOf('\0') >= 0 || table.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid physical identity");
        }
    }

    public String hash()
    {
        try {
            return digest(new ObjectMapper().writeValueAsBytes(
                    List.of(application, connection, provider, physicalId, dialect, schema, table)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String digest(byte[] bytes)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
