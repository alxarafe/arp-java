# Final review: ARP-JAVA-LISTGET-001

Branch: `feature/arp-java-listget-001`; base: `b74d1dd78b52469385ed6f225da967ef8f51e692`.
No commits, publications, worktrees, permission systems or caches were created in this review.

## Historical components: necessity and preservation

- `ScalarValidator`: only adds a storage helper using the existing codec/type ranges with an unconstrained
  descriptor. Historical write validation is unchanged. Historical rating 11 is readable; unrepresentable integers,
  NaN and infinite dates fail the complete response. No scalar validator is duplicated.
- `CdsReadPublication`: adds a synchronized read-only getter for the existing generation counter, needed to bind
  protected references. Existing invalidation and metadata publication behavior is unchanged.
- `PhysicalCatalog`: delegates that getter and publishes reads under the same lock as invalidation, checking the
  exact immutable snapshot. Review additionally rejects null snapshots. Tests show stale/null callbacks never run.
  Existing physical provider/persistence invalidation is retained; no second generation protocol is introduced.
- `postgresql.sql`: only adds built-in numeric/DATE facts under the existing namespace/domain/CHECK guards.
  Earlier integer/key/nullability/index observations are unchanged. Products need these DECIMAL/DATE facts.
  Persisted older observations without them must be invalidated to rebuild; missing capabilities fail closed.
  This does not assert verified DECIMAL editing-domain compatibility.

All four additions are used by real reads. The 454 earlier Java/native checks still pass unchanged, including
327 DICT and 96 CDS conformance checks. Historical sources, schemas, fixture counts and `contract.ref` are unchanged.

## Audit and corrections

1. Complete field authorization now precedes resolution errors. Target carries the exact immutable declared
   exposure and resolved fields must match it. This is trusted registration, not another permissions/cache system.
   Authorization also precedes request extras, so a client authorization flag cannot change rejection precedence.
2. Null keys and malformed component objects return INVALID_KEY instead of execution/provider failure.
3. Revoked operation/field/policy grants at publication return UNAVAILABLE; composition/metadata invalidation
   remains GENERATION_CHANGED. An authoritative policy revision change during SQL is explicitly tested.
4. An absent metadata snapshot cannot invoke the publication callback.

SQL identifiers come solely from trusted resolved metadata and are quoted. Values, complete keys, positions and
limits are JDBC parameters. Every integer equality is in SQL before ORDER BY/LIMIT, including multi-column
restrictions. The complete physical PK is ascending numeric keyset order. LIST fetches at most limit + one;
GET at most two to detect identity failure. There is no OFFSET or host row filtering. Only projection columns
and the complete internal PK are selected; hidden keys and other hidden values never appear in public values.

AES-GCM uses an instance-private random 256-bit key and fresh 96-bit nonces. Associated data binds purpose,
target, projection, identity and policy revision/epoch. The encrypted payload binds generation, limit and full PK.
A seven-component token is exercised at <=128 characters; eight components fail closed before a row SELECT.
References are exercised across two GET-enabled projections using the contract's alternate definition.
Every use is reauthorized. No row-result or token cache exists; recreating a host revokes its evidence.

Lock order is host gate then catalog publication lock. Database/resolveGate work runs outside the host gate.
A real two-thread test pauses after SELECT, proves external ALTER TABLE times out with SQLSTATE 55P03 while
ACCESS SHARE is held, invalidates on the other thread and confirms there is no stale output. DDL and rebuild
subsequently succeed. The database lock remains through decoding and final checks. A read-only commit failure
returns a safe failure without output. Publication linearizes at the final fence; no distributed watcher or
lasting data snapshot across separate requests is promised. Application policy changes must use changeAccess.
The final reauthorization checks the authoritative policy against the immutable trusted request context.

## Results and artifacts

Final run: 476 tests (454 previous + 22 LIST/GET), zero failures/errors/skips, plus one persistence recovery
in a replacement container using the same owned volume. Six review scenarios extend the earlier 16 native tests.
No product fixture is copied. All recorded public responses validate against the pinned LISTGET JSON Schema.

Evidence: `~/Desarrollo/tmp/arp-java-listget-001-final-review/`:
`java.log`, `java-isolation.json`, `listget-responses.json`, `schema-validation.json`, `vector-coverage.json`,
`contract-integrity.json`, `final.diff`, `diff-stat.txt`.
The inventory confirms all preexisting containers retain running/start/restart state and all preexisting volumes
remain. Only owned disposable resources, the source copy and read-only contract checkouts are mounted/queried.
No other project's database or data is accessed. This is isolation evidence, not a checksum of unrelated data.

