# Dhia — progress (solo project)

> Claude reads this at session start and updates it after each verified lesson.

## Current position
- **Lesson:** 03 — Prompt injection 101 + untrusted envelope
- **Mode:** Claude builds every lesson end to end, in a row; Dhia reads the lesson files (🎓 sections) to defend the project
- **Level:** experienced in coding and LLMs; deep on multi-agent design, AI security and app security · English

## Checklist
- [x] 00 Setup (JDK 21, Docker, Ollama + models), Quarkus skeleton, `AppConfig` fail-closed, Ollama smoke test (2026-10-29)
- [x] 00b CI workflow + CODEOWNERS + PR template + Dependabot, `build` check required on `protect-main` (2026-10-29)
- [x] 01 Data model: enums + slot taxonomy with policy flags, `V0101` schema with CHECKs, 3 Panache entities, append-only `decision_log` (2026-10-29)
- [x] 01b Contracts: `LlmClient`, `LlmRequest`, `LlmException`, strict `LlmOutputParser`, `FakeLlmClient`, obedient-model attack test (2026-10-29)
- [x] 02 `OllamaLlmClient`: JSON schema, strict parse, retry once, timeouts, tier → model (2026-10-29)
- [ ] 03 Prompt injection 101: attacks on a naive agent, untrusted-data envelope
- [ ] 04 Mock mailbox, pre-filter (loops, auto-replies), sanitizer (hidden HTML, Unicode)
- [ ] 05 Orchestrator: state machine, idempotence, append-only decision log
- [ ] 06 Triage agent + injection detector
- [ ] 07 Resolver: memory, KB, mock calendar, recent answers, scope checks
- [ ] 08 Grouping: embeddings, clusters, input cards, priority
- [ ] 09 Curator + knowledge memory (anti-poisoning)
- [ ] 10 Writer: grounded replies, facts used + sources
- [ ] 11 Reviewer: deterministic rules + LLM check, send modes
- [ ] 12 Sender clarification, broadcast, timers
- [ ] 13 Web review panel: Qute, form auth, CSRF, XSS
- [ ] 14 Security test suite: attack corpus, obedient-LLM worst case
- [ ] 15 Evaluation and metrics report
- [ ] 16 Hardening + GDPR: PII redaction in logs, retention
- [ ] 99 Defense guide

## Session log
| Date | What was done | Concepts to know for the defense |
|---|---|---|
| 2026-10-29 | Chose stack (Java/Quarkus/Postgres/Ollama), repo created, lessons 00 + 00b | Trust boundaries, OWASP LLM Top 10, CI + protected `main` |
| 2026-10-29 | Lesson 00: pom fixes, Ollama smoke test green, `StartupConfigCheck` so `SEND_MODE=autto` fails at start | Constrained decoding enforces enums not regexes; primitive defaults hide missing fields; fail-closed config |
| 2026-10-29 | Lesson 01: `.\mvnw verify` 3/3, Flyway v0101, UPDATE/DELETE on `decision_log` refused (#12) | Policy in enums (`shareable`, `automatable`); DB CHECKs as last line; `@Version`; `@Immutable` + trigger = defense in depth |
| 2026-10-29 | Switched to a solo project: Claude builds every lesson; rules, CODEOWNERS and progress rewritten | — |
| 2026-10-29 | Lesson 01b: 16/16 tests; 10 obedient-model attacks refused; a lenient mapper accepted 5 of them | Instructions vs data as separate fields; strict Jackson; checked exception = fail closed by the compiler; fake in `src/test` |
| 2026-10-29 | Lesson 02: `OllamaLlmClient` (2 tiers, schema, strict parse, 1 retry, typed failures); 21 tests + 2 Ollama tests green | Low-level ChatModel vs AI services; retry once; retry note never echoes model text; valid ≠ correct (`REQUEST` vs `QUESTION`) |

## Blockers / questions to revisit
- Claude's shell can inherit a stale `JAVA_HOME=C:\jdk-21.0.2\bin`; the user variable (Adoptium 21.0.12) is right.
