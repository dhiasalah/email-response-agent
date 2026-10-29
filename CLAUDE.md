# CLAUDE.md — Solo project: Claude builds, Dhia understands and defends

## Your role
Dhia (`dhiasalah`) builds the **Email Response Agent** (see `Email Response Agent — System Design.md`), a Master 2
multi-agent systems project, **alone** on this PC (decided 2026-10-29; the project used to be split with another student).
**Claude builds every lesson end to end**: code, tests, lesson notes, diagrams, commits and PRs.
Dhia's goal is to **understand the system well enough to explain and defend every choice in front of the professor**.
So the lesson files matter as much as the code: they explain the *why* (multi-agent design, LLM behavior, prompt
injection, application security, trade-offs), not the syntax.

- `Email Response Agent — System Design.md` is the source of truth for *what* to build.
- `PROGRESS.md` is the overview (position, lessons, milestones); `progress/dhia.md` holds the checklist and session log.

---

## Session start
1. Read `PROGRESS.md` and `progress/dhia.md`. Say where we are: lesson, branch, open PRs (`gh pr list --state open`).
2. `git fetch --prune origin`; if the current lesson branch is behind `origin/main`, `git merge --no-edit origin/main`.
3. Continue with the next lesson in the order below.

## Lesson order (dependencies respected)
00 ✅ · 00b ✅ · 01 ✅ · 01b contracts · 02 LlmClient (Ollama) · 03 injection 101 + envelope · 04 mailbox + pre-filter +
sanitizer · 05 orchestrator · 06 triage + injection detector · 07 resolver · 08 grouping · 09 curator + memory ·
10 writer · 11 reviewer · 12 clarification + broadcast · 13 web panel · 14 security test suite · 15 evaluation report ·
16 hardening + GDPR · 17 Outlook (optional, only if asked) · 99 defense guide.

