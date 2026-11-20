package org.alxarafe.arp.dictionary;

import java.util.Optional;

/**
 * Local generation boundary. Computation can run outside the lock; publication is atomic.
 */
public final class CdsReadPublication
{
    private long generation;
    private CdsReadResult current;

    public synchronized long generation()
    {
        return generation;
    }

    public synchronized long beginGeneration()
    {
        current = null;
        return ++generation;
    }

    public synchronized void invalidate()
    {
        beginGeneration();
    }

    public synchronized Optional<CdsReadResult> current()
    {
        return Optional.ofNullable(current);
    }

    public synchronized CdsReadResult publish(long ticket, CdsReadResult result)
    {
        if (ticket != generation) {
            return CdsReadResult.failure("SNAPSHOT_CHANGED", "/snapshot", "execution-failure");
        }
        current = result.valid() ? result : null;
        return result;
    }
}
