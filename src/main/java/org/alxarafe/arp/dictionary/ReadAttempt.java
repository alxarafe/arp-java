package org.alxarafe.arp.dictionary;

import java.util.List;

/**
 * An unpublished, complete batch. The physical publication guard lives until mandatory close succeeds.
 */
public interface ReadAttempt extends AutoCloseable
{
    List<ReadPlan.Row> rows();

    /**
     * Mandatory data completion; must not release the independent publication guard.
     */
    void complete();

    /**
     * Mandatory checked cleanup. A failure must discard any privately accepted response.
     */
    @Override
    void close();
}