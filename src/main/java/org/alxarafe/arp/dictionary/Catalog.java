package org.alxarafe.arp.dictionary;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable snapshot, safe for concurrent resolution and validation without locks. */
public final class Catalog {
    private final Map<String, Descriptor> elements;
    private final Map<String, Provenance> provenance;

    Catalog(Map<String, Descriptor> elements, Map<String, Provenance> provenance) {
        this.elements = Collections.unmodifiableSortedMap(new TreeMap<>(elements));
        this.provenance = Map.copyOf(provenance);
    }

    public List<String> ids() { return List.copyOf(elements.keySet()); }
    public Optional<Provenance> provenance(String id) { return Optional.ofNullable(provenance.get(java.util.Objects.requireNonNull(id))); }
    public Resolution resolve(String id) {
        var descriptor = elements.get(java.util.Objects.requireNonNull(id));
        return descriptor == null ? new Resolution(Optional.empty(), missing(id)) : new Resolution(Optional.of(descriptor), List.of());
    }
    public ValidationResult validate(String id, Object portableValue) {
        var descriptor = elements.get(java.util.Objects.requireNonNull(id));
        return descriptor == null ? ValidationResult.failure(missing(id)) : ScalarValidator.validate(descriptor, portableValue);
    }
    private static List<Issue> missing(String id) { return List.of(new Issue(Code.DICTIONARY_ELEMENT_NOT_FOUND, id, null, null)); }
}
