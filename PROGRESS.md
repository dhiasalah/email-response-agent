# Email Response Agent — Team progress

> Shared overview. Each student's details live in `progress/dhia.md` and `progress/kacem.md`.
> Claude updates only the line or row of the student it's talking to.

## Current position
- **Dhia:** lesson 00 (shared): set up tools, generate the Quarkus skeleton, open the first PR · details: `progress/dhia.md`
- **Kacem:** lesson 00 (shared): accept the GitHub invitation, install tools, review Dhia's PR, clone · details: `progress/kacem.md`
- **Repo:** https://github.com/dhiasalah/email-response-agent (private)
- **Stack:** Java 21 · Quarkus 3 · Maven wrapper · PostgreSQL (Dev Services) · LangChain4j + Ollama (local, free)

## Lessons and owners
| # | Lesson | Owner | File | Status |
|---|---|---|---|---|
| 00 | Roadmap, setup, threat model | Both | `lessons/shared/00-roadmap-setup-threat-model.md` | 🔄 written |
| 00b | GitHub collaboration: CI, CODEOWNERS, PR template, Dependabot | Both | `lessons/shared/00b-github-collaboration.md` | 🔄 written |
| 01 | Data model + contracts (`LlmClient`, `FakeLlmClient`) | Both | — | ⏳ |
| 02 | LlmClient (LangChain4j + Ollama, validate, retry, timeouts) | Kacem | — | ⏳ |
| 03 | Prompt injection 101 + untrusted envelope | Dhia | — | ⏳ |
| 04 | Mock mailbox, pre-filter, sanitizer | Dhia | — | ⏳ |
| 05 | Orchestrator: state machine, idempotence, audit log | Dhia | — | ⏳ |
| 06 | Triage agent + injection detector | Dhia | — | ⏳ |
| 07 | Resolver (memory, KB, calendar, recent answers) | Kacem | — | ⏳ |
| 08 | Grouping (embeddings, clusters, cards, priority) | Kacem | — | ⏳ |
| 09 | Curator + knowledge memory (anti-poisoning) | Kacem | — | ⏳ |
| 10 | Writer (grounded replies) | Kacem | — | ⏳ |
| 11 | Reviewer (rules + LLM check, send modes) | Dhia | — | ⏳ |
| 12 | Sender clarification, broadcast, timers | Kacem | — | ⏳ |
| 13 | Web review panel (Qute, auth, CSRF, XSS) | Kacem | — | ⏳ |
| 14 | Security test suite (attack corpus, obedient-LLM worst case) | Dhia | — | ⏳ |
| 15 | Evaluation and metrics report | Kacem | — | ⏳ |
| 16 | Hardening + GDPR | Dhia | — | ⏳ |
| 17 | (Optional) Real Outlook via Microsoft Graph | Both | — | ⏳ |

## Milestones
| Milestone | Needs | Result | Status |
|---|---|---|---|
| M0 Shared base | 00, 00b, 01 | Repo, CI green, contracts on `main` | ⏳ |
| M1 Core loop (doc Phase 1) | D03–D06, D11, K02, K10 | Demo: email → triage → draft → review (drafts only) | ⏳ |
| M2 Grouping (Phase 2) | K07, K08 | One card answers many emails | ⏳ |
| M3 Memory (Phase 3) | K09, K12 | Scoped facts reused safely | ⏳ |
| M4 Finish (Phase 4) | D14, D16, K13, K15 | Panel + green security suite + metrics report | ⏳ |

Suggested order after M0: **Kacem starts with 02** (Dhia's 06 needs it); **Dhia does 03 → 04 → 05 → 06** meanwhile.

## Shared decisions
- 2026-10-29: Java 21 + Quarkus (not Spring Boot, not Python); plain Java agents + code orchestrator (not JADE);
  PostgreSQL via Dev Services (state must survive restarts, idempotence); Ollama local (free, data stays on the PC); mock mailbox.
- 2026-10-29: split "security vs agents". PRs need **no approval** (Dhia's choice): auto-merge after checks; each reads the other's
  merged PRs at sync time, and Dhia keeps a security eye on Kacem's.

## Blockers / questions to revisit
- `protect-main` ruleset is active (2026-10-29) and auto-merge is on; **add the required `build` check** once the CI workflow (00b, Dhia) is merged.
- Open questions from design doc §15 (sender scope source, AI disclosure, holding-reply delay): decide by lessons 07 / 12.
