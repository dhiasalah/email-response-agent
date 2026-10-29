## Summary
<!-- What this PR adds and why -->

## What changed
-

## How I tested it
- [ ] `./mvnw verify` is green locally
- [ ] Ollama tests run if this touches an agent: `./mvnw test "-Dgroups=ollama" "-DexcludedGroups=none"`

## Security checklist
- [ ] No secrets, tokens or `.env` in the diff
- [ ] No real student emails or personal data (fixtures are fake)
- [ ] Email content and LLM output are treated as untrusted (validated, never executed, never rendered as raw HTML)
- [ ] No new capability for an LLM agent (no send / forward / write tools)
- [ ] New dependencies are explained below

## Notes for the reviewer
