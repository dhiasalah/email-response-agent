# Lessons index

One file per lesson, written to **understand and defend** the project (each ends with 🎓 questions the professor may ask).
- `shared/`: lessons 00–01b (written when the project had two students) · `dhia/`: every lesson from 02 on
- `concepts/`: deep-dives · `assets/`: SVG diagrams
- Where we are: `PROGRESS.md` (overview) and `progress/dhia.md`.

| Part | Lessons | Status |
|---|---|---|
| Foundations | 00, 00b, 01, 01b | ✅ |
| Pipeline + agents | 02–13 | 🔄 02 |
| Proof + finish | 14, 15, 16, 99 | ⏳ |

## Foundations
- [00 Roadmap, setup (JDK 21, Docker, Ollama, Quarkus skeleton, fail-closed config), threat model: trust boundaries + OWASP LLM Top 10](shared/00-roadmap-setup-threat-model.md)
- [00b GitHub workflow: branch per lesson, PRs, CI (build + gitleaks), CODEOWNERS, PR template, Dependabot, protect `main`](shared/00b-github-collaboration.md)
- [01 The shared data model: enums with policy (slot taxonomy), Flyway schema with CHECKs, Panache entities, append-only decision log](shared/01-data-model.md)
- [01b The `LlmClient` contract: instructions vs untrusted input, strict output parser, `FakeLlmClient`, obedient-model attacks](shared/01b-llm-contract.md)

## Pipeline + agents
- [02 `OllamaLlmClient`: two model tiers, JSON-schema decoding, strict parsing, one retry, typed failures](dhia/02-ollama-client.md)
- [03 Prompt injection 101: measured on our model (naive 5/6, enveloped 4/6), untrusted envelope with random id](dhia/03-prompt-injection.md)

## Proof + finish

## Concepts
- (none yet)
