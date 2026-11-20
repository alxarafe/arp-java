package org.alxarafe.arp.cds.physical;

import java.util.Map;

/**
 * A caller-registered table; implementations own fresh connections and contain no scalar business rules.
 */
public interface PhysicalObservationProvider
{
    String providerId();

    Map<String, Object> observe() throws ObservationException;
}
