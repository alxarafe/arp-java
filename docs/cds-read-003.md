# ARP-CDS-READ-003 — Physical observation provider

Reference: `arp-contract/main`, CDS-READ-001, pinned at
`7a62bba0e8f84dbc198e837baf63fc7e0d8722e1` by the existing `contract.ref`.
Neither the contract, its fixtures, the pure resolver nor the scalar APIs are modified.
This is implementation evidence for the approved read subset, not a normative release/conformance declaration.

## Architecture

The optional adapter receives a trusted, registered provider ID, independent physical ID, schema/table names and
an application-owned connection factory. It never discovers a connection from a client, HTTP request or Git URL.
The factory must supply a fresh connection/lease exclusively owned by one observation; no shared user transaction
may be supplied. Provider names, identifiers, credentials and timeouts are host configuration, not business rules.

A replaceable observation interface returns the existing `arp.physical/v1` representation. The provider-specific
implementation does metadata I/O; `PhysicalCatalog` only orchestrates observation and the existing pure resolver.
The original dictionary supplies its own validated scalar descriptors; no scalar validator is copied.

`PhysicalCatalog` owns an immutable definition snapshot and an immutable scalar catalog. Construction performs no
DB I/O. First resolve observes all registered tables in Unicode/UTF-8 handle order, builds the physical snapshot,
then compiles the logical models and explicit projections with CDS-READ-002. Success publishes the whole generation;
a later resolve reuses it. `invalidate` clears current state without observing or rewriting the overlay. Next use
regenerates everything. New physical columns remain absent from projections until explicitly selected.

Computation and I/O occur outside the generation lock/boundary. Invalidation during generation produces
`CDS_READ_SNAPSHOT_CHANGED` at `/snapshot` instead of publishing stale work. Expected driver, permission,
unavailable-table or incomplete-observation failures become `CDS_READ_OBSERVATION_FAILED` at `/providers/<id>`;
results contain no connection string, SQL, credentials or trace. A failure in any active provider prevents partial
publication, including when an earlier provider has already succeeded. Programming faults are not disguised as
invalid user definitions. Old readers may retain immutable snapshots, but they are not advertised as current.

Each table is protected against DDL changes while its observation is assembled. This is not a global schema snapshot
across connections/tables. External DDL is not automatically detected: the host must explicitly invalidate affected
catalogs. There is no distributed cache, SQL query executor, HTTP endpoint or ORM. Optional filesystem
persistence below adds a local per-key generation fence, not distributed in-memory catalog coherence.

## Verified and unknown facts

Native identifiers remain literal and separate from logical IDs; SQL identifiers are engine-quoted and lookup values
are bound parameters. Only registered objects are inspected. Types stay verbatim in `nativeType`; portable types
are emitted only for the deliberately narrow mapping below. Integer compatibility is still decided by the pure
resolver, never by a provider label or native type name heuristic.

Declared NOT NULL/PK, column order, native identity/generated flags, simple index keys and FK column order are
observed. Absence of PK/index/FK is exported as `[]` only after a complete observation. If an index cannot be fully
represented (partial/expression/prefix/etc.), the entire indexes property is omitted rather than claiming no indexes.
Foreign keys describe verified target identifiers and column names; no target rows are queried or expanded.

Native CHECK expressions are not parsed into a second constraint language. When they could restrict a field's
domain, portable type/range proof is withheld so dictionary bindings remain `not-verified`. Unknown nullability
is omitted too; an explicit declared NOT NULL remains a verified false. This deliberately sacrifices proof rather
than announcing compatibility from storage width alone. Compatible means only the integer containment proof of
the existing resolver; it never authorizes writes or proves unique/FK/row-policy satisfaction.

PostgreSQL 18.6 tested (catalog query uses PostgreSQL 12+ attributes; other versions are not certified here).
A READ COMMITTED, READ ONLY transaction acquires ACCESS SHARE before catalog inspection and retains it through
commit. Only ordinary/partitioned table relations are admitted. Domains remain opaque, including unknown domain
nullability unless the column declares NOT NULL. CHECK-affected domains withhold proof; constant/unreferenced
CHECKs conservatively affect every column. pg_catalog int2/int4/int8, varchar/bpchar/text and bool have narrow
portable mappings; other native types stay opaque. Partial, expression or invalid indexes make indexes unknown.
The SQL resource is byte-identical to .NET's provider query; neither reuses illustrative fixture observations.

