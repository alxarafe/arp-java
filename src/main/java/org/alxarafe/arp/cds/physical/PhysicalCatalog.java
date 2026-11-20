package org.alxarafe.arp.cds.physical;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.alxarafe.arp.dictionary.Catalog;
import org.alxarafe.arp.dictionary.CdsReadPublication;
import org.alxarafe.arp.dictionary.CdsReadResolver;
import org.alxarafe.arp.dictionary.CdsReadResult;

/**
 * Lazy local catalog; definition bytes are owned and observers run outside the publication lock.
 */
public final class PhysicalCatalog implements org.alxarafe.arp.dictionary.ReadCatalog
{
    private final Object resolveGate = new Object();
    private final CdsReadPublication publication = new CdsReadPublication();
    private final CdsReadResolver resolver = new CdsReadResolver();
    private final Catalog dictionary;
    private final byte[] definitions;
    private final Map<String, PhysicalObservationProvider> providers;
    private final ObjectMapper json = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public PhysicalCatalog(
            Catalog dictionary, Map<String, Object> definitions,
            Map<String, PhysicalObservationProvider> providers)
    {
        this.dictionary = Objects.requireNonNull(dictionary);
        try {
            this.definitions = json.writeValueAsBytes(definitions);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Definitions must be a portable JSON graph", e);
        }
        if (providers.size() > 256) {
            throw new IllegalArgumentException("Too many registered providers");
        }
        var sorted = new java.util.TreeMap<String, PhysicalObservationProvider>((a, b) -> Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
        sorted.putAll(providers);
        this.providers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private String blocked;

    public synchronized void invalidate()
    {
        publication.invalidate();
        blocked = null;
        for (var provider : providers.values()) {
            if (provider instanceof InvalidatablePhysicalProvider persisted) {
                try {
                    persisted.invalidate();
                } catch (ObservationException e) {
                    if (blocked == null) {
                        blocked = provider.providerId();
                    }
                }
            }
        }
        if (blocked != null) {
            throw new IllegalStateException("Physical invalidation unavailable");
        }
    }

    public synchronized Optional<CdsReadResult> current()
    {
        return publication.current();
    }

    public synchronized long generation()
    {
        return publication.generation();
    }

    /**
     * The caller must coordinate host policy changes using its own publication gate.
     */
    public synchronized <T> T publishRead(CdsReadResult expected, java.util.function.Supplier<T> publish)
    {
        if (expected == null || publication.current().orElse(null) != expected) {
            throw new IllegalStateException("Read generation changed");
        }
        return publish.get();
    }

    public CdsReadResult resolve()
    {
        synchronized (resolveGate) {
            return resolveGeneration();
        }
    }

    @SuppressWarnings("unchecked")
    private CdsReadResult resolveGeneration()
    {
        long ticket;
        synchronized (this) {
            if (publication.current().isPresent()) {
                return publication.current().orElseThrow();
            }
            if (blocked != null) {
                return CdsReadResult.failure("OBSERVATION_FAILED",
                        "/providers/" + blocked.replace("~", "~0").replace("/",
                                "~1"),
                        "execution-failure");
            }
            ticket = publication.beginGeneration();
        }
        Map<String, Object> bundle;
        try {
            bundle = json.readValue(definitions, Map.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Owned definitions cannot be decoded", e);
        }
        var physical = new LinkedHashMap<String, Object>();
        for (var entry : providers.entrySet()) {
            try {
                physical.put(entry.getKey(), entry.getValue().observe());
            } catch (ObservationException e) {
                String id = entry.getValue().providerId().replace("~", "~0").replace("/", "~1");
                return publication.publish(ticket,
                        CdsReadResult.failure("OBSERVATION_FAILED",
                                "/providers/" + id,
                                "execution-failure"));
            }
        }
        bundle.put("physical", physical);
        return publication.publish(ticket, resolver.resolve(bundle, dictionary));
    }
}
