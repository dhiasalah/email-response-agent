# Dhia — progress (security + pipeline track)

> Claude reads this at session start when `git config user.name` is `dhiasalah`, and updates it when a step is verified.

## Current position
- **Lesson:** 01b (shared) — Contracts: `LlmClient` + `FakeLlmClient`
- **Step:** paste pieces 1–5 (`LlmException`, `LlmClient`, `FakeLlmClient`, `@DefaultBean` wiring), write the attack test, `.\mvnw verify` · branch `both/01b-llm-contract`
- **Lesson file:** `lessons/shared/01b-llm-contract.md`
- **Level / mode:** experienced in coding and LLMs; deep on AI security and app security · copy-paste snippets · English
- **Also:** security reviewer of Kacem's PRs

## Checklist
### Shared
- [x] 00 Setup (JDK 21, Docker, Ollama + models), Quarkus skeleton, `AppConfig` fail-closed, Ollama smoke test, PR merged (2026-10-29)
- [x] 00b CI workflow + CODEOWNERS + PR template + Dependabot in one PR (`both/00b-github`, Kacem's pieces done by Claude at Dhia's request) · `build` check required on `protect-main` (2026-10-29)
- [x] 01 Data model: enums + slot taxonomy with policy flags, `V0101` schema with CHECKs, 3 Panache entities, append-only `decision_log` (trigger), attack test green (2026-10-29)
- [ ] 01b Contracts: `LlmClient` + `FakeLlmClient`
### My track
- [ ] 03 Prompt injection 101: attacks on a naive agent, untrusted-data envelope
- [ ] 04 Mock mailbox, pre-filter (loops, auto-replies), sanitizer (hidden HTML, Unicode)
- [ ] 05 Orchestrator: state machine, idempotence, append-only decision log
- [ ] 06 Triage agent + injection detector (needs K02 or FakeLlmClient)
- [ ] 11 Reviewer: deterministic rules + LLM check, send modes (needs K10 draft format)
- [ ] 14 Security test suite: attack corpus, obedient-LLM worst case, SpotBugs/FindSecBugs, dependency check
- [ ] 16 Hardening + GDPR: PII redaction in logs, retention, encryption at rest

## Session log
| Date | What was done | Concepts learned | Open questions |
|---|---|---|---|
| 2026-10-29 | Chose stack (Java/Quarkus/Postgres/Ollama), split with Kacem, repo created + Kacem invited, lessons 00 + 00b written | Trust boundaries, OWASP LLM Top 10, team workflow | Why is a free-text `intent` dangerous? (lesson 01) |
| 2026-10-29 | Lesson 00 done (Claude wrote the remaining pieces at Dhia's request): fixed JAVA_HOME, pom (AssertJ in `<dependencies>`, `excludedGroups` top-level), smoke test green ×3, `StartupConfigCheck` so `SEND_MODE=autto` really fails | dependencyManagement vs dependencies; decoder ignores regex, enforces enum; LangChain4j fields optional by default; primitive defaults hide missing fields; unused `@ConfigMapping` is never validated | — |
| 2026-10-29 | Lesson 01 done (Dhia pasted the 4 enums, Claude wrote pieces 2–8 + attack test at Dhia's request): `.\mvnw verify` 3/3, Flyway v0101, UPDATE/DELETE on `decision_log` refused | Policy in enums (`shareable`, `automatable`); DB CHECKs as last line; `validate` + naming strategy; `@Version`; `@Immutable` + trigger = defense in depth; append-only vs GDPR erasure | GDPR erasure vs append-only log (lesson 16) |

## Blockers / questions to revisit
- Kacem hasn't typed lesson 01: he should read `lessons/shared/01-data-model.md` + the merged PR before 01b/02.
- Claude's shell can inherit a stale `JAVA_HOME=C:\jdk-21.0.2\bin`; the user variable (Adoptium 21.0.12) is right. Open a new terminal if `mvnw` complains.
