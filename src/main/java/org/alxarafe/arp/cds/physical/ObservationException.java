package org.alxarafe.arp.cds.physical;

/**
 * Expected unavailable/incomplete observation. Never exposes driver diagnostics or credentials.
 */
public final class ObservationException extends Exception
{
    public ObservationException()
    {
        super("Physical observation unavailable");
    }
}