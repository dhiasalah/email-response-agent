# MEMORY.md

Quick catch-up for Claude sessions. Read this first; open other files only when the task needs them.

## What this is

Master 2 multi-agent systems (MAS) course project: an agent system that drafts replies to a professor's
student emails. Java 21 + Quarkus 3.40 + LangChain4j, all LLM calls go to a **local Ollama** (no cloud model).
Replies are **drafts only by default** (`SEND_MODE=draft`); the owner reviews in a web panel.
Assignment PDF lives outside the repo (project files: `multi_agent_project_en.pdf`). Full design:
`Email Response Agent — System Design.md` (repo root, ~750 lines; it still says "Outlook" in places, the code uses
a mock mailbox or Gmail). Design doc section numbers (§6, §9, …) are cited all over the code comments.

## Architecture in one screen

The **Orchestrator is plain code, not an LLM** (`orchestrator/Orchestrator.java`). Each `AgentStep` declares one
trigger `EmailStatus`; the orchestrator snapshots the email, runs the step outside any transaction, validates the
proposed transition against `EmailLifecycle`, then commits only if the email's (status, version) didn't change.
Agents never call each other and never set a status themselves. `OrchestratorJob` is the scheduler heartbeat
(`ORCHESTRATOR_EVERY`, default 30s): import mail → learn from sent replies → run steps → grouping.

Pipeline (package `com.emailagent`, statuses in `model/EmailStatus.java`):

| Status in | Step | File | Notes |
|---|---|---|---|
| NEW | Triage (LLM, FAST) | `agents/TriageAgent.java` | labels intent/sensitivity/language |
| TRIAGED | Router (code) | `agents/TriageRouter.java` | → ESCALATED / IGNORED / RESOLVING; regex backstop for sensitive topics |
| RESOLVING | Resolver (code) | `agents/ResolverAgent.java` | fills slots from KB/calendar/memory → READY, AWAITING_SENDER or AWAITING_INPUT |
| AWAITING_SENDER | `orchestrator/SenderReplyStep.java` | | clarification with student |
| AWAITING_INPUT | `orchestrator/HoldingReplyStep.java` | | holding reply while owner answers an input card |
| (slots) | Grouping (embeddings + LLM verifier) | `agents/GroupingAgent.java`, `orchestrator/GroupingRun.java` | clusters similar open questions into one owner input card; not an AgentStep |
| READY | Writer (LLM, STRONG) | `agents/WriterAgent.java` | |
| DRAFTED | Reviewer (LLM, STRONG + rules) | `agents/ReviewerAgent.java`, `agents/ReviewRules.java` | → REVIEWED or ESCALATED |
| REVIEWED | `orchestrator/SendDispatcher.java` | | DRAFT_SAVED (draft mode) or SENT (auto) via `OutboxRelay` |
| — | Curator (LLM, FAST) | `agents/CuratorAgent.java`, `orchestrator/ReplyLearning.java` | extracts reusable facts from the owner's sent replies |

Other packages:
- `llm/` — `LlmClient`/`OllamaLlmClient`, `ModelTier` (FAST/STRONG), `LlmOutputParser` (strict JSON into records like `TriageOutput`).
- `security/` — `PreFilter`, `Sanitizer` (jsoup), `InjectionDetector`, `UntrustedEnvelope` (wraps email text before it reaches a prompt).
- `mailbox/` — `Mailbox` interface; `MockMailbox` (JSON fixtures in `src/main/resources/mailbox/`) or `GmailMailbox` + `GoogleOAuth` (PKCE, allow-listed senders, drafts only).
- `store/` — Panache entities + `KnowledgeBase`/`KnowledgeMemory`; append-only `DecisionLogEntry`. Schema via Flyway in `src/main/resources/db/migration/` (Hibernate is `validate` only, so every entity change needs a new `V####__*.sql`).
- `web/` — `PanelResource` (`/panel`: cards, drafts send/reject, escalations, broadcasts, `/panel/metrics`), `LoginResource`, `PasswordHash`. Qute templates in `src/main/resources/templates/`, CSS in `META-INF/resources/panel.css`.
- `config/` — `AppConfig` (`@ConfigMapping(prefix="emailagent")`, defaults live there), `StartupConfigCheck`, `PiiLogFilter`.

