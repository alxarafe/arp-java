package org.alxarafe.arp.dictionary;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ValidationResult(Optional<Object> normalizedValue, List<Issue> issues) {
    public ValidationResult {
        normalizedValue = java.util.Objects.requireNonNull(normalizedValue);
        issues = List.copyOf(issues);
        if (normalizedValue.isPresent() == !issues.isEmpty()) throw new IllegalArgumentException("Value or issues required, exclusively");
        if (normalizedValue.isPresent() && !(normalizedValue.get() instanceof String || normalizedValue.get() instanceof Boolean))
            throw new IllegalArgumentException("Portable success must be a string or boolean");
    }
    public boolean valid() { return issues.isEmpty(); }
    public Map<String, Object> projection() {
        return normalizedValue.<Map<String, Object>>map(value -> Map.of("valid", true, "normalizedValue", value, "issues", List.of()))
                .orElseGet(() -> Map.of("valid", false, "issues", issues.stream().map(Issue::projection).toList()));
    }
    static ValidationResult success(Object value) { return new ValidationResult(Optional.of(value), List.of()); }
    static ValidationResult failure(List<Issue> issues) { return new ValidationResult(Optional.empty(), issues); }
}
