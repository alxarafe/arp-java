package org.alxarafe.arp.cds.physical;

import java.util.Map;

/**
 * Optional decorator; only the existing provider may introspect the database.
 */
public final class PersistedPhysicalProvider implements PhysicalObservationProvider, InvalidatablePhysicalProvider
{
    private final PhysicalObservationProvider inner;
    private final FilePhysicalStore store;
    private final PhysicalStoreKey key;

    public PersistedPhysicalProvider(PhysicalObservationProvider inner, FilePhysicalStore store, PhysicalStoreKey key)
    {
        this.inner = inner;
        this.store = store;
        this.key = key;
    }

    public String providerId()
    {
        return inner.providerId();
    }

    public Map<String, Object> observe() throws ObservationException
    {
        if (!inner.providerId().equals(key.provider())) {
            throw new ObservationException();
        }
        return store.get(key, inner::observe);
    }

    public void invalidate() throws ObservationException
    {
        store.invalidate(key);
    }
}
