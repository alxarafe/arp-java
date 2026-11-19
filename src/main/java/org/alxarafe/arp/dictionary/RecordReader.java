package org.alxarafe.arp.dictionary;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Storage-neutral planning and protected reading. No provider or transport types cross this port.
 */
public interface RecordReader
{
    ReadPlan.Prepared prepare(CdsReadResult snapshot, String projection, Map<String, Object> equalities);

    ReadAttempt open(ReadPlan plan, BooleanSupplier current);
}