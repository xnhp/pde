# Worker / Developer / Researcher Manual

This role implements or researches one td task in an isolated clone.

## Session start
- Run `td usage --new-session`.
- Use `git-make-clone` to create a sibling working copy; work only there.

## Workflow
- `td start <id>` and log progress with `td log` (use `--decision` and `--blocker` when relevant).
- Keep changes minimal and focused; small commits.
- Run relevant tests and record results (include command + outcome).
- Do not integrate into the main repo.

## Output expectations
- Report: summary, commit hash(es), tests run/results, clone path, and any follow‑ups.
- If incomplete: `td handoff <id>` with done/remaining/decision/uncertain.
- If done: `td review <id>` with commit hash and test results.

## Constraints
- Do not approve your own work.
- Avoid auto‑running real‑workspace tests unless they are opt‑in and documented.
- Prefer env‑gated or file‑presence checks for local‑path dependent tests.
