# ARP-DICT-001-JAVA-001 acceptance evidence

Date: 2026-11-14. Branch: `feature/arp-dict-001-java`.
Contract commit: `0172ab64742218b9f64a6ae96c43ebdfe690f4c6`.
Feature/format: ARP-DICT-001 / `arp.dictionary/v1`.
The implementation commit is the Git commit containing this report; the delivery
identifies its SHA. This is an unpublished contract candidate, not adoption of a
published release. No PHP output was used as an expected outcome.

## Actual functional results

| Original fixture group | Passed | Failed | Not executed |
| --- | ---: | ---: | ---: |
| Baseline 17-type catalog | 1 | 0 | 0 |
| Values / normalization | 132 | 0 | 0 |
| Definitions | 34 | 0 | 0 |
| Sources / registration | 21 | 0 | 0 |
| Pattern grammar | 45 | 0 | 0 |
| Pattern matching samples | 21 | 0 | 0 |
| Catalog steps (13 scenarios) | 32 | 0 | 0 |
| Complete descriptor projections | 12 | 0 | 0 |
| **Shared dynamic JUnit cases** | **298** | **0** | **0** |
| Java-specific runtime tests | 23 | 0 | 0 |
| **Maven/JUnit total** | **321** | **0** | **0** |

The manifest and all referenced sets are consumed directly from the original
pinned checkout. Expected outputs are neither copied nor regenerated from an
implementation. Values compare complete outcomes, ordered issues, absence of a
normalized value on rejection and exact JSON model projections. Definition/source
diagnostic identities are excluded when the original expectation omits them.
Unpaired-surrogate and invalid-byte fixtures reach their intended boundaries.

`RuntimeTest` separately exercises:

- Defensive byte copying and deep immutability of descriptor/result collections.
- Atomic rejection, independent contexts and sorted IDs without discovery.
- 32 simultaneous builds with repeated validation, and eight readers observing
  complete snapshots while 100 replacements and failed duplicate builds execute.
- Acceptance of exactly 1 MiB and 2048 definitions; rejection above configured
  byte/element/depth budgets for both source encodings.
- Null/escaped duplicate keys; tags, anchors, aliases, merges, unknown/recognized
  directives, multiple documents, malformed Unicode and external-reference syntax.
- Literal block text with dangerous-looking symbols, YAML/JSON exact equivalence,
  YAML 1.2 scalar resolution, large integer metadata and integral exponent export.
- All ASCII controls in pattern contexts, bounded ambiguous repetitions, exact
  seven-digit UTC normalization under several system timezones and UTC year bounds.

## Environment and reproducibility

Actual runtime: Eclipse Adoptium Java **25.0.4.1**, Maven **3.9.16**, Linux amd64,
using the local `maven:3.9-eclipse-temurin-25` image. The host has Java 25.0.3 and
no Maven; no host Maven installation or alternative Java release was used.
Initial dependency acquisition used network access; acceptance runs use the local
Maven cache, Docker `--network none`, Maven `-o` and a read-only contract mount.

Reproduce from this repository:

```sh
docker run --rm --network none --user "$(id -u):$(id -g)" -e MAVEN_CONFIG=/tmp \
  -v "$PWD:/work" -v /tmp/arp-java-m2:/m2 \
  -v /tmp/arp-contract-main:/contract:ro -w /work \
  maven:3.9-eclipse-temurin-25 \
  mvn -o -B -Dmaven.repo.local=/m2 -Darp.contract.path=/contract clean verify

bash scripts/check-contract.sh /tmp/arp-contract-main
```

Surefire XML/text reports are generated in `target/surefire-reports/`. The runner
writes `target/conformance-summary.json`; [the recorded summary](conformance-summary.json)
is captured from that output, not from expected data. The library JAR is built
locally under `target/`; nothing is installed to a remote repository or published.

Runtime dependencies: Jackson Databind/Core/Annotations 2.18.2 and SnakeYAML
Engine 2.9. Test-only dependency: JUnit Jupiter 5.11.4. No application framework.

## Separate structural controls

The original contract's schema/metaschema and fixture-integrity checker passes.
Its **12 checker regression tests pass**, separately from the 321 Java tests.
Its printed `NOT EXECUTED: semantic conformance` line describes the checker itself;
it does not describe the Java suite executed here.

The structural script verifies the same contract SHA and disables Python bytecode
writes. `.openidea/` remains ignored; `git diff --check` is part of final review.
No tracked files in `arp-contract`, `arp-php` or other repositories were changed.

## Limits of this evidence

No shared fixture was skipped. The vectors are representative, not an exhaustive
proof of all possible inputs. The configurable 128-level source nesting budget is
an implementation resource ceiling; shared budgets and YAML spelling coverage
are proposed for review in [contract feedback](contract-feedback.md).

ERP integration, persistence, CRUD, presentation, cross-language native API
comparison and adoption of a published contract release were not executed: these
are outside scope. No support for Java versions other than 25 is claimed. No
push, PR, tag, merge, release or publication is part of this delivery.

## Files created or modified

- `README.md` (modified).
- `contract.ref` (new).
- `docs/architecture.md` (new).
- `docs/conformance-summary.json` (new).
- `docs/conformance.md` (new).
- `docs/contract-feedback.md` (new).
- `pom.xml` (new).
- `scripts/check-contract.sh` (new).
- `src/main/java/org/alxarafe/arp/dictionary/BuildResult.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Catalog.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/CatalogBuilder.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Code.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/DefinitionParser.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Descriptor.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Issue.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/JsonSupport.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Limits.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Numbers.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/PortablePattern.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Provenance.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Rejected.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Resolution.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/ScalarType.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/ScalarValidator.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Source.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/SourceParser.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/Unicode.java` (new).
- `src/main/java/org/alxarafe/arp/dictionary/ValidationResult.java` (new).
- `src/test/java/org/alxarafe/arp/dictionary/ConformanceTest.java` (new).
- `src/test/java/org/alxarafe/arp/dictionary/RuntimeTest.java` (new).