Metadata locking follows [PostgreSQL explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html).

## Public implementation API and dependencies

Optional sources/resources live under `providers/postgresql` and enter the build only with the
`postgresql-provider` Maven profile. The default pure artifact and its dependency list remain unchanged.
`PhysicalObservationProvider`, `ObservationException`, `PostgreSqlObservationProvider`, `PhysicalCatalog` live in
`org.alxarafe.arp.cds.physical`. The factory returns a fresh JDBC connection/lease in auto-commit mode; the adapter
owns its transaction and closes it. Driver connection timeout is host configuration; statement timeout is explicit.

`PhysicalCatalog(dictionary, definitions, handleProviders)` exposes resolve, invalidate and current, using the
existing synchronized CdsReadPublication. BigInteger/BigDecimal and immutable result snapshots remain intact.
The profile uses build-helper-maven-plugin 3.6.1 for optional sources, and PostgreSQL JDBC 42.7.8 in test scope only.
A consuming host supplies its own driver; no pool, ORM or framework is added. Default `mvn test` stays pure.

## Reproducible real-DB tests

Use a clean separate checkout of the exact contract reference and the existing Docker engine:

```sh
git clone https://github.com/alxarafe/arp-contract.git /tmp/arp-cds-read-reference
git -C /tmp/arp-cds-read-reference checkout --detach 7a62bba0e8f84dbc198e837baf63fc7e0d8722e1
python3 providers/integration-docker.py --contract /tmp/arp-cds-read-reference \
    --output /tmp/arp-cds-read-003-java-results
```

The runner creates one uniquely labelled private network and one disposable DB container with no published ports
and no bind-mounted database directory. An owned-container bootstrap installs a random run nonce; native tests
verify it before any DDL. The original contract setup SQL runs only in that disposable database/scope. Read-only
provider code runs against real server metadata; no mock replaces the provider, resolver or dictionary.

SDK/test processes use the invoking UID and a temporary source copy. Dependencies and build outputs stay outside
the repository. The PHP test image adds pdo_mysql only inside Docker, never to the user's PHP installation.
Cleanup tracks only IDs created by this run, checks the unique ownership label and rejects preexisting IDs; it
removes their anonymous volumes and its own network. It never prunes Docker, removes by a shared name, reuses an
existing DB/container, or invokes another project's stop script. Preexisting container IDs, running status,
start timestamps and restart counters are compared before/after. Failure paths also run this cleanup.

The first development run exposed root-owned build temporaries; the runner now uses the user UID. The two affected
own temporary directories were corrected and removed. Final full runs exercise normal completion and native
observation failures, including connection and privilege errors, with preexisting containers preserved. Docker
crash/host shutdown cleanup recovery is not claimed.

## Results (contractual and additional, kept separate)

PostgreSQL: **18.6 (Debian 18.6-1.pgdg13+2)**, image pinned by the runner to
`postgres@sha256:3a4264520e121176619f54ff867d12774bc81e95e7b84db58d63bccdb6144b81`.
**454 JUnit tests passed** in the seed phase with the optional profile, followed by one recovery test in a new
container: the 451 previous tests, two real integration methods and one persistence method
reporting **5 contract DB scenarios and 9 additional groups**. The 327 scalar assertions and 80 + 11 pure CDS
scenarios retain their original expectations. No formatter is configured in Maven; source/diff checks pass.

All five database scenarios from `fixtures/cds/read/v1/database-cases.json` are executed, using actual observations:

| Contract scenario | Result |
| --- | --- |
| lazy-generation | PASS: 0 before first resolve, 1 after, exact metadata facts and projected descriptors |
| add-column-and-invalidate | PASS: lazy regeneration, overlay unchanged, logical addition without exposure expansion |
| remove-specialized-column-and-invalidate | PASS: exact CDS_READ_UNKNOWN_FIELD outcome, no partial current catalog |
| observation-fails-after-invalidation | PASS: real refused connection, exact safe execution-failure result |
| invalidation-during-generation | PASS: actual observation invalidated before publish, exact stale result |

