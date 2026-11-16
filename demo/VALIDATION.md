# ARP-DEMO-003 — JAVA validation

Executed on 2026-11-16 on `feature/arp-demo-003-java-host`, based on `5888272f33e48d305b95ee448c8485b56d7a14cf`.
Only demo files are new. Library source/public APIs, dependencies, tests and contract.ref are unchanged.

## Results

- Docker build and host compilation/syntax: passed, with digest-pinned base images.
- Existing native suite: **355 tests: 327 shared contract assertions and 28 runtime tests**, zero failures/errors/skips.
- Shared assertions use the unchanged original fixtures at clean contract commit
  `4c0f4bd0337c01c15a036910b6462e6f0e674927`, not expectations copied from another runtime.
- Real HTTP checks: passed. 18 elements, 17 original type vectors, original source hashes, exact INT64/DECIMAL
  values and numeric descriptor metadata, library rejections and normalized results.
- HTTP negative/regression checks: passed. Empty/malformed JSON, isolated surrogates, invalid UTF-8, nested values
  and property names, valid surrogate pair/literal UTF-8/backslash text, duplicate/extra root fields, size/media
  type, unknown element/route/method, approved/disallowed Origin and preflight.
- Real Docker regressions: passed. Occupied port in start/check leaves no created container; the port owner is
  untouched. Name collisions preserve existing containers even with both demo labels. Explicit stop refuses
  containers without both ownership labels. Alternate loopback port, stable startup snapshot, normal and
  repeated stop passed. Existing running containers' status/start timestamp/restart count were unchanged.
- Cross-runtime comparison: **308 equal exchanges per host**, plus runtime identity and source/capability checks,
  using real Java, PHP and hardened .NET simultaneously. Exact JSON kinds and Decimal numeric comparison.
- Reference .NET: `442cd19556a3381d47e7e26b0351aa9ee567a791`; built/run from a temporary copy so its repository
  remained read-only. Contract reference checkout: `85c94e4a26dd1cdd1f97e0498e0b21b6afd441fb`, read-only.
- Comparison ports: .NET 5199, Java 5200, PHP 5300. The existing services on 5100 and 4173 were preserved.
  Separate checks also exercised non-default and dynamically allocated loopback ports.

- Existing pinned schema/fixture checker and **27 Python tooling tests**: passed in each repository's checker.
- Java host `javac -Xlint:all`: passed without diagnostics; PHP router `php -l`: passed.
- Bash/POSIX-shell syntax, Python AST parsing, LF/no trailing whitespace and 120-column checks: passed.
- Final staged diff checks: passed. All staged paths are under demo/; generated output, dependencies and temporary
  data are excluded. Reference repositories remain clean. Test-created hosts were stopped; existing services remain.

Closure review corrected the runtime variable to `ARP_DEMO_RUNTIME` and standalone HTTP checker default ports.
Check was repeated
with an intentionally wrong inherited value to verify that each runner supplies its own expected identity.

## Reproduction and limits

Commands and configuration are in README.md. Native test commands are `demo/run test-library` after build;
HTTP and Docker checks are `demo/run check` and `python3 demo/check-docker.py`. The comparison tool takes all three
running server URLs. During implementation logs were retained outside repositories in `/tmp/arp-demo-003/`.

No mocks replace library execution. No normative transport or general runtime conformance is claimed. No Chromium
or end-to-end Angular test was performed: the current client explicitly accepts only .NET, and arp-contract is
read-only in this task. README.md identifies the exact consumer change needed, without false host identities.

No packages, tags or releases were created. No library or normative contract changes were required.
Docker ownership limitations, server framing differences and the untested signal-interruption case are documented
in README.md. OS package repositories and dependency download availability limit bit-for-bit/offline reproduction.