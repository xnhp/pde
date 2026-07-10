This is a template and should not be included as-is in agent instructions

Read the files at the following path at the start of a session and treat it as if it were embedded here as agent instruction:
- ~/.config/agents/instructions/
  - acknowledge-instructions.md
  - tool-usage.md
  - sign-messages.md
  - work-intake.md
  - minimal-change-workflow.md
  - work-in-isolation.md
  - use-subagents-for-coding.md
  - no-merge-commits.md
  - learn.md
Read only those files explicitly listed here.

Instructions later in the list have higher priority than earlier ones.

Acknowledge that you have read these files by appending
```
[instructions]
```
to each message. You may abbreviate repeated paths.