Additional integration groups (not new contract fixtures): composite PK/FK/index ordering; unsupported indexes
omitted; conservative/opaque types and exact large integer metadata; native CHECK withholding proof; literal
quoted identifiers; actual column-level permissions and incomplete metadata visibility; missing/view objects,
read-only/sentinel preservation; and multi-provider failure after successful observation without partial publication.
The invalidation decorator counts/calls the real provider and then invalidates; it does not fabricate observations.
An unrelated sentinel object in the owned database remains unchanged. No external application database is used.

Outputs: `<output>/java.log` contains test results and a JSON scenario summary; `<output>/java-isolation.json`
records the image/reference, completion and preservation checks. These generated files are not source deliverables.

## Minimal decisions still needed for actual LIST/GET

CDS-READ-001 explicitly compiles metadata and operation declarations; it does not define row execution. D02/D03
already constrain the first automatic path to one registered persistent table with an identifying key. Do not
reopen CRUD authoring, DICT-002 or projection grammar to add this read path. A short shared fixture-backed agreement
is still needed on these four points:

1. GET key input and missing-row outcome: ordered typed components for composite keys; use of a key excluded from
   public fields without exposing it; distinguish a missing row from a failed operation. A physical PK is observed,
   but its mere existence does not define a public row identity or authorize access.
2. Row output: the minimal envelope for LIST/GET, explicit SQL NULL versus absent data, exact portable numeric/binary/
   temporal conversion, and how original invalid historical values plus field diagnostics are returned. Existing
   CDS historical-data rules apply; an adapter must not rewrite, hide or silently normalize such data.
3. LIST bounds: server-enforced maximum page size, deterministic order/tie-breaker and the smallest pagination
   semantics. Client filters, arbitrary sorting, joins and a new query language can remain outside the first cut.
4. Server execution boundary: how trusted row/field/operation restrictions are supplied/applied to the same query,
   and how execution rejects an explicitly invalidated metadata generation. A declared LIST/GET is not permission;
   this is not a proposal for roles, UI or an HTTP protocol.

Define these outcomes in a small set of shared row fixtures, then implement native single-table SELECT adapters.
No broader architecture phase or transport contract is required to settle those minimum decisions. This task
implements none of them unilaterally and announces no executable LIST/GET capability in Angular or demo hosts.

## Review state

Branch: `feature/arp-cds-read-003`. Changes are uncommitted for review; no push, PR, merge, tag, release or package
publication. Pure CDS/scalar code and the reference contract remain unchanged. Real DB results do not imply general
normative runtime conformance.

## Optional physical YAML persistence

The existing native observer remains the sole metadata authority. `PersistedPhysicalProvider` decorates it;
`FilePhysicalStore` and `PhysicalStoreKey` are optional provider-layer APIs. Pure dictionary/resolver consumers
need no directory, database, driver or filesystem store. No dependency or normative schema is added.

The host creates an **existing absolute directory outside all versioned source trees**, then passes that directory
and its versioned source roots to the store. Source roots are mandatory even when a container copy omits `.git`.
Real paths/symlink ancestors are resolved; Git worktrees/bare repositories, declared source subtrees and symlink
or directory artifact targets are rejected. The directory must belong to the application and contain no secrets
in its name. No application folder hierarchy is prescribed: the implementation uses opaque hashed filenames
inside whichever root the host supplies. Do not choose a source directory just because it is ignored by Git.

`PhysicalStoreKey(application, connection, provider, physicalId, dialect, schema, table)` separates application
scope, stable connection alias, provider registration and literal engine/schema/table identities. Aliases are
trusted **non-secret host registrations**, never DSNs, passwords, tenant tokens or client input. A SHA-256 hash of
the length-delimited JSON tuple names the file; raw identifiers/connection strings never form the path. Changing
a database endpoint or authorization scope requires a different connection/application alias or invalidation.
Hashing is isolation/naming, not encryption or authorization. Schema/table names remain literal in the descriptor.
The host controls directory access; generated metadata can itself be sensitive and is not a public artifact.

### Serialization and publication

One registered table description is written as `<opaque-hash>.physical.yaml`. `PhysicalYaml` emits deterministic
**YAML 1.2 flow syntax**: a document marker followed by a sorted JSON-compatible flow mapping. Strings are quoted,
arrays retain observation order, absent properties stay absent, and integer metadata never passes through float.
Native types, exact range strings and unknown/verified facts are unchanged. This is an encoding of the existing
`arp.physical/v1` document, not a new physical grammar or type-mapping policy. Byte escaping is implementation-local;
semantic equality, not byte identity across languages, is required. YAML recovery uses the existing CDS YAML intake.
Schema/physical semantics, registered source identity and exact canonical roundtrip are checked before writing.
Generated documents contain only the native observation; no logical overlays or application connection settings.

The store writes a unique temporary in the same directory, flushes it and replaces the target atomically. Java
requires `ATOMIC_MOVE`; there is no copy fallback when atomic replacement is unavailable. A per-key locked `.lock`
file carries local generation, identity, checksum and validity metadata (internal implementation data, **not** a
contract format). Data is accepted only with a matching valid state/checksum, bounded to the existing 1 MiB intake,
and after schema/identity validation. Corruption fails the request and marks the cache invalid; a subsequent
request can regenerate from the real observer. There is no fallback to a damaged/stale file or fabricated metadata.
The lock/state and YAML must be retained together; copying just a YAML is not an authoritative cache restoration.
Use a root/key namespace owned by one implementation/version: the internal lock metadata is not a cross-runtime
storage protocol. The physical YAML document itself retains the shared contractual representation.

### Lifetime and failure behavior

1. First use with no committed YAML releases the file lock, introspects the requested table, validates/roundtrips
   and commits its YAML. Construction does not introspect or scan all tables. A following request reuses the snapshot.
2. A new catalog/process opens the same host-selected root/key and recovers the verified YAML without querying
   metadata again. If the host changes privileges or database identity, it must invalidate or change the alias.
3. `PhysicalCatalog.invalidate` clears the whole compiled generation and calls invalidatable provider decorators.
   Each store records a new **invalid** generation before removing its YAML. Next use regenerates from current DDL
   and rebuilds models/projections with the unchanged developer definitions. No automatic DDL watcher is added.
4. An observation running during invalidation cannot commit under the superseded store generation; the catalog
   rejects its publication with the existing snapshot-changed outcome. File locks are not held during observation.
5. Read/write/observation failures produce the existing sanitized observation-failed diagnostic. The catalog never
   publishes a partial result. Individually complete table caches may survive a later table/logical resolution
   failure: whole-catalog publication is atomic, not a distributed transaction across all physical files.
6. If storage cannot record invalidation, invalidation **reports failure** and the current catalog remains blocked
   until storage is repaired and explicit invalidation succeeds. Do not restart and treat a failed invalidation
   as successful: an entirely unwritable/unavailable filesystem cannot durably record that event. When the invalid
   marker was written but removal fails, a new process also rejects the old YAML. No stale fallback is permitted.

Local OS file locks serialize storage access, and a per-key persisted generation fences in-flight writes. They
are not distributed cache coherence: other processes' already-published in-memory catalogs must also be explicitly
invalidated by their host. No global multi-table DDL snapshot, automatic retry policy or cross-host cache is claimed.
The storage boundary assumes an owner-controlled local filesystem, not adversarial path replacement. Data/state
flushes and same-filesystem atomic replacement are used; crash/power-loss guarantees and directory-entry durability
on every filesystem/platform are not asserted. Integration evidence is Linux Docker, not all OS/filesystem pairs.

**Contractual limit:** the wider physical schema mentions views, but the approved CDS-READ-001 executable subset
admits tables and rejects views. This implementation persists only the existing supported table observations;
it does not claim view support or broaden the resolver without a contractual decision.

