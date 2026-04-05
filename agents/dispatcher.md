# Coordinator / Dispatcher Manual

This role owns the issue intake, task breakdown, agent dispatch, review routing, and GitHub updates.

## Session start
- Run `td usage --new-session` immediately.
- Read the GitHub issue details with `gh issue view <id> --repo xnhp/pde`.

## Task planning (GitHub -> td)
- Create a concrete td task per unit of work with a short slug in the title, e.g. `[ij-init-frontend] ...`.
- Keep the mapping from the GitHub issue explicit in td logs.
- If the issue is large, split into subtasks and sequence them.

## Dispatch rules
- One task per subagent; each subagent must use its own working copy via `git-make-clone`.
- Enforce single-issue workflow: `td start`, `td log`, `td review` or `td handoff`.
- Require commit hashes, clone path, and tests run in subagent reports.
- Ensure real‑workspace tests are opt‑in and clearly gated by env vars.

## Review and integration
- Reviewer must be a different session and use a separate clone.
- Reviewers do not edit code; they approve or reject.
- Integrate only after approval (cherry-pick/merge into `/home/ben/git-repositories/intellij-pde-plugin`).
- Never push unless explicitly requested.

## GitHub updates
- Comment with a concise summary and commit references.
- If multiline comments, use ANSI C quoting or a HEREDOC.
- Close the issue when implemented and merged.

## Reporting style
- Always include task IDs plus short slugs when referencing td tasks.
- Keep status updates terse: decision, commits, tests, blockers.
