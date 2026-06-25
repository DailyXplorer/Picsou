# Lesson: thread-bound context is lost across a framework's async thread hop

> Recorded: 2026-06-26 · Module: backend (Spring Security × Spring AI MCP)

## What happened

The embedded MCP server (Spring AI 1.0.3, WebMvc+SSE) authenticated each `psk_` access-key on the
Tomcat **servlet thread** — `AccessKeyAuthFilter` sets `SecurityContextHolder`. But Spring AI runs
`@Tool` methods on a **Reactor scheduler thread**. `ScopeEnforcementAspect` reads the authentication
from `SecurityContextHolder` (a `ThreadLocal`), so on the tool thread it saw *nothing*. Every scoped
`tools/call` failed with `Missing required scope` — **even for a key that held the scope** — so the
whole MCP feature was non-functional, in production, while the entire test suite was green.

The bug was invisible to unit tests and only surfaced by driving the real SSE transport end to end
(`GET /mcp` → `initialize` → `tools/call list_accounts` → "missing scope"). Fixed by propagating the
context across the hop: register `SecurityContextThreadLocalAccessor` with the Micrometer
`ContextRegistry` and call `Hooks.enableAutomaticContextPropagation()` (`McpSecurityContextPropagationConfig`).
Clean A/B — the *only* change was the propagation; before → "missing scope", after → the owner's real data.

## What we learned

- `SecurityContextHolder` — and any `ThreadLocal` (MDC, `@Transactional`, request scope) — does **not**
  cross a reactive/async thread boundary. Authorization at the servlet filter layer can pass while an
  in-handler thread-local check fails, because they execute on **different threads**.
- Spring AI tool execution is reactive **even for the `SYNC` server type** — tools run off the request thread.
- Single-threaded unit tests **structurally cannot** catch this: they set the context on the test thread
  and call the aspect directly, so they stay green regardless. Green tests were false confidence.

## Why it matters

The feature shipped and reached a public production deploy completely broken, with a fully passing test
suite. The failure mode is invisible to the existing (Mockito, single-thread) test strategy, so any
future thread-local check on a reactive/async path will silently break the same way without warning.

## Takeaway

- When a check reads a `ThreadLocal` and the framework may run the work on another thread (Reactor,
  `@Async`, executor pools), **propagate the context explicitly**. For Reactor: a `ThreadLocalAccessor`
  + `Hooks.enableAutomaticContextPropagation()`. Do **not** rely on `MODE_INHERITABLETHREADLOCAL` — it
  only covers threads spawned from the current one, not pooled scheduler threads.
- **Verify auth/context behavior by driving the real transport**, not same-thread unit tests. Picsou MCP
  smoke: open `GET /mcp`, read the `endpoint` event, `POST /mcp/message` an `initialize` then a
  `tools/call`, and assert a scoped tool returns the owner's data — run it against `:8080` and the public origin.

## Links

- Feature note: [mcp-server.md](../features/mcp-server.md) (Gotchas + Tests)
- ADR: [Access-key auth + embedded MCP server](../decisions/2026-06-05-access-key-auth-and-embedded-mcp.md)
