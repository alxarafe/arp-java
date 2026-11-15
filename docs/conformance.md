# ARP-DICT-001-RUNTIME-002 Java acceptance evidence

Date: 2026-11-15. Branch: `feature/arp-dict-001-portability-002`.
Contract: `2dde1a09780e29c7385606c5e018fa75a0808b68` (recorded in `contract.ref`).
The implementation SHA is the local commit containing this report.

## Results

| Group | Passed | Failed | Not executed |
| --- | ---: | ---: | ---: |
| Base catalog | 1 | 0 | 0 |
| Values | 136 | 0 | 0 |
| Definitions | 36 | 0 | 0 |
| Sources | 40 | 0 | 0 |
| Source descriptor projections | 4 | 0 | 0 |
| Patterns | 45 | 0 | 0 |
| Matching samples | 21 | 0 | 0 |
| Catalog steps | 32 | 0 | 0 |
| Catalog descriptor projections | 12 | 0 | 0 |
| **Shared** | **327** | **0** | **0** |
| Java-specific | 28 | 0 | 0 |
| **Total JUnit** | **355** | **0** | **0** |

The runner consumes the pinned original manifest and fixture bytes directly.
Complete source projections are verified separately. JSON numbers compare by
exact BigDecimal mathematical equality; booleans and strings remain distinct.
The generated [machine summary](conformance-summary.json) records actual results.
No shared cases were excluded and expectations were not copied or regenerated.

The original 23 Java tests remain, including immutability, defensive copying,
concurrency, isolation, atomicity, Unicode, seven-digit UTC calendar bounds,
1 MiB/2048 elements/depth, security and exact numeric export. The formerly
unbounded exponent acceptance is updated explicitly to the common contract.
Four additional regression methods check padded/zero exponent attacks, exact
numeric token/magnitude boundaries, YAML extensions/JSON escapes, block chomping
and the runner's exact number/kind comparison. No public API changed.

## Reproduction and tooling

Actual environment: Eclipse Adoptium Java **25.0.4.1**, Maven **3.9.16**, Linux
amd64, local `maven:3.9-eclipse-temurin-25`. Dependencies unchanged: Jackson
2.18.2, SnakeYAML Engine 2.9 and test-only JUnit Jupiter 5.11.4.

```sh
docker run --rm --network none --user 1000:1000 -e MAVEN_CONFIG=/tmp \
  -v "$PWD:/work" -v /tmp/arp-java-m2-20261115:/m2:ro \
  -v /home/rsanjose/Desarrollo/Alxarafe/arp-contract:/contract:ro -w /work \
  maven:3.9-eclipse-temurin-25 \
  mvn -o -B -Dmaven.repo.local=/m2 -Darp.contract.path=/contract clean verify
```

Surefire reports and the local JAR are generated under ignored `target/`.
The contract-only checks were executed separately by the coordinating task:
`tools/check_contract.py` PASS (327 inventoried), and unittest **27/27** PASS.
These structural checks are separate from the 355 native JUnit cases.

## Changes and remaining observations

SourceParser adds raw JSON token preflight before Jackson numeric conversion,
YAML scalar token/style guards and an explicit ARP plain scalar resolver.
BigInteger/BigDecimal preserve admitted source values, numeric JSON kinds and
export precision. SnakeYAML remains only a safe syntax/block-text provider;
its extra scalar syntax and implicit type choices are not authority.
The public API, independent catalog ownership and validation architecture remain
as documented in [architecture](architecture.md).

Modified files: README.md, contract.ref, docs/conformance.md,
docs/conformance-summary.json, docs/contract-feedback.md, SourceParser.java,
ConformanceTest.java and RuntimeTest.java (source files under the existing
`org/alxarafe/arp/dictionary` main/test package paths).

No new unresolved contractual contradiction was found. The old feedback is
resolved by consolidation 002; see [historical feedback and resolution](contract-feedback.md).
No other repository was modified. No push, PR, tag, merge or package publication.

## Reverification from updated main (2026-11-15)

`git pull --ff-only origin main` confirmed main at `5c3ff2a`, which already
contains the runtime-002 implementation. The old feature branch `a143c61`
had the same tree but separate squash history. It was preserved locally as
`backup/arp-dict-001-portability-002-a143c61`; the requested feature branch was
then created from updated main. No history was discarded or published.

This work adds one independent Java regression in `RuntimeTest.java`: literal
and folded text as the first field of a compact sequence mapping, followed by
a plain date label. Both exact block content and the unchanged date string pass.
Production behavior already met this case; no API or parser change was needed.
The existing token/exponent/magnitude, exact numeric comparison, concurrency,
isolation, immutability, limits and security regressions all pass again.

Fresh `clean verify` completed at 2026-11-15 07:46:05 UTC without network:
327/327 original shared assertions (including four source projections),
28/28 Java-specific methods, 355 total, no failures/errors/skips. The generated
`target/conformance-summary.json` confirms the shared counts above. Only
`src/test/java/org/alxarafe/arp/dictionary/RuntimeTest.java` and this evidence
file changed relative to updated main. `git diff --check` passed.

The historical temporary Maven cache was absent, and the user cache lacked
SnakeYAML Engine 2.9. Pinned dependencies were downloaded into a fresh writable
`/tmp/arp-java-m2-20261115` cache; the final verification mounted it read-only
with `--network none`. Dependencies and build configuration did not change.
