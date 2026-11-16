# ARP-DEMO-003 — JAVA dictionary HTTP demonstration

Standalone demo adapter, separate from the scalar library. **This HTTP API is not a normative ARP transport
contract.** Only dictionary listing, descriptors and validation are executable; no CDS, CRUD, persistence,
authentication simulation or service dispatch is introduced.

## Implementation

The host uses the JDK 25 `jdk.httpserver` module, virtual threads and the existing Jackson dependency.
It is compiled separately by `javac`; the library Maven project and public APIs are unchanged. It calls
`CatalogBuilder.buildCatalog`, `Catalog.ids/resolve/validate`, descriptor `toJson()` and result `projection()`.
Jackson parsing at the HTTP boundary uses strict UTF-8, a depth limit of 64 and an explicit token pass to reject
isolated UTF-16 surrogates and count duplicate root fields. Empty input is invalid JSON, not a shape error.
Descriptors use exact BigInteger/BigDecimal serialization; exponent spelling may differ without changing value.

Both original, unchanged fixtures are loaded from the read-only `ARP_CONTRACT_PATH` mount:

- `fixtures/dictionary/v1/catalog.json`: 17 scalar types.
- `fixtures/dictionary/v1/sources/portable-equivalent.json`: exact numeric metadata `1e127`.

A failed catalog build/configuration fails startup. Sources and SHA-256 hashes are reported, alongside the library's
unchanged `contract.ref`. No arbitrary client-selected sources are read. The catalog snapshot is stable for the
life of the host. Library validation remains independent of HTTP and Docker.

## Demo endpoints

| Method | Path | Response |
| --- | --- | --- |
| GET | `/demo/info` | `runtime: java`, `demoProtocol: arp-demo-java/1`, library pin, capabilities, hashes |
| GET | `/demo/dictionary/elements` | Ordered `elements` array with `id` and `type` |
| GET | `/demo/dictionary/elements/{id}` | Exact descriptor JSON from the real library |
| POST | `/demo/dictionary/elements/{id}/validate` | Body with exactly one property `value`; real library result |

Capabilities are exactly `dictionary.list`, `dictionary.describe` and `dictionary.validate`.
A body such as `{"value":"9223372036854775807"}` preserves numeric values as strings; booleans and null retain their
JSON kind. Missing value, duplicate root fields and extra properties are input-shape errors. JSON numeric, array
and object values receive actual library type rejection, never coercion. Success and value rejection return 200;
unknown elements return 404 with `DICTIONARY_ELEMENT_NOT_FOUND`. Successful normalization includes normalizedValue;
unsuccessful results omit it. Contractual diagnostic codes and ordered issues are preserved.

Demo errors are `{"valid":false,"issues":[{"code":"DEMO_..."}]}`: malformed JSON/Unicode is
400 `DEMO_INVALID_JSON`, wrong shape 400 `DEMO_INPUT_SHAPE`, body over 16 KiB 413 `DEMO_REQUEST_REJECTED`, non-JSON
media type 415 `DEMO_JSON_REQUIRED`, forbidden Origin 403 `DEMO_ORIGIN_NOT_ALLOWED`, unknown routes/methods
404/405 `DEMO_HTTP_ERROR`, internal execution failure 500 `DEMO_EXECUTION_FAILURE`. Only input decoding failures
are classified as malformed JSON; internal errors are logged rather than exposed as traces.

CORS allows the exact `ARP_DEMO_ORIGIN`, default `http://127.0.0.1:4173`, GET/POST and Content-Type, without
credentials. Allowed preflight is 204; disallowed preflight has no allow-origin header. Normal requests with an
unapproved Origin receive 403. Requests without Origin are allowed: CORS is not authentication.

## Build, start and stop

Prerequisites: Docker Engine, Bash, tar, sha256sum, and an arp-contract checkout. Python 3 is needed for tests.
No host Java/PHP installation, database or global package installation is required. Base images are digest-pinned.
Application dependencies reuse the existing pom.xml/composer.lock. Java's dependency-copy build plugin is pinned
and demo-only; PHP's container installs Git/unzip to run Composer and existing tests. OS package repositories are
not a bit-for-bit lock. Nothing is pushed to an image registry or package registry.