Agent prompts are Java constants (`INSTRUCTIONS` in each agent class), not external files.

## Build, run, test

```bash
./mvnw verify                      # what CI runs; needs Docker (Dev Services starts PostgreSQL). Ollama tests excluded.
./mvnw test -Dtest=WriterTest      # one test class
./mvnw test "-Dgroups=ollama" "-DexcludedGroups=none"   # @Tag("ollama") tests, need a running Ollama
./mvnw quarkus:dev                 # app at http://127.0.0.1:8080/panel (binds 127.0.0.1 only)
MAILBOX=gmail ./mvnw quarkus:dev   # Gmail mode
```

Prereqs: Java 21, Docker, Ollama with `ollama pull qwen2.5:7b` and `ollama pull nomic-embed-text`.
Tests use `llm/FakeLlmClient` and `FakeEmbedder`; evaluation fixtures are in `src/test/resources/eval/`, attack corpus in `src/test/resources/attacks/`.
CI: `.github/workflows/ci.yml` (`./mvnw -B verify` + gitleaks).

## Config (env vars, see `application.properties` and `AppConfig.java`)

- `OWNER_PASSWORD_HASH` — PBKDF2 hash from `java src/main/java/com/emailagent/web/PasswordHash.java '<password>'`. Unset = nobody can log in. Username defaults to `owner`.
- `OLLAMA_HOST` (default `http://127.0.0.1:11434`), `FAST_MODEL` and `STRONG_MODEL` (default `qwen2.5:7b`; `qwen2.5:3b` on low RAM), `EMBED_MODEL` (`nomic-embed-text`).
  Gotcha: `.env.example` says `CHAT_MODEL`, but the properties read `FAST_MODEL`/`STRONG_MODEL`.
- `SEND_MODE` = `draft` (default) | `auto`. `OWNER_ADDRESS`, `ORCHESTRATOR_EVERY`, `RETENTION_DAYS` (365).
- `MAILBOX` = `mock` (default) | `gmail`. Gmail also needs `GMAIL_ALLOWED_SENDERS` (comma-separated; empty = reads nothing) and `~/.emailagent/gmail-client.json` (Desktop OAuth client). Tokens stay outside the repo.
- Prod DB: `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## Conventions and rules

- Treat email content and LLM output as untrusted: wrap in `UntrustedEnvelope`, parse strictly, escape in templates, never let model text choose a status or an escalation reason.
- No agent gets send/forward/write tools. Policy decisions live in code (router, review rules, send dispatcher), not prompts.
- Fail closed: on doubt or a second LLM failure, escalate to the owner.
- Never log prompts, replies or email addresses (`PiiLogFilter`, `log-requests=false`). Fixtures are fake data only.
- Comments cite design doc sections; keep that habit. Code and comments are in English, sample emails may be French.
- PRs follow `.github/pull_request_template.md` (summary, tests, security checklist). One feature per PR, branch off `main`.

## Known gaps vs the assignment PDF (as of 2026-11-04)

1. PDF §4-6 wants a router to **context-specialized agents** (e.g. Student / Teacher / Admin / SuperMASProject), each with its own system prompt and context files **editable without code**. Ours are functional agents with hardcoded prompts.
2. Panel lacks agent list/create/edit and prompt editing, draft editing, and **regenerate-on-reject** with a comment (§6.10, §10). Reject currently escalates.
3. `README.md` is still the Quarkus template (§16.1 wants a real README).
4. No report: answers to the 14 design questions, patterns used, UML, contributions table (§14, §16-18).
5. No experiments broken down by sender category.