## All 88 vectors

N: direct shared request/complete expectation replay with live PostgreSQL metadata. These 24 negative dispatch
vectors assert zero row SELECT; N does not claim a row query for a rejection. Only the registered target alias
is adapted. E: an identified native test proves equivalent behavior, without claiming exact vector replay.
U: not executed or unsupported; see individual reasons. Totals: N=24, E=58, U=6.
Evidence aliases are dynamic test names in `ListGetIntegrationTest`:

- P: `products LIST/GET exact shared values and paging (simple and composite)`.
- R: `absent and inaccessible indistinguishable; reports derived (simple and composite)`.
- O: `opaque bindings, tampering, limit and generation (simple and composite)`.
- A: `authorization, inactive modules and unsupported policy fail closed (simple and composite)`.
- M: `no output on inflight invalidation, module or policy change (simple and composite)`.
- D: `metadata rebuild, external DDL and provider failure (simple and composite)`.
- X: `invalid stored scalar discards complete page (simple and composite)`.
- F1: `review shared input vectors`.
- F2: `review full authorization precedes resolution errors`.
- F3: `review empty SQL page and empty GET publication fence`.
- F4: `review independent token bindings and access revocation`.
- F5: `review concurrent invalidation and real DDL exclusion`.
- F6: `review PK capacity and incompatible storage boundaries`.

| Vector | Status | Evidence |
| --- | --- | --- |
| get-authorized-hidden-key | E | P |
| get-null-preserved | E | P |
| get-historical-rating | E | P |
| get-exact-decimal | E | P |
| get-unicode-and-empty-values | E | P |
| get-unknown-reference | E | O |
| get-existing-but-row-denied | E | R |
| get-scope-binding-denied | E | F4 |
| get-principal-binding-denied | E | F4 |
| get-policy-binding-denied | E | F4 |
| get-projection-binding-denied | E | F4 |
| get-simple-typed-key | E | P |
| get-composite-typed-key | E | P |
| get-missing-typed-key | E | R |
| get-raw-hidden-key-rejected | N | F1 |
| key-null | N | F1 |
| key-incomplete | N | F1 |
| key-reordered | N | F1 |
| key-extra | N | F1 |
| key-number-not-string | N | F1 |
| key-wrong-type | N | F1 |
| key-out-of-range | N | F1 |
| list-first-page | E | P |
| list-second-page | E | P |
| list-last-page | E | P |
| list-server-clamps-limit | E | P |
| list-empty-authorized-set | E | F3 |
| list-numeric-order-simple-keys | E | P |
| list-derived-projection | E | F3 |
| list-limit-zero | N | F1 |
| list-limit-negative | N | F1 |
| list-limit-bool | N | F1 |
| list-limit-fraction | N | F1 |
| list-limit-too-large | N | F1 |
| list-limit-null | N | F1 |
| list-arbitrary-filter | N | F1 |
| list-arbitrary-sort | N | F1 |
| list-fields-injection | N | F1 |
| list-offset-rejected | N | F1 |
| cursor-unknown | N | F1 |
| cursor-null | N | F1 |
| cursor-limit-changed | E | O |
| cursor-context-changed | E | F4 |
| operation-not-authorized | E | A |
| target-not-registered | E | A |
| module-disabled | E | A |
| operation-not-enabled | E | A |
| derived-operation-not-exposed | E | R |
| selected-field-not-readable | E | A |
| client-context-injection | N | F1 |
| client-context-cannot-authorize | N | F1 |
| input-null | N | F1 |
| read-capability-not-proven | E | A |
| table-without-key | E | F6 |
| invalid-definition | E | D |
| unauthorized-definition-error-concealed | E | F2 |
| physical-incompatibility | E | F6 |
| stale-catalog-generation | U | - |
| stale-record-reference | E | O |
| stale-continuation | E | O |
| invalidation-during-read | E | F5 |
| authorization-lost-before-publication | E | F4 |
| provider-failure | E | D |
| last-row-decode-failure-no-partial-page | E | X |
| unexpected-null-nonnullable | U | - |
| duplicate-record-identity | U | - |
| new-column-not-published-after-rebuild | E | D |
| removed-published-column-rebuild-rejected | E | D |
| public-application-without-principal | E | P |
| empty-get-still-checks-publication-generation | E | F3 |
| context-changed-before-publication | E | F4 |
| list-limit-one | E | F4 |
| list-after-last-empty | E | F3 |
| invalid-server-limit-registration | U | - |
| modules-catalog-list | E | P |
| modules-catalog-get-denied-independently | E | A |
| modules-catalog-get-authorized | E | P |
| modules-reports-derived-list | E | F3 |
| modules-reports-get-unavailable | E | R |
| modules-reports-inactive | E | A |
| modules-all-inactive | E | A |
| modules-reports-without-catalog | U | - |
| modules-undeclared-projection-dependency | U | - |
| modules-deactivation-during-read | E | M |
| modules-row-restriction-still-authoritative | E | F3 |
| modules-client-cannot-activate | N | F1 |
| modules-field-access-still-authoritative | E | A |
| modules-permission-revoked-before-publication | E | M |

