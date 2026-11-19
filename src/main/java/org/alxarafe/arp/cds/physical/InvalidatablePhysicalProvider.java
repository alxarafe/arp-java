package org.alxarafe.arp.cds.physical;

public interface InvalidatablePhysicalProvider
{
    void invalidate() throws ObservationException;
}