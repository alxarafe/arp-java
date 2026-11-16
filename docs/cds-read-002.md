# ARP-CDS-READ-002 — Pure read metadata resolver

Reference: `arp-contract/main` at `7a62bba0e8f84dbc198e837baf63fc7e0d8722e1`, recorded in `contract.ref`.
This implements the approved-for-implementation CDS-READ-001 subset. It is not a normative release claim.
The scalar RC remains `4c0f4bd0337c01c15a036910b6462e6f0e674927`; its fixtures and schemas are unchanged
at the newer reference. Historical scalar RC reports remain historical evidence, not results for this change.

## Architecture and scope

The compiler receives an already validated scalar `Catalog` and caller-registered physical, logical and projection
snapshots. It resolves metadata only: no row reads, LIST/GET execution, CRUD, writes, DICT-002 or services.
There is no discovery, connection, HTTP, SQL, ORM or persistence dependency. Existing demo hosts are unchanged.
The three authoring schemas are copied byte-for-byte from the reference and shipped as library resources; tests
compare them to the original files. This introduces no new authoring format. The small schema interpreter supports
only keywords used by these three pinned schemas; it is not a general-purpose JSON Schema API.

All bindings use the existing scalar catalog's descriptor resolution; existing portable parsers, exact arithmetic
and integer ranges are reused. CDS does not duplicate scalar value validators. Native names never prove portable
compatibility. Only inclusive integer interval containment is demonstrated; other dimensions remain `not-verified`.
A demonstrated incompatibility fails the complete snapshot without clipping a logical domain.

Physical/logical identities are independent. All physical columns survive into effective logical metadata;
projections select explicitly and derived projections cannot expand exposure or operations. Absent properties,
false, empty arrays/maps and explicit null remain distinct under the authoring schemas. Sorting uses Unicode
scalar order (valid UTF-8 byte order), not locale or UTF-16 ordinal order. Authored array order is retained.
Only the first diagnostic is returned in contractual phase order, including minimum before maximum.

## Inputs and outputs

Decoded input uses the existing fixture harness structure: physical/logical/projections maps keyed by stable trusted
handles. It must be an exact portable JSON graph, not arbitrary application objects. This is an in-process API,
not a newly published transport. The separate raw-source entry point counts documents first, parses in kind/handle
order and completes intake before resolving schemas/references. Standalone intake also accepts non-object JSON;
authoring shape validation belongs to resolution.

Portable intake budgets remain 1 MiB/document, depth 128, numeric token 256, absolute exponent 128 and magnitude
less than 10^128. Resolution adds 256 documents, 2048 columns/table, chain length 64 and integer-bound string
length 4096. Tests cover the boundary and its successor. Exact mathematical integers such as 1.0 and 1e127
are retained without binary floating-point conversion.

Successful results contain valid/issues/models/projections; failures contain only valid/issues. Snapshot outputs
are immutable or detached on access, including nested metadata. Old readers can retain their immutable result;
that does not mean it is still the current physical schema.

A separate local publication object allocates a generation and clears current state before work. Computation runs
outside publication; publishing checks generation and installs the whole successful result atomically. Failed work
leaves no current catalog. Explicit invalidation clears current state; stale work reports CDS_READ_SNAPSHOT_CHANGED
at /snapshot and cannot replace newer work. Hosts can report a real observation failure with
CDS_READ_OBSERVATION_FAILED; programming errors are never reclassified as malformed user definitions.
There is no SQL observer, distributed cache, automatic DDL detection, persistence or cross-process generation token.

## Public API and language-specific representation

Package: `org.alxarafe.arp.dictionary`, in the existing library artifact.

- `CdsReadResolver.parse(Source)` returns an immutable map with document or one issue.
- `resolve(Map<String,Object>, Catalog)` compiles decoded maps/lists; `resolveSources(...)` accepts nested maps
  of existing immutable Source instances. Exact numbers use BigInteger/BigDecimal; binary floats are rejected
  at the decoded graph boundary.
- `CdsReadResult.valid()`, `projection()` and `toJson()` expose a recursively immutable result.
  `failure(codeSuffix, at, family)` constructs an explicit host failure.
- `CdsReadPublication.beginGeneration()`, `invalidate()`, `publish(ticket,result)` and `current()` synchronize
  publication per instance. A real worker/latch regression covers invalidation during computation.

Internal Numbers.integerRange returns a detached copy of scalar bounds; no old public API changes.
Schemas are classpath resources, so deployment does not require a contract checkout or schema filesystem path.

## Reproducible validation

Use a separate clean checkout of the exact contract revision (do not change the shared contract working tree):

```sh
git clone https://github.com/alxarafe/arp-contract.git /tmp/arp-cds-read-reference
git -C /tmp/arp-cds-read-reference checkout --detach 7a62bba0e8f84dbc198e837baf63fc7e0d8722e1
```

With the existing Java 25 / Maven toolchain:

```sh
mvn -B -Darp.contract.path=/tmp/arp-cds-read-reference test
git diff --check
```

This run used a disposable Maven Java 25 container with existing demo-image dependencies. No host HTTP process,
port binding or external database was used. No formatter is configured in pom.xml; new source layout follows
.editorconfig and changed files are checked for whitespace and line length.

Tests consume the original contract fixtures directly. Each stack independently compares all 80 resolution cases
and 11 intake cases to their exact expected structures, with numbers compared mathematically and booleans kept
distinct. The 80 resolutions also run with recursively reversed object-member order while preserving arrays.
Additional checks cover input/output immutability, catalog isolation, raw intake before schema checks, pinned
schemas, source/column/chain budgets and deterministic bound error precedence.

Observed: **451 tests passed**, zero failures/errors/skips: 327 original shared scalar assertions,
28 previous native regressions and 96 CDS tests (80 resolution + 11 intake + 5 integrity tests).

No PostgreSQL/MariaDB, observation provider, HTTP or Angular integration is exercised by these tests. The ten
real-database executions listed in the contract remain a later adapter task. Passing this subset does not establish
general CDS or published normative conformance. No contractual ambiguity blocking this subset was found;
broader contextual constraints, row execution and distributed invalidation remain outside its approved scope.

## Review state

Branch: `feature/arp-cds-read-002`. Prepared for integration through an independent reviewed PR.
No merge, release, tag or package publication is authorized by this closure.
No changes to arp-contract or previous public scalar APIs. No new runtime dependencies.