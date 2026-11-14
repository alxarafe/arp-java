package org.alxarafe.arp.dictionary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Stateless builder; each invocation has its own parser and unpublished working state. */
public final class CatalogBuilder {
    private final Limits limits;
    public CatalogBuilder() { this(Limits.DEFAULT); }
    public CatalogBuilder(Limits limits) { this.limits = java.util.Objects.requireNonNull(limits); }
    public Limits limits() { return limits; }

    public BuildResult buildCatalog(Iterable<Source> activeSources) {
        java.util.Objects.requireNonNull(activeSources);
        var parser = new SourceParser(limits);
        var elements = new LinkedHashMap<String, Descriptor>();
        var provenance = new LinkedHashMap<String, Provenance>();
        for (Source source : activeSources) {
            java.util.Objects.requireNonNull(source);
            String id = null, path = "";
            try {
                Object parsed = parser.parse(source);
                if (!(parsed instanceof Map<?, ?> document)) throw Rejected.document();
                if (!"arp.dictionary/v1".equals(document.get("apiVersion"))) throw new Rejected(Code.DICTIONARY_FORMAT_UNSUPPORTED);
                if (!document.keySet().equals(Set.of("apiVersion", "elements"))
                        || !(document.get("elements") instanceof List<?> definitions) || definitions.isEmpty()) throw Rejected.document();
                if (definitions.size() > limits.maxElementsPerSource()) throw Rejected.limit();
                for (int i = 0; i < definitions.size(); i++) {
                    path = "/elements/" + i; id = null;
                    if (!(definitions.get(i) instanceof Map<?, ?> definition)) throw Rejected.document();
                    if (definition.get("id") instanceof String elementId) id = elementId;
                    var descriptor = DefinitionParser.parse(definition);
                    if (elements.putIfAbsent(descriptor.id(), descriptor) != null) throw new Rejected(Code.DICTIONARY_DUPLICATE_ELEMENT);
                    provenance.put(descriptor.id(), new Provenance(source.providerId(), source.sourceId(), path));
                }
            } catch (Rejected rejected) {
                var issue = new Issue(rejected.code, id, null, new Provenance(source.providerId(), source.sourceId(), path));
                return new BuildResult(Optional.empty(), List.of(issue));
            }
        }
        return new BuildResult(Optional.of(new Catalog(elements, provenance)), List.of());
    }
}
