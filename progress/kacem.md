# Kacem — progress (agents + UI track)

> Claude reads this at session start when `git config user.name` is `KacemAbdelbaki`, and updates it when a step is verified.

## Current position
- **Lesson:** 00 (shared) — Roadmap, setup, threat model
- **Step:** accept the GitHub invitation (github.com/notifications), install JDK 21 + Docker + Ollama + models, review Dhia's
  skeleton PR, then clone and run `.\mvnw verify`
- **Lesson file:** `lessons/shared/00-roadmap-setup-threat-model.md`
- **Level / mode:** experienced in coding and LLMs; deep on multi-agent design and AI security · copy-paste snippets · English
- **Note:** first lesson of your own track is 02 (LlmClient); Dhia's triage (06) depends on it, so it comes first after 01

## Checklist
### Shared
- [ ] 00 Setup on your PC (JDK 21, Docker, Ollama + models), review Dhia's skeleton PR, clone, `.\mvnw verify` green, smoke test
- [ ] 00b `gh` login, PR template + Dependabot (PR) · review Dhia's CI + CODEOWNERS PR
- [ ] 01 Data model + contracts (pair session with Dhia)
### My track
- [ ] 02 LlmClient: LangChain4j + Ollama, JSON schema, validate, retry once, timeouts
- [ ] 07 Resolver: memory, KB (mini-RAG), mock calendar, recent answers, scope checks
- [ ] 08 Grouping: embeddings, clustering, LLM verification, priority score
- [ ] 09 Curator + knowledge memory: scoped, expiring, versioned facts; anti-poisoning
- [ ] 10 Writer: grounded replies with the facts used (needs D06 slot output)
- [ ] 12 Sender clarification, broadcast suggestion, timers
- [ ] 13 Web review panel: Qute, form auth, CSRF, XSS from email content (needs D05 statuses)
- [ ] 15 Evaluation and metrics report

## Session log
| Date | What was done | Concepts learned | Open questions |
|---|---|---|---|

## Blockers / questions to revisit
- None yet.
