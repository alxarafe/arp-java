package org.alxarafe.arp.dictionary;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record BuildResult(Optional<Catalog> catalog, List<Issue> issues) {
    public BuildResult {
        catalog = java.util.Objects.requireNonNull(catalog);
        issues = List.copyOf(issues);
        if (catalog.isPresent() == !issues.isEmpty()) throw new IllegalArgumentException("Catalog or issues required, exclusively");
    }
    public boolean valid() { return catalog.isPresent(); }
    public Map<String, Object> projection() { return Map.of("valid", valid(), "issues", issues.stream().map(Issue::projection).toList()); }
}
