# pde-remote-runner

Helper CLI that mirrors Eclipse's `RemoteTestRunnerClient` so headless PDE launches can
stream test events back into local logs or CI reporters. The tool binds to a localhost
port, prints JSON + human instructions with the selected port, then waits for the
PDE runtime to connect using the standard `-port <value>` argument.

## Build and Usage

- Run via Gradle:
  - `./gradlew :pde-remote-runner:run --args "--report teamcity --timeout 60"`
- Install standalone script:
  - `./gradlew :pde-remote-runner:installDist`
  - `./build/install/pde-remote-runner/bin/pde-remote-runner --listen-host 0.0.0.0`
- Typical workflow:
  1. Start the helper and capture the emitted JSON payload to learn the selected port.
  2. Launch `pde-resolver launch ... --programArg "-port <port>"` (or add to
     `launch.yaml`). The PDE runtime connects back automatically.
  3. Observe streaming logs / TeamCity service messages, or inspect the generated
     JUnit XML report.

## Options

| Option | Description |
| --- | --- |
| `--listen-host <ip>` | Host interface to bind. Defaults to `127.0.0.1`. |
| `--listen-port <port>` | Bind to a fixed port instead of an ephemeral one. |
| `--port-range start-end` | Pick the first free port inside the inclusive range. |
| `--timeout <seconds>` | Fail if no PDE client connects within the window. |
| `--report teamcity` | Emit TeamCity service messages for IDE/CI parsing. |
| `--report junit-xml:/path/file.xml` | Write a JUnit report summarizing the run. |
| `--include / --exclude <regex>` | Filter console logging to matching tests. |
| `--forward-log label=/path/to/pipe` | Tail a named pipe or log file and prefix
  its output with the given label (repeatable). |

All reports can be supplied more than once. For example, a CI job can request both a
JUnit artifact and TeamCity live service messages.

## JSON announcement example

```
{"host":"127.0.0.1","port":50521,"timeoutSeconds":120,
 "instructions":["Add '-port 50521' to PDE launch program arguments.",
   "Example: pde-resolver launch --programArg \"-port 50521\""],
 "issuedAt":"2025-12-01T20:15:04.312Z"}
```

Use the emitted information to wire up the follow-up launch (scripts can parse the JSON
line or rely on the human-readable hints printed right after it).

## Exit codes

- `0` – Session completed and reported no errors/failures.
- `1` – Remote tests reported failures/errors, or the connection ended prematurely.
- `2` – Parameter/port binding errors before listening.
- `3` – Timed out waiting for a PDE client to connect.

The CLI always surfaces the counts (`failures`, `errors`, `stopped`) in its summary so
CI logs stay actionable even without reports.
