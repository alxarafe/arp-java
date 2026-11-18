# ARP-JAVA-LISTGET-001: native PostgreSQL read slice

Implemented on `feature/arp-java-listget-001`, based on remote main
`b74d1dd78b52469385ed6f225da967ef8f51e692`. No commit or publication is included.

## Run the actual database integration

Keep two clean contract checkouts: the existing historical `contract.ref` remains
`7a62bba0e8f84dbc198e837baf63fc7e0d8722e1`; `listget-contract.ref` independently pins
`eb38cb96e6ccd218734df2a181908de46067e0c7`. Historical DICT/CDS fixtures and expected counts are unchanged.
The integration test reads products and definitions directly from the latter checkout.

```sh
python3 providers/integration-docker.py \
    --contract "$HOME/Desarrollo/tmp/arp-contract-php-config-001-validation" \
    --listget-contract "$HOME/Desarrollo/tmp/arp-java-listget-001-contract" \
    --output "$HOME/Desarrollo/tmp/arp-java-listget-001-review"
```

The runner uses the existing PostgreSQL provider Maven profile, owned Docker network/database,
nonce validation before DDL, read-only contract mounts and the existing persistence/recreation test.
Build copies and persistent reports live under the output directory in `~/Desarrollo/tmp/`.
Only containers, networks and volumes created by the invocation are cleaned up, with ownership checks.
The pinned PostgreSQL image is 18.6; the JDBC test verifies the actual server version.
`java.log`, `java-isolation.json` and `listget-responses.json` survive cleanup.

## Invocation boundary

`ListGetHost.invoke(byte[], Context)` accepts the strict SERVICES-style JSON request.
The application supplies an active target registry, immutable context and an `Authorizer` independently
of the client request. The target registers a resolved projection, its exact declared field set and server
default/maximum limit.
That immutable field set permits complete authorization before metadata errors can be revealed.
This integration registers `catalog`, `reports` (derived summary) and `identified` over the contract table.
It provides real SQL-backed invocations inside integration tests; it does not add an HTTP endpoint.

The authorizer grants operations independently, the complete published field set, and a conjunction of
integer column equalities. An empty equality map explicitly permits all rows; a null map means the
host policy cannot be executed and is rejected. Other predicates are unsupported and must not be
represented as an empty map. Identifiers come from the trusted registry/effective physical catalog;
all values, complete GET keys, keyset positions and limits are JDBC parameters.

The application must include principal/tenant/scope in `Context.identity` and the current policy
revision in `Context.policyRevision`. Every policy or active composition mutation must run through
`changeAccess`, even if it later restores a previous policy. The application is responsible for
validating module dependencies before registering a composition: this slice has no general module
activation engine. Removing reports can preserve catalog; removing catalog requires removing reports.
The test registry supplies these controlled valid compositions.

## Implemented guarantees

- LIST uses the complete physical integer PK in ascending numeric order, tuple keyset comparison,
  SQL restrictions before pagination and at most effective limit + one rows. No OFFSET or row post-filtering.
- GET uses every PK component and the same SQL restriction. Missing and inaccessible rows return `found:false`.
- Values contain exactly the projection fields. INT64 and DECIMAL use exact strings; Unicode and nulls survive.
  The existing DICT scalar codec checks representability without current editing constraints: rating 11 survives.
- If any key component is hidden, AES-256-GCM protects the entire PK with a fresh random nonce.
  Purpose, target, projection, identity, policy epoch/revision, physical generation and cursor limit are bound.
  The secret exists only in the host instance; recreation revokes its tokens. No token or row-result cache is added.
- The existing `PhysicalCatalog` owns the metadata generation. Reads check authorization/generation before SQL
  and again under the host/catalog publication gates, including absence. Invalidated work has no output.
- ACCESS SHARE remains held during physical reobservation, row reading, decoding and final publication checks.
  Physical drift fails closed; the application invalidates/rebuilds metadata before retrying. No stale schema fallback.
- SQL/connection/provider details are omitted from public failures; decoding failure discards the complete page.

## Verified scope and remaining work

The recorded final review passed 476 tests (454 existing + 22 native LIST/GET), plus one persistence-recovery test
in a replacement runner container. All had zero failures/errors/skips. Both simple and composite PK layouts
exercise catalog LIST, existing/missing/inaccessible GET, reports LIST, independent operation/field authorization,
inactive reports, protected-token context/purpose/limit/generation binding, multi-page traversal without duplicates,
metadata addition/removal, provider failure and invalidation after an actual SELECT, including missing GET.
Real PostgreSQL NaN/infinite date values and an unrepresentable integer discard the entire response.
All 147 recorded native responses validate against the pinned LISTGET JSON Schema, including the 128-character
token ceiling. Evidence: `~/Desarrollo/tmp/arp-java-listget-001-final-review/`
(logs, responses, isolation and schema reports).
Twelve existing generated-physical Git guard tests also passed; no project formatter is configured.

This is a working bounded implementation, **not a claim of full conformance to all 88 draft vectors**.
The [final review](listget-001-review.md) classifies every vector: 24 direct native replays, 58 identified equivalents
and six not executed/unsupported. No locally duplicated fixture set is introduced.

Fail-closed capacity limits: equality restrictions on integer columns only; published integer/DECIMAL/STRING/TEXT/
BOOLEAN/DATE values only; demonstrated integer PKs of at most seven components (128-character token ceiling);
no views, inherited/partitioned tables or fixed CHAR padding.
Other codecs, policy predicates and a general modular activation system are not implemented.
There is no automatic external-DDL watcher or distributed policy/generation coordinator. External DDL drift is
checked under a database lock on each read; application invalidation is required to rebuild after such drift.
The metadata resolver still reports its historical compatibility semantics; it does not claim verified DECIMAL
editing domains. Native lossless decoding is tested separately. The previous observer lacked numeric/date scalar
facts; adding those facts enabled the real products read without modifying historical fixtures.