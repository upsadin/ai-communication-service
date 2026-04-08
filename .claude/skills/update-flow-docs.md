---
name: update-flow-docs
description: Updates FLOW.md and FLOW_DIAGRAM.md after changes to application flow components
user_invocable: true
---

# Update Flow Documentation

When this skill is invoked, update the flow documentation files to reflect the current state of the codebase.

## Steps

1. Read the current versions of `FLOW.md` and `FLOW_DIAGRAM.md` in the project root
2. Scan the following directories for changes that affect the application flow:
   - `src/main/java/com/aicomm/conversation/` — all services handling conversations
   - `src/main/java/com/aicomm/kafka/` — Kafka consumer
   - `src/main/java/com/aicomm/telegram/` — Telegram client, handler, rate limiter
   - `src/main/java/com/aicomm/schedule/` — deferred tasks, work schedule
   - `src/main/java/com/aicomm/agent/` — AI agent factory and interface
   - `src/main/java/com/aicomm/controller/` — REST controllers
3. Compare the actual code with what's documented in FLOW.md and FLOW_DIAGRAM.md
4. Update both files to match the current implementation:
   - **FLOW.md** — detailed text description of each step, method signatures, parameters
   - **FLOW_DIAGRAM.md** — ASCII diagrams showing all flow paths and branching logic
5. Preserve the existing structure and formatting style of both files
6. Report what was changed

## Rules

- Do NOT remove existing sections unless the corresponding code was deleted
- Add new sections for new components/services
- Update method signatures if they changed
- Update configuration sections if application.yml changed
- Keep the language in Russian (matching existing documentation style)