```sh
export ARP_CONTRACT_PATH=/absolute/path/to/arp-contract
./demo/run build
./demo/run start
curl http://127.0.0.1:5200/demo/info
curl -H 'Content-Type: application/json' -d '{"value":"128"}' \
  http://127.0.0.1:5200/demo/dictionary/elements/test.int8/validate
./demo/run stop
```

`ARP_DEMO_PORT` overrides 5200; container port is always 8080 and publication is exclusively `127.0.0.1`.
`ARP_DEMO_CONTAINER` overrides the per-runtime, per-user default name. `ARP_DEMO_ORIGIN` sets one exact http(s)
origin without path, credentials, query, fragment or trailing slash. Different ports/names let the three hosts
coexist. Successful standalone start means Docker started the process; use `/demo/info` or check for readiness.

The runner creates and captures an exact container ID before starting. Failure cleanup is registered beforehand,
requires both `arp.demo=java-dictionary` and the absolute checkout ownership label, and never adopts an existing
same-name container. Explicit stop resolves the ID before checking both labels. Builds/tests are ephemeral and
never mount writable library/contract sources. No global prune, foreign-container removal, image/volume deletion
or termination of a process occupying a port is used. Local build caches/images remain available for later runs.

## Tests

The native suite requires a separate clean contract checkout at
`4c0f4bd0337c01c15a036910b6462e6f0e674927` (`contract.ref`); current main is not a substitute.

```sh
export ARP_CONFORMANCE_PATH=/absolute/path/to/pinned-contract-checkout
./demo/run check
python3 demo/check-docker.py
```

Check builds, runs the unchanged native suite, starts a real host, verifies loopback publication, executes the
HTTP regressions and removes only that invocation's host. `test-library` runs the native suite after building.
`check-docker.py` uses real containers with unique names and tests occupied-port failures in start/check modes,
name collisions even with matching labels, refusal with missing labels, a non-default loopback port, stable startup
snapshots using only temporary fixture copies, normal/repeated stop and unchanged pre-existing running containers.
Its finalizer removes exact test-created IDs. Temporary outputs under `demo/.artifacts/` are ignored.

With all three real hosts running, compare from either repository:

```sh
python3 demo/compare-http.py --dotnet-url http://127.0.0.1:5100 \
  --java-url http://127.0.0.1:5200 --php-url http://127.0.0.1:5300
```

The comparison keeps JSON kinds distinct (boolean is not number), compares numeric metadata with Decimal and
checks 308 exchanges per host: lists, all descriptors, type vectors, valid/invalid values, errors, Unicode/JSON,
size/depth boundaries, media types, unknown elements/routes/methods and CORS. Runtime/protocol identity is checked
truthfully and excluded only from cross-runtime equality. Results prove the tested demo subset, not general
normative conformance. See VALIDATION.md for the exact revisions and observed results.

## Angular integration and remaining limits

The existing Angular client in `arp-contract@85c94e4a26dd1cdd1f97e0498e0b21b6afd441fb` checks
`info.runtime === 'dotnet'` and `info.demoProtocol === 'arp-demo-dotnet/1'` in
`demo/client/angular/src/dotnet/dictionary.ts`, and only mounts that adapter for .NET. Its Java/PHP selections
therefore cannot yet execute these hosts. Do not make a host impersonate .NET.

The minimal next consumer change belongs in arp-contract: extract the existing dictionary adapter into a common
facade, choose the expected truthful runtime/protocol pair from the selector and mount it for all three runtimes.
Reuse these four routes, configurable URLs and exact/raw descriptor handling. No theme, translation, fixture,
contract or angular-ui-runtime changes are needed to define the backend behavior. That consumer edit is outside
this task because arp-contract is explicitly read-only. No end-to-end Angular compatibility is claimed here.

These are unauthenticated local development servers, not production deployment designs. Native HTTP servers can
reject malformed transport framing before application routing; this is not a promise of byte-for-byte equality
of all server-generated headers/errors. No retries, heartbeat or mandatory HTTP dependency is added to ARP.
Labels are cooperative ownership markers, not protection against an actor controlling Docker. Cleanup needs an
available daemon; SIGKILL/daemon failure may prevent it. INT/TERM handlers exist but signals were not separately
exercised. Ports are configurable; the script never takes over an occupied port.