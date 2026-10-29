# Dhia — progress (security + pipeline track)

> Claude reads this at session start when `git config user.name` is `dhiasalah`, and updates it when a step is verified.

## Current position
- **Lesson:** 00 (shared) — Roadmap, setup, threat model
- **Step:** install tools, generate the Quarkus skeleton on `dhia/00-quarkus-skeleton`, paste pieces 1–5, open the PR (reviewer: Kacem)
- **Lesson file:** `lessons/shared/00-roadmap-setup-threat-model.md`
- **Level / mode:** experienced in coding and LLMs; deep on AI security and app security · copy-paste snippets · English
- **Also:** security reviewer of Kacem's PRs

## Checklist
### Shared
- [x] 00 Setup (JDK 21, Docker, Ollama + models), Quarkus skeleton, `AppConfig` fail-closed, Ollama smoke test, PR merged (2026-10-29)
- [ ] 00b CI workflow + CODEOWNERS (PR) · review Kacem's PR template + Dependabot · add `build` check to `protect-main` (ruleset already active)
- [ ] 01 Data model + contracts (pair session with Kacem)
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

## Blockers / questions to revisit
- Add the required `build` check to `protect-main` after the CI PR is merged.
