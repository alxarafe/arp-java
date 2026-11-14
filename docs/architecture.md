# ARP-DICT-001 Java architecture

The public API consists of immutable data and snapshot operations:

- `Source`, `Source.Encoding` and `Limits` define explicit input and resource budgets.
- `CatalogBuilder` constructs all-or-nothing snapshots with local working state.
- `Catalog` provides sorted enumeration, resolution, provenance and validation.
- `Descriptor` encapsulates only validated immutable scalar metadata.
- `BuildResult`, `Resolution` and `ValidationResult` are records enforcing exclusive
  success/failure states using Optional values and defensive immutable issue lists.
- `ScalarType`, `Code`, `Issue` and `Provenance` expose typed vocabulary and diagnostics.

Package-private implementation collaborators:

- `SourceParser`: strict UTF-8 decoding, Jackson JSON intake, SnakeYAML token/event
  checks and safe node conversion. All source trees remain local to one build.
- `JsonSupport`: exact parser configuration, complete Unicode tree checking and
  JSON projection export. Configured shared writer is safe for concurrent use;
  input mappers are scoped to their build's limits.
- `DefinitionParser`: closed shapes, applicability, identity, numeric bounds,
  paired decimal geometry, text restrictions and ENUM self-consistency. It does
  not attempt general satisfiability solving. A first isolated definition failure
  is returned; the contract prescribes no global definition issue ordering.
- `Numbers`: portable lexical rules, integer domains and exact numeric metadata.
  Mathematical comparisons and divisibility use arbitrary-precision BigDecimal;
  integer metadata is parsed as BigInteger without passing through floating point.
- `ScalarValidator`: lexical/type checks, intrinsic and declared constraints,
  portable normalization, exact temporal UTC normalization and strict Base64.
- `PortablePattern`: immutable compiled ASCII atoms plus bounded O(atoms × code
  points) dynamic matching. Per-call mutable arrays prevent concurrency interference.
- `Unicode`: checks surrogate pairing in Java UTF-16 representations.
- `Rejected`: package-private expected rejection signal. Only recognized parser
  errors and contract rejections become dictionary issues. Resource failures and
  programming errors are not relabeled as business-invalid values.

No class discovers modules, obtains source files, fetches URLs, installs handlers,
uses a database or publishes an active catalog globally. A consuming application
owns those actions. Each call validates against exactly one Catalog object; an
application can atomically swap its own reference after a successful new build.

The design was derived from the fixed ARP contract and original expected
fixtures. It does not clone PHP class structure or inherit PHP parser limits.
