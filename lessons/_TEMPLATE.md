# Lesson NN · Step N — <Step title>

**Owner:** Dhia | Kacem | Both

## 🗺️ In plain words: what we're doing today
One short paragraph, zero jargon (or jargon explained right away): where we are now, what we'll build today,
and why. Finish with one concrete example of "what you'll see when it works".

## 🎯 Goal
One or two sentences: what you'll have at the end and why it matters for the Email Response Agent.
**Design-doc sections:** §X, §Y. **Depends on:** lesson NN merged (or FakeLlmClient).

## 🧰 Tools in this step
For each new tool, in plain words:
- **Tool name**: what it is (analogy) · what problem it solves · why this project needs it · where it comes back later. [Docs](https://...)

How they connect (SVG in `lessons/assets/`):
![How the parts connect](../assets/NN-name.svg)

## 💻 Commands
Copy-paste (PowerShell):

```powershell
git switch main; git pull
git switch -c <name>/NN-short-name
```
Expected output:
```
...
```

## 🧩 Code, piece by piece
### 1. <what this piece does>
**Where:** `src/main/java/com/emailagent/.../File.java` — new file / add under / replace ...

```java
// snippet
```
Short paragraph: what it does and why it is written this way.

### 2. ...

## 🤝 Contract for <the other student> (if this lesson defines something they use)
The record / interface they will call, and what it guarantees.

## 📚 Key concepts
Only the multi-agent / LLM / security ideas worth knowing, with [docs](https://...) links.

## 🔐 Security note
- Threat (OWASP LLM0X) → defense in this step.

## 🧨 Attack it (from lesson 03 on)
One attack to run against what you just built, and what should happen.

## ✅ Check it works
```powershell
.\mvnw verify
```
Expected output:
```
[INFO] BUILD SUCCESS
```

## 🌿 Branch & PR
Branch: `<name>/NN-short-name`. When the step is verified, **Claude** checks for secrets, commits, pushes, opens the PR
(reviewer: <dhiasalah | KacemAbdelbaki>) and switches to the next lesson's branch. You only need to:
- read the PR description and the diff once,
- ask the other student to review; they check the security checklist against the diff,
- squash-merge after green CI + approval: `gh pr merge <N> --squash --delete-branch`.

## ➡️ Next step
What comes next — tell Claude "I finished lesson NN, please review" or "go next".

## Your next action
1. ...