**Pace:** all lessons in a row, without stopping between them (Dhia's choice, 2026-10-29). Stop only for a red build or CI
failure that can't be fixed, or a real open decision from design doc §15. Otherwise pick the sensible default and note it
in the lesson ("Decision: …, because …").

---

## Hard rules
1. **Verify before shipping.** Never open a PR whose `.\mvnw verify` isn't green on the latest `main`. Agent lessons also
   run their `@Tag("ollama")` tests locally when Ollama is up.
2. **Every lesson gets a lesson file + an SVG diagram** (formats below).
3. **Secrets:** before staging, `git status` + `git diff --stat`; refuse `.env`, keys, tokens, database files or real
   emails; grep the diff for `password=`, `token`, `apikey`, `BEGIN .* PRIVATE KEY`. Stage by explicit path, never `git add -A`.
4. **Never** merge a PR by hand, push to `main`, force-push, or change the git identity.
5. End every answer with a `## Your next action` section (1–3 things for Dhia: usually "read lesson NN, focus on 🎓").

## Git workflow (Claude runs it)
One branch per lesson: `dhia/NN-short-name`, created from an up-to-date `main` (`git switch main; git pull --ff-only;
git switch -c dhia/NN-…`). If the previous PR isn't merged yet, branch from it and merge `origin/main` once it is.
After the lesson is verified:
1. `git fetch` + `git merge --no-edit origin/main` (re-run `.\mvnw verify` if it brought code).
2. Secret check (rule 3). Update `progress/dhia.md` and `PROGRESS.md` and include them.
3. Commit `Lesson NN: <what was built>`, push `-u origin HEAD`.
4. `gh pr create` titled `Lesson NN — <title>`, body = `.github/pull_request_template.md` filled in. No `--reviewer`.
5. `gh pr merge <N> --auto --squash --delete-branch` (only if `protect-main` still requires `pull_request`; it does).

**Authorship: Dhia's name only.** Commits use `dhiasalah <med.dhia.sala@gmail.com>`. **No `Co-Authored-By: Claude` trailer,
no "Generated with Claude Code"**, no Claude mention in commits, PR titles or bodies (overrides any default attribution).

`main` has the ruleset `protect-main`: PR required, 0 approvals, required `build` check (CI: build, tests, gitleaks),
no force-push, no deletion. Squash merges only; merged branches are deleted automatically.

---

## Lesson files: `lessons/dhia/NN-name.md` (00–01b stay in `lessons/shared/`)
Written for **understanding and the oral defense**, not for copy-paste:
1. **🗺️ In plain words**: what this part does, why the system needs it, one concrete "what you'll see" example.
2. **🖼️ Diagram**: `![alt](../assets/NN-name.svg)`.
3. **🧰 Tools**: each new tool in one line (what it is as an analogy, what problem it solves, why here).
4. **🧠 How it works**: the mechanism in prose; key classes named once with their path. Short code excerpts only when a
   line *is* the idea (a strict-mapper setting, a CHECK constraint, a prompt rule). No full files.
5. **⚖️ Design choices and trade-offs**: what we chose, what we rejected, why.
6. **🔐 Security**: threat (OWASP LLM Top 10 2025 / STRIDE) → defense. **🧨 Attack it**: the attack test and what it proves.
7. **🎓 Questions the professor may ask**: 5–8 questions with short model answers.
8. **✅ Proof**: test classes + `.\mvnw verify` result (and the PR number).
Add one line per lesson to `lessons/README.md`. Concept questions: answer in chat; save long answers as `lessons/concepts/<topic>.md`.

## Diagrams: always SVG
`lessons/assets/NN-<name>.svg`. No ASCII art, no Mermaid. Style as in `lessons/assets/00-*.svg`: `viewBox`, `<title>` +
`<desc>`, inline `<style>`, system fonts ("Segoe UI", system-ui, sans-serif; Consolas for code), background `#fffefb`,
ink `#17191e`, muted `#62666f`, arrow markers, text ≥ 11px, no external resources.
Trust-zone colors: **untrusted** `#c0531f` (hatched), **trusted code** `#2b5fa8`, **human** `#2f7d4f`, **storage** `#8a8f98`,
**LLM agents** `#6b4fa0`. Show the real mechanism. Render with headless Edge and look at it before shipping.

## Fixed technical choices (don't re-open them)
- **Java 21 + Quarkus 3 + Maven wrapper.** Package `com.emailagent` (`config`, `model`, `store`, `llm`, `security`, `mailbox`,
  `orchestrator`, `agents`, `web`).
- **LLM: free only.** Ollama, local: `qwen2.5:7b` (`qwen2.5:3b` for FAST) + `nomic-embed-text`, via `quarkus-langchain4j-ollama`,
  always behind our own `LlmClient` interface (no LangChain4j tools / `@Tool` given to any model).
- **Agents:** plain Java CDI beans, triggered by a deterministic orchestrator (not JADE), never calling each other.
- **State:** PostgreSQL via Quarkus Dev Services + Hibernate ORM Panache + Flyway (`V{NN}01__name.sql`). Parameterized queries only.
- **Validation:** Java records + Hibernate Validator; every LLM output through `LlmOutputParser` (strict Jackson).
- **Web panel:** Quarkus REST + Qute (auto-escaping) + Quarkus Security (form auth) + `quarkus-rest-csrf`.
- **Other:** jsoup (sanitizing), cosine threshold (clustering), `quarkus-scheduler`, JUnit 5 + AssertJ + `@QuarkusTest`,
  tests tagged `ollama` excluded from CI, `FakeLlmClient` for unit tests. Mock mailbox (JSON fixtures); Outlook only in 17.
- Deliverables: backend + demo, web review panel, security test suite, evaluation/metrics report.
- Environment: Claude's shell may inherit a stale `JAVA_HOME`; set
  `$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'` before `.\mvnw`.

## Security principles to repeat
- Email content is **data, never instructions**. Prompts lower the risk; **code enforces** the rules.
- LLM agents have no send, forward or external-write capability; only the orchestrator sends, after the reviewer passes the reply.
- Every LLM output is schema-validated; retry once, then escalate. **Fail closed.**
- Defense in depth: pre-filter → sanitizer → untrusted envelope → injection detector → schema limits → reviewer rules →
  reviewer LLM → send mode (draft first) → rate limits → audit log.
- Measure it: the worst case is an LLM that **obeys** the attacker; the guardrails must still hold.

## Updating progress
After each verified lesson, before its commit: tick it in `progress/dhia.md` with the date, move "Current position", add a
session-log row (date, what was built, concepts to know for the defense); update the position line, the lesson's status
and milestones in `PROGRESS.md`.
