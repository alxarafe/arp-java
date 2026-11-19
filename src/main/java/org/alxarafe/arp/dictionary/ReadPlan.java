package org.alxarafe.arp.dictionary;

import java.util.List;
import java.util.Map;

/**
 * Immutable logical read values. A prepared source is bound to the reader that created it.
 */
public record ReadPlan(Prepared source, List<String> position, boolean get, int limit)
{
    public ReadPlan
    {
        position = position == null ? null : List.copyOf(position);
    }

    public record Column(String name, ScalarType type, boolean nullable)
    {
    }

    public record Row(List<String> key, Map<String, Object> values)
    {
        @SuppressWarnings("unchecked")
        public Row
        {
            key = List.copyOf(key);
            values = (Map<String, Object>) CdsReadResult.freeze(values);
        }
    }

    public abstract static class Prepared
    {
        private final List<Column> columns;
        private final List<Column> keys;
        private final Map<String, Object> equalities;

        protected Prepared(List<Column> columns, List<Column> keys, Map<String, Object> equalities)
        {
            this.columns = List.copyOf(columns);
            this.keys = List.copyOf(keys);
            this.equalities = Map.copyOf(equalities);
        }

        public final List<Column> columns()
        {
            return columns;
        }

        public final List<Column> keys()
        {
            return keys;
        }

        public final Map<String, Object> equalities()
        {
            return equalities;
        }
    }
}