package org.alxarafe.arp.dictionary;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Neutral catalog port. Publication uses the same fence as catalog invalidation.
 */
public interface ReadCatalog
{
    CdsReadResult resolve();

    Optional<CdsReadResult> current();

    long generation();

    <T> T publishRead(CdsReadResult expected, Supplier<T> publish);
}