### Persistence verification

The Docker runner creates a uniquely labelled named volume outside both source trees, runs the baseline/DB suites
and 15 additional persistence groups, **destroys the seed runner**, then creates a different runner/process with the
same volume. Recovery asserts the same YAML checksum and **zero native introspections**. Database test state remains
in the owned DB container during this specific runner-recreation test; this is not a daemon/host-crash exercise.

The 15 groups cover lazy generation/cache/reopen, deterministic semantic roundtrip against actual introspection,
application/connection/schema/table isolation, DDL regeneration without public exposure expansion, corrupt/truncated
and schema-valid edited YAML, state corruption, read permission recovery, failed native regeneration without stale
fallback, write permission recovery, failed target replacement, failed invalidation with a blocked catalog,
in-flight invalidation, source/symlink rejection, no secret/temp leaks, and exact integer serialization.
Codec-only large-number regression inputs are explicitly separate from facts introspected from the database.
Developer definition bytes and original contract YAML hashes remain unchanged. New physical BIGINT metadata retains
native engine differences, including MariaDB unsigned range; no scalar type conversion is added by storage.

Reports add `physicalYamlRecoveredAfterContainerRecreation`, `recoveryWithoutIntrospection`,
`noGeneratedPhysicalYamlInSource`, `preexistingVolumesPreserved` and the two destroyed runner IDs. Cleanup removes
only recorded, uniquely labelled owned resources; the named volume is removed **after** recovery is proven. No
preexisting volume is reused or changed. Reports remain under the mandatory external `--output` directory.
Run the same documented `providers/integration-docker.py` command to reproduce the complete cycle.

The pre-commit hook and CI policy still scan complete Git blobs/trees and reject generated physical YAML even when
force-added. The external contract's deliberately authored physical fixtures remain unchanged and outside runtime
Git trees. Check to require in branch protection: **`physical-artifact-policy`**. Remote execution is recorded in
the integration PR; branch protection is verified separately. See the audit for hook limitations.

### Host wiring example

```java
var store = new FilePhysicalStore(hostPhysicalDirectory, List.of(hostSourceRoot));
var key = new PhysicalStoreKey("application", "registered-db", "primary", "storage.items",
        "postgresql", "public", "items");
PhysicalObservationProvider persisted = new PersistedPhysicalProvider(existingObserver, store, key);
// Register persisted in PhysicalCatalog with the existing definitions.
```

`PhysicalYaml.encode/decode`, `FilePhysicalStore.get/invalidate/fileFor` and `InvalidatablePhysicalProvider`
are in `org.alxarafe.arp.cds.physical`. Storage failures use the existing checked `ObservationException`.
To retain the previous `PhysicalCatalog.invalidate()` signature, a failed invalidation throws a sanitized
`IllegalStateException`; `current()` is empty and `resolve()` fails until explicit invalidation succeeds.
The optional profile's tests now require the runner's explicit seed/recover phase and owned volume.

See [the closing audit](cds-read-003-audit.md) for the exact evidence and remaining limits.

Additional read-only reference checks: 106 contractual Python tests and all six contract verifiers passed (including
the CDS read verifier). These are static/pure checks, kept separate from native DB evidence.

## Application directory convention

Applications that author their own definitions use this layout:

```text
arp/
  dictionary/
  models/
  projections/
  locales/
cache/
  arp/
    physical/
```

`arp/` holds versioned declarative definitions. `cache/` is ignored by Git; `physical/` holds generated physical
YAML and its storage state. The host configures the physical directory; no path is mandatory in the ARP core.
The dictionary-only demos currently load unchanged contract fixtures read-only and do not own CDS definitions
or physical storage, so those fixtures are not moved or duplicated into application directories.

For the optional persisted provider, deploy/mount `cache/arp/physical/` outside the versioned source checkout
and pass its existing absolute path as the host physical directory. The current store deliberately rejects
locations inside a Git/source tree, including ignored directories. A deployment root or external volume can
provide this application layout while keeping authored definitions and generated storage separate.
The integration runner's `/physical-store` mount is an external configurable test volume, not a core path rule.