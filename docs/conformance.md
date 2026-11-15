# ARP-DICT-001 v0.1.0 RC1 — Java acceptance evidence

Date: 2026-11-15. Branch: `release/arp-dict-001-v0.1.0-rc1`.
Contract repository: `alxarafe/arp-contract`.
Annotated remote tag: `v0.1.0-rc.1`.
Tag object: `192dc1ce0ceab1a2b2f4545a4e327cde2e0fc5d5`.
Peeled commit / strict runner pin / `contract.ref`:
`4c0f4bd0337c01c15a036910b6462e6f0e674927`.

Updated main and implementation base: `3a14a5ea1460ab6caaf634dc538292cefcb74c81`.
The local acceptance commit is the commit containing this report (obtain it with
`git rev-parse HEAD`). No production source, public API, dependencies or fixture
expectations changed. This work updates contract identity, strict input checks
and evidence only. Earlier portability implementation reports remain in Git history.

## Authority and verification

`git ls-remote --tags origin 'v0.1.0-rc.1*'` in the contract repository returned
both the tag object and its peeled commit above. In an independent temporary
clone, `git cat-file -t 192dc1ce0ceab1a2b2f4545a4e327cde2e0fc5d5` returned
`tag`, and `git cat-file -p` confirmed its target type `commit`, exact SHA and tag
name. Thus the remote reference is annotated and targets the required commit.
`arp-contract` was not changed; the tests read `/tmp/arp-java-contract-rc1`, a
separate detached clone with clean tree and exact HEAD. Fixtures, schema and
normative feature contents are unchanged from portability consolidation 002.

The runner requires the exact checkout HEAD and matching `contract.ref`, and
checks tracked input changes against HEAD for fixtures, schemas and all three
normative feature/portability/checks documents. It processes the original manifest
without exclusions or expectation changes. Numbers compare mathematically with
BigDecimal; booleans and strings remain distinct. Complete projections include
all four source descriptors.

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
| Contract tool regression methods | 27 | 0 | 0 |

Contract structural/schema/fixture integrity: PASS, 327 runtime assertions
inventoried. These structural checks are separate from the native runtime passes.
The unchanged Java regressions cover concurrency, isolation, immutable snapshots,
atomic generation behavior, exact numeric limits/export, byte/element/depth limits,
Unicode and security, including compact YAML blocks followed by date text.
The generated [machine summary](conformance-summary.json) records the actual RC run.
`git diff --check` passed.

## Environment and reproduction

Linux amd64; Docker image `maven:3.9-eclipse-temurin-25`, Maven 3.9.16,
Eclipse Adoptium Java 25.0.4.1. Jackson 2.18.2, SnakeYAML Engine 2.9,
JUnit Jupiter 5.11.4. Contract tools: Python 3.14.4, jsonschema 4.19.2,
PyYAML 6.0.3 (matching pinned requirements). Final native build completed
2026-11-15 08:19:18 UTC with 355 tests, no failures, errors or skips.

```sh
git clone --no-hardlinks --no-checkout /home/rsanjose/Desarrollo/Alxarafe/arp-contract /tmp/arp-java-contract-rc1
git -C /tmp/arp-java-contract-rc1 checkout --detach 4c0f4bd0337c01c15a036910b6462e6f0e674927

docker run --rm --network none --user 1000:1000 -e MAVEN_CONFIG=/tmp \
  -v "$PWD:/work" -v /tmp/arp-java-m2-20261115:/m2:ro \
  -v /tmp/arp-java-contract-rc1:/contract:ro -w /work \
  maven:3.9-eclipse-temurin-25 \
  mvn -o -B -Dmaven.repo.local=/m2 -Darp.contract.path=/contract clean verify

cd /tmp/arp-java-contract-rc1
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_contract.py
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -v
```

The Maven cache was already populated for these pinned dependencies. No network
was used by the final native tests or contract checks. Generated JAR and Surefire
reports stay under ignored `target/`; no package was published.

## Scope and Git state

Changed files: `README.md`, `contract.ref`, `docs/conformance.md`,
`docs/conformance-summary.json`, and
`src/test/java/org/alxarafe/arp/dictionary/ConformanceTest.java`.
Only `arp-java` was modified. Main was clean before its fast-forward update;
the RC branch was created from updated main. No contract incompatibility was
found. The required delivery is a local commit with clean tree; no push, PR,
merge, runtime tag or package publication was performed.
