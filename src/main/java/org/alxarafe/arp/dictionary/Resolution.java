package org.alxarafe.arp.dictionary;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record Resolution(Optional<Descriptor> descriptor, List<Issue> issues) {
    public Resolution {
        descriptor = java.util.Objects.requireNonNull(descriptor);
        issues = List.copyOf(issues);
        if (descriptor.isPresent() == !issues.isEmpty()) throw new IllegalArgumentException("Descriptor or issues required, exclusively");
    }
    public boolean valid() { return descriptor.isPresent(); }
    public Map<String, Object> projection() { return Map.of("valid", valid(), "issues", issues.stream().map(Issue::projection).toList()); }
}
