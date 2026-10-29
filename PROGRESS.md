# Email Response Agent — Progress

> Overview. Details and session log: `progress/dhia.md`.

## Current position
- **Dhia:** lesson 05 — Orchestrator: state machine, idempotence, audit log next · details: `progress/dhia.md`
- **Repo:** https://github.com/dhiasalah/email-response-agent (private)
- **Stack:** Java 21 · Quarkus 3 · Maven wrapper · PostgreSQL (Dev Services) · LangChain4j + Ollama (local, free)

## Lessons
| # | Lesson | File | Status |
|---|---|---|---|
| 00 | Roadmap, setup, threat model | `lessons/shared/00-roadmap-setup-threat-model.md` | ✅ merged |
| 00b | GitHub workflow: CI, CODEOWNERS, PR template, Dependabot | `lessons/shared/00b-github-collaboration.md` | ✅ merged, `build` required |
| 01 | Data model (enums, schema, entities, append-only log) | `lessons/shared/01-data-model.md` | ✅ merged (#12) |
| 01b | Contracts: `LlmClient`, strict parser, `FakeLlmClient` | `lessons/shared/01b-llm-contract.md` | ✅ merged (#13) |
| 02 | LlmClient (LangChain4j + Ollama, validate, retry, timeouts) | `lessons/dhia/02-ollama-client.md` | ✅ PR |
| 03 | Prompt injection 101 + untrusted envelope | `lessons/dhia/03-prompt-injection.md` | ✅ PR |
| 04 | Mock mailbox, pre-filter, sanitizer | `lessons/dhia/04-mailbox-sanitizer.md` | ✅ PR |
| 05 | Orchestrator: state machine, idempotence, audit log | — | ⏳ |
| 06 | Triage agent + injection detector | — | ⏳ |
| 07 | Resolver (memory, KB, calendar, recent answers) | — | ⏳ |
| 08 | Grouping (embeddings, clusters, cards, priority) | — | ⏳ |
| 09 | Curator + knowledge memory (anti-poisoning) | — | ⏳ |
| 10 | Writer (grounded replies) | — | ⏳ |
| 11 | Reviewer (rules + LLM check, send modes) | — | ⏳ |
| 12 | Sender clarification, broadcast, timers | — | ⏳ |
| 13 | Web review panel (Qute, auth, CSRF, XSS) | — | ⏳ |
| 14 | Security test suite (attack corpus, obedient-LLM worst case) | — | ⏳ |
| 15 | Evaluation and metrics report | — | ⏳ |
| 16 | Hardening + GDPR | — | ⏳ |
| 17 | (Optional) Real Outlook via Microsoft Graph | — | ⏳ not planned |
| 99 | Defense guide (whole system + professor questions) | — | ⏳ |

## Milestones
| Milestone | Needs | Result | Status |
|---|---|---|---|
| M0 Shared base | 00, 00b, 01, 01b | Repo, CI green, contracts on `main` | ✅ |
| M1 Core loop (doc Phase 1) | 02–06, 10, 11 | Demo: email → triage → draft → review (drafts only) | ⏳ |
| M2 Grouping (Phase 2) | 07, 08 | One card answers many emails | ⏳ |
| M3 Memory (Phase 3) | 09, 12 | Scoped facts reused safely | ⏳ |
| M4 Finish (Phase 4) | 13–16 | Panel + green security suite + metrics report | ⏳ |

## Decisions
- 2026-10-29: Java 21 + Quarkus (not Spring Boot, not Python); plain Java agents + code orchestrator (not JADE);
  PostgreSQL via Dev Services (state must survive restarts, idempotence); Ollama local (free, data stays on the PC); mock mailbox.
- 2026-10-29: **solo project**: Dhia alone, Claude builds every lesson end to end; lessons explain the design for the
  oral defense. PRs auto-merge after the `build` check, no approval.

## Blockers / questions to revisit
- Open questions from design doc §15 (sender scope source, AI disclosure, holding-reply delay): decided by default in 07 / 12.
