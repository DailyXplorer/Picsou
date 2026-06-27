# Lesson: test a constant-time fix by counting crypto ops, not wall-clock time

> Recorded: 2026-06-27 · Module: backend (auth — `AuthController.login`)

## What happened

`POST /api/auth/login` leaked which usernames existed via response latency: an unknown
username failed instantly while a known username paid a full bcrypt comparison
(CWE-208, GHSA-ww5m-pxgq-8qq6). The fix equalizes the work — the unknown-user path now
runs a decoy `passwordEncoder.matches(password, dummyPasswordHash)` before throwing, so
both `401` paths cost one bcrypt round.

The tempting way to test this is to **measure both paths and assert their durations are
close**. That test is inherently flaky: bcrypt timing varies with CI load, scheduler
jitter, JIT warmup, and GC, so it needs tolerances so wide they prove nothing — and it
fails randomly, which gets it quarantined or deleted, silently removing the protection.

Instead, the regression test
(`AuthControllerTest.login_unknownUserAndWrongPassword_payIdenticalBcryptCost_soLatencyRevealsNothing`)
drives the controller with a **real spied `BCryptPasswordEncoder(12)`** and asserts a
**structural** invariant: both the unknown-user and wrong-password paths call `matches()`
**exactly twice in total** (one round each, no short-circuit), against `$2a$12$` hashes,
with the submitted password — and issue no session. Same operations ⇒ same time, proven
without ever looking at a clock.

## What we learned

- A timing-attack fix is fundamentally about equalizing **operations**, so the testable
  invariant is "the same crypto work happened on both paths" — not "the elapsed time was
  similar". Op-count is deterministic; wall-clock is not.
- The encoder must be a **real `BCryptPasswordEncoder`, spied** — not a Mockito mock. A
  mock's `matches()` returns instantly, so the test would pass even if the decoy did no
  real work or used the wrong cost factor. The spy both lets you count calls **and**
  guarantees genuine bcrypt cost; asserting the hash starts with `$2a$12$` pins the cost
  factor.
- Asserting **"no short-circuit"** (exact call count, not "≥ 1") is what actually catches
  a regression that re-introduces the early `throw` before the decoy.

## Why it matters

Username enumeration is low-severity here, but the same op-equalization pattern guards
any future constant-time path — password-reset token lookup, MFA code verification,
API-key (`psk_`) comparison. If each is tested by timing, the tests rot; if tested by
op-count, they stay green and meaningful. This makes the *technique* reusable, not just
this one fix.

## Takeaway

- To test a constant-time / op-equalization fix: **spy the real crypto primitive, assert
  the exact call count across both branches, and assert the operand shape** (e.g. cost
  factor via the `$2a$12$` prefix). Never assert elapsed time in a unit test.
- Use the real algorithm (spied), never a mock, when the property under test *is* that
  the algorithm runs and how much it costs.

## Links

- Feature note: [login-timing-attack.md](../features/login-timing-attack.md)
- Advisory: GHSA-ww5m-pxgq-8qq6 (CWE-208)
- Related login security: [mfa-and-remember-me.md](../features/mfa-and-remember-me.md) (threat model)
