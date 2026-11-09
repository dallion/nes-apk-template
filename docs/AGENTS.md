# AGENTS.md

## Purpose

Maintain design documentation, packaging rules, and verification guides for using and maintaining the FC/NES Android template.

## Structure

```text
docs/
├─ AGENTS.md         # Directory purpose and constraints
├─ README.md         # Project documentation index
├─ architecture.md   # Module responsibilities, core selection, and architectural tradeoffs
├─ packaging.md      # Packaging inputs, app identity, asset binding, and delivery rules
├─ cheats.md         # Cheat formats, persistence, bundled presets, and core adaptation design
└─ verification.md   # Verification methods and pass criteria for builds, packaging, and runtime features
```

## Constraints

### Files and Structure

- Agents must not create new documents without explicit authorization.
- Content additions and edits must strictly follow the file responsibilities defined in Structure.

### Content Scope

- Documents in this directory are limited to rules, usage, design, and reusable verification methods.
- Do not include disclaimers, self-justification, fact-checking narratives, operation logs, or explanations of document organization and cleanup.
- Do not include collaboration timelines, execution milestones, or transient status statements such as "as of [date]", "completed in this round", "not yet verified", or "to be added later".
- State technical prerequisites, applicability conditions, and functional limitations relevant to actual use directly as rules or design descriptions.

### Post-operation Review

- After each operation that creates, edits, deletes, moves, or renames files in this directory, review the changes, adjacent content, and affected documents for compliance with file responsibilities and Content Scope.
- Review actual meaning rather than relying solely on keywords. Do not preserve the same irrelevant content by rewording it.
- Correct noncompliant content directly and repeat the review until it complies. Merely flagging it or appending an explanation is insufficient.
- The file-changing operation is complete only after the review and all required corrections are finished.
