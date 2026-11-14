# ARP Java scalar dictionary

Standalone Java 25 / Maven library for the approved **ARP-DICT-001** candidate,
format `arp.dictionary/v1`. It implements the 17 scalar types, exact validation
and normalization, explicit JSON/YAML source registration and immutable catalogs.
No Spring, application framework, ERP, database, HTTP or persistence is required.

Contract authority: `alxarafe/arp-contract` commit
`0172ab64742218b9f64a6ae96c43ebdfe690f4c6`, recorded in `contract.ref`.
The contract has not been released; these results concern that exact candidate.
PHP output is neither an oracle nor a source of expected test results.

## Public API

```java
import org.alxarafe.arp.dictionary.*;
import java.util.List;

Source source = Source.utf8("application", "dictionary:finance", Source.Encoding.JSON, """
    {"apiVersion":"arp.dictionary/v1","elements":[
      {"id":"finance.amount","type":"DECIMAL","constraints":{"precision":18,"scale":2}}
    ]}
    """);

BuildResult build = new CatalogBuilder().buildCatalog(List.of(source));
if (!build.valid()) {
    List<Issue> issues = build.issues(); // includes trusted provenance
    return;
}
Catalog catalog = build.catalog().orElseThrow();
Resolution resolved = catalog.resolve("finance.amount");
Descriptor descriptor = resolved.descriptor().orElseThrow();
ValidationResult result = catalog.validate("finance.amount", "1.2300");
// result.valid() == true
// result.normalizedValue().orElseThrow().equals("1.23")
```

The public package is `org.alxarafe.arp.dictionary`:

- `Source`: defensive byte snapshot with opaque provider/source identities and an
  explicit encoding. `Source.utf8(...)` is a convenience for valid Java text;
  malformed input bytes can be supplied to its byte-array constructor.
- `CatalogBuilder.buildCatalog(Iterable<Source>)`: returns a typed `BuildResult`.
  A failed build contains issues and **no partial catalog**. The builder is
  reusable concurrently and maintains no active registry.
- `Catalog.resolve(String)`: typed `Resolution` with a descriptor or not-found
  issue. IDs are exact, case-sensitive and have no fallback namespace.
- `Catalog.validate(String, Object)`: accepts the neutral portable codec: Boolean
  for BOOLEAN and String for other types, including numeric values. No coercion,
  float conversion, rounding, trimming or implicit Unicode normalization.
- `ValidationResult`: Optional normalized value on success, or ordered immutable
  issues on failure. `projection()` omits `normalizedValue` on failure.
- `Descriptor`: immutable constraints, ordered ENUM literals, optional texts and
  `projection()` / `toJson()` for the complete contractual descriptor. Integer
  metadata uses `BigInteger`; integral decimal number spellings may remain
  `BigDecimal`. JSON export preserves exact numeric values and number kinds.
- `Catalog.ids()` returns an immutable sorted list; `provenance(id)` exposes
  diagnostics separately. `Issue` exposes contract `Code` and optional nullable
  element/constraint/provenance fields, with a portable `projection()`.

Applications own catalog contexts and publication. Build the replacement first,
then replace their own reference (for example an `AtomicReference<Catalog>`)
only after success. Existing catalog instances remain valid and immutable after
any later build. The library has no tenant-global caches or hidden discovery.

## Parsing, arithmetic and limits

Runtime dependencies are **Jackson Databind 2.18.2** (with Jackson Core and
Annotations transitively) and **SnakeYAML Engine 2.9**. JUnit Jupiter 5.11.4 is
used only in tests. Dependency and Maven plugin versions are explicit in `pom.xml`.
Neither runtime parser brings an application framework.

JSON uses duplicate detection and arbitrary-precision numeric decoding. UTF-8 is
decoded strictly, and all parsed strings/keys are checked for unpaired surrogates.
Trailing roots are rejected. YAML uses the engine's YAML 1.2 JSON schema;
`yes/no/on/off` and plain dates remain strings. Block/flow collections and quoted,
plain, literal and folded scalars are supported. Both token and event passes
reject directives (including unknown ones), tags, anchors, aliases and multiple
documents. Node conversion rejects duplicate keys, merge keys and non-string
mapping keys. No YAML object construction, environment expansion, custom tag
handler or external reference resolution is used.

Scalar numeric grammar is checked before exact `BigDecimal` arithmetic; no
`float` or `double` is involved. Portable patterns have a dedicated formal parser
and bounded position-set matcher rather than host regex backtracking semantics.
Temporal values use strict Gregorian parsing and explicit offset arithmetic,
with all seven fractional digits preserved and no default timezone dependency.
Base64 must decode and re-encode identically.

Default per-source budgets are 1 MiB UTF-8 bytes, 2048 elements and 128 collection
nesting levels, configurable through `Limits`. Depth is checked before recursive
YAML node conversion. The fixed contractual limits remain 4096 numeric digits,
512 pattern characters and 4096 code points for pattern inputs. There is no
additional 4096-exponent limit for metadata: integral `1e10000` remains exact,
without expanding it into a large integer just to compare a length.

## Build and tests

Use Java 25 and Maven 3.9. Create an independent contract checkout at the pinned
commit, or point to an existing clean checkout:

```sh
mvn -Darp.contract.path=/path/to/arp-contract clean verify
bash scripts/check-contract.sh /path/to/arp-contract
```

`ConformanceTest` reads the original manifest and all fixture sets directly.
Nothing under `fixtures/` is copied into this library. It verifies the contract
SHA and rejects edited tracked inputs. It uses the original source bytes,
including invalid UTF-8, and retains the deliberately unpaired surrogate value
in Java until validation. Unknown manifest groups/operations fail explicitly.
JUnit reports every dynamic case; `target/conformance-summary.json` records
per-group passed/failed/not-executed counts.

`RuntimeTest` separately covers defensive copying, deep immutability, concurrent
builds and readers, independent contexts, failed generation publication, parser
security, exact export and configurable resource boundaries.

Structural/schema/integrity checks run separately through the contract's own
Python tools and their dependencies in `tools/requirements.txt`; their success
does not stand in for Java semantic conformance.

Where Maven is unavailable on the host, the local Maven/Java 25 image can be used
without changing any other repository:

```sh
mkdir -p /tmp/arp-java-m2
docker run --rm --user "$(id -u):$(id -g)" -e MAVEN_CONFIG=/tmp \
  -v "$PWD:/work" -v /tmp/arp-java-m2:/m2 \
  -v /path/to/arp-contract:/contract:ro -w /work \
  maven:3.9-eclipse-temurin-25 \
  mvn -B -Dmaven.repo.local=/m2 -Darp.contract.path=/contract clean verify
```

After dependencies are cached, add Docker `--network none` and Maven `-o` to run
the same verification offline. No application services are started.

See [architecture](docs/architecture.md), [conformance evidence](docs/conformance.md)
and [contract feedback](docs/contract-feedback.md).
