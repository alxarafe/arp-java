# ARP-DICT-001 review feedback from the Java implementation

Reference: `0172ab64742218b9f64a6ae96c43ebdfe690f4c6`.
No supplied expected outcome required changing, overriding or skipping. No
contract file or PHP implementation was modified.

1. Specify a minimum accepted YAML 1.2 subset or common resolution schema.
   The contract permits a restricted subset, while positive YAML fixtures only
   exercise simple strings. This library explicitly uses the YAML 1.2 JSON schema
   and supports block scalar text. A shared decision and fixtures should settle
   broader scalar spelling support rather than letting parser defaults decide.
2. Add positive and negative source vectors for large numeric metadata and
   mathematically integral decimal/exponent spellings. The JSON Schema's integer
   model permits exact integral numeric values independently of lexical spelling.
   Java preserves BigInteger/BigDecimal through parsing and export, including
   integral `1e10000`; it does not inherit PHP's extra exponent ceiling.
3. Add duplicate-null/escaped-key vectors and unknown directives to shared source
   tests. These exposed parser-specific traps outside the current fixtures.
   Token inspection rejects directives even when an event parser would discard
   unknown directives. Duplicate checks use key presence, not non-null values.
4. Define a shared nesting budget and the intended classification of parser
   resource ceilings beyond bytes/elements/numeric digits. Java exposes a
   configurable 128-level default and returns DICTIONARY_LIMIT_EXCEEDED.
5. Clarify "declared numeric domain" for numeric constraints relative to optional
   DECIMAL geometry. The existing `ordered-constraints` value vector establishes
   that a bound outside the geometry is not automatically a definition error;
   this implementation follows that vector and does not add a satisfiability solver.

These are proposals for common review, not changes to ARP semantics or claims of
PHP/Java equivalence outside the actual shared fixtures. Snapshot concurrency and
parser security still require stack-specific instrumentation, beyond JSON vectors.

## Consolidation 002 resolution

The five observations above are historical feedback against `0172ab6`.
Contract `2dde1a09780e29c7385606c5e018fa75a0808b68` resolves them with
a mandatory YAML subset, exact bounded source numbers, shared depth/resource
budgets and explicit geometry wording. This implementation follows those decisions.
The prior acceptance test for `1e10000` was deliberately changed to admitted
`1e127`; oversized exponents now receive DICTIONARY_LIMIT_EXCEEDED before
BigDecimal conversion. No public API or scalar-string arithmetic was changed.
No remaining contradiction was found during the 327 shared assertions and
27 Java regressions. Shared fixtures are representative rather than exhaustive.
