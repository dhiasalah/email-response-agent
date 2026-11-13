# Specialized agents

Every email is handed to **one** specialized agent by the router. An agent is a set of files in this folder:
- its **instructions** (a system prompt the writer follows);
- its own **context**: a knowledge base and a calendar that no other agent reads.

What the owner teaches through an agent's emails also stays with that agent. This covers the facts from answered cards, the answers learned from sent replies, and the cards themselves.

Agents are read once at start-up. To add or change one, edit the files and restart; no code changes. A mistake stops the start-up with a message naming the file. For example: an unknown key, a missing file, two fallback agents, or an id that does not match the file name.

The folder is `agents/` next to where the app runs; set `AGENTS_DIR` to use another one.

## Files

```
agents/
├── student.json          the definition: id, name, routing, which files to load
├── student/prompt.txt    instructions for the writer: role, tone, structure, what never to disclose
├── student/kb.json       knowledge base: answers the agent may give without asking
└── student/calendar.json calendar: answers the owner confirms before they are used
```

### `<id>.json`

```json
{
  "id": "colleague",
  "name": "Colleague agent",
  "description": "Faculty colleagues: juries, grading, teaching duties, seminars.",
  "order": 30,
  "fallback": false,
  "routing": {
    "senderRoles": ["COLLEAGUE"],
    "senderAddresses": [],
    "senderDomains": [],
    "keywords": ["jury", "copies", "correction", "séminaire"],
    "members": []
  },
  "promptFile": "colleague/prompt.txt",
  "knowledge": ["colleague/kb.json"],
  "calendar": []
}
```

| Key | Meaning |
| --- | --- |
| `id` | Lower case letters, digits and dashes; must equal the file name. It is stored with every email, fact and card, so renaming it orphans them. |
| `name`, `description` | Shown in the panel; the name is also given to the writer ("You act as the Colleague agent"). |
| `order` | Tie-break: when two agents fit equally well, the lower order wins. Default 100. |
| `fallback` | Exactly one agent must be the fallback: it takes every email no other agent fits. It cannot be members-only. |
| `routing` | What sends an email here (below). All lists are optional. |
| `promptFile` | Plain text appended to the writer's rules. It can shape the reply but never overrides the rules. Facts still come only from the knowledge base, calendar and memory. |
| `knowledge`, `calendar` | JSON files in the format of `student/kb.json`: `id`, `title`, `slotType`, `entity`, optional `qualifier`, `program`/`track`/`year` scope, `value`, `source`. |

Every file must be inside this folder.

## Routing

The router adds up the evidence for each agent:

| Evidence | Weight |
| --- | --- |
| Sender listed in `senderAddresses` | 1.0 |
| Sender's domain in `senderDomains` | 0.5 |
| Sender's role (from the sender directory) in `senderRoles` | 0.4 |
| Each keyword found in the subject or body | 0.3, at most 0.6 in total |
| An earlier email of the same thread went to this agent | 0.8 |

Sender evidence (address, domain) counts only when the sender is authenticated.

Keywords are matched as whole words, without case or accents: `resultats` matches `Résultats`, and `exam` does not match `examined`.

The agent with the highest score takes the email:
- **Too weak:** below 0.3, no agent fits and the fallback agent answers.
- **Close call:** when the two best agents are within 0.1, the one with the lower `order` wins. The decision is marked as a close call in the activity log.
- **Confidence:** min(1, score) × score / (score + runner-up score). It is shown in the activity log with the reasons.

**Members-only agents.** An agent with a `members` list takes email only from those addresses, and only when the sender is authenticated. Membership is not evidence: it doesn't make an email go there. A member writing about something else goes to the agent that fits that topic.

Use this for a project whose documents must not reach outsiders: an outsider naming the project lands elsewhere and gets nothing from its knowledge base.

## The shipped agents

| Agent | Order | Takes | Context |
| --- | --- | --- | --- |
| `supermas-project` | 10 | Project members writing about SuperMASProject (members only) | Deliverable D2.1, meeting minutes, plenary meeting |
| `student` | 20 | Students: exams, grades, internships, deadlines | Grade appeals, course slides, the MAS project; grade publication and resit dates |
| `colleague` | 30 | Faculty colleagues: juries, grading, seminars | Grade submission, room booking |
| `administration` | 40 | The administration: forms, agreements, certificates | Grade submission, internship agreements |
| `generic` | 999 | Everything else (fallback) | None: every question becomes a card for you |

The demo inbox has one email per case (m13 to m17):
- the same colleague in two contexts: m13 goes to colleague, m14 to the project;
- an office question: m15 goes to administration;
- a close call between student and administration: m16;
- a student naming the project, kept out of it: m17.

You can see each decision on the panel's **Agents** and **Activity** pages.