## Equivalence details

- `get-unknown-reference`: Tampered/unknown protected evidence returns the same safe absence path.
- `get-existing-but-row-denied`: Real SQL GET combines inaccessible and missing typed identities; no post-filtering.
- `get-projection-binding-denied`: Shared alternate definition enables reports GET; cross-target/projection references
  fail.
- `list-server-clamps-limit`: Server clamping tested on identified over the same table; limiting is
  projection-independent.
- `list-empty-authorized-set`: A trusted tenant equality selects no authorized rows in PostgreSQL.
- `list-numeric-order-simple-keys`: Simple-key catalog traversal proves numeric PK order; identified uses the same
  model/order plan.
- `list-derived-projection`: Derived reports and tenant/product equalities return exactly one authorized row before
  limiting.
- `target-not-registered`: Removing an active target exercises the same registry miss guard as an unknown target.
- `operation-not-enabled`: Independent host GET denial with catalog LIST still allowed.
- `table-without-key`: Dropping the actual PK makes LIST unavailable before a row SELECT.
- `invalid-definition`: Removing a published column causes real resolution failure with no stale fallback.
- `unauthorized-definition-error-concealed`: Full field authorization now precedes an invalid physical/logical
  definition.
- `physical-incompatibility`: Real bigint-to-smallint narrowing is incompatible with the INT64 logical domain.
- `authorization-lost-before-publication`: GET authorization is revoked after SQL, before output is published.
- `new-column-not-published-after-rebuild`: A new physical column is observed after invalidation but never expands
  projected values.
- `public-application-without-principal`: Public identity alias has no principal; the controlled host explicitly
  authorizes GET.
- `empty-get-still-checks-publication-generation`: Unknown reference does not SELECT rows; invalidation at final
  authorization prevents empty output.
- `context-changed-before-publication`: Authoritative policy revision changes during SQL; final authorization rejects
  the stale trusted context.
- `list-after-last-empty`: An issued cursor followed by deletion of remaining rows produces an empty terminal SQL page.
- `modules-catalog-list`: Controlled valid active registry; this does not claim a module manifest parser.
- `modules-reports-derived-list`: Reports derives from catalog; two SQL equalities restrict its result to one product.
- `modules-all-inactive`: Removed-target registry guard is equivalent; no general module activation engine is claimed.
- `modules-deactivation-during-read`: Reports deactivation aborts publication; parallel metadata invalidation is
  separately tested.
- `modules-row-restriction-still-authoritative`: A predicate yielding zero authorized rows runs before
  lookahead/pagination.
- `modules-permission-revoked-before-publication`: LIST permission revoked after SQL returns UNAVAILABLE without output.

## Remaining limits and unexecuted obligations

- `stale-catalog-generation`: The host resolves its own current catalog; an externally supplied stale catalog is not an
  input.
- `unexpected-null-nonnullable`: NOT NULL prevents this physical contradiction; the defensive faulty-provider null
  branch was not injected.
- `duplicate-record-identity`: The real PK prevents duplicate identity; the defensive duplicate-provider branch was not
  injected.
- `invalid-server-limit-registration`: Invalid server limits fail at Target construction, not through the neutral
  invocation envelope.
- `modules-reports-without-catalog`: General module dependency composition is outside this profile; the application
  supplies a valid registry.
- `modules-undeclared-projection-dependency`: No general module source/dependency manifest parser is implemented in this
  read slice.

The NULL/duplicate defensive branches are not advertised as natively executed: actual NOT NULL/PK constraints
prevent those contradictions. No fake database was introduced to manufacture evidence. Invalid server limits
are rejected at trusted registration time (tested), rather than through the fixture's neutral INVALID_DEFINITION
envelope: this is a demonstrated difference. General module dependency validation remains with the application;
only controlled valid registries are used. Integer equality restrictions, demonstrated scalar codecs, one ordinary
table and an integer PK of at most seven components remain the profile. Other codecs, arbitrary predicates,
views, partitioning/inheritance and fixed CHAR padding fail closed. No external DDL watcher is added.
U vectors are counted neither as passed nor as equivalent; this is not a claim of complete 88-vector conformance.