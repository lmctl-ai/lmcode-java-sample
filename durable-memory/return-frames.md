# Invocation return frames

## Contract

The CLI creates one `ResultSink` for one loaded program and binds it to the
registry before `Program.run`. The same sink remains active through body and
selected entry dispatch. Do not reset it between entries or store it in the
per-run standard collection arena.

## Output ordering

Trace and entry-result lines remain in their existing order, followed by the
existing `{ok,result}` diagnostic line. Only after all selected entries and
the negative-ledger application check succeed, `Main` writes the terminal
`LMCODE_RETURN_BEGIN` / JSON / `LMCODE_RETURN_END` frame. No frame is emitted
for load, body, entry, or application failure. An untouched sink emits `null`.

## Verification

The synchronized canonical programs 360–368 cover scalar/collection/JSON
values, entry lifetime, and no-frame error paths. `sample-suite/run-suite.lms`
and `test.sh` compare stdout bytes and exit codes through the independent return
protocol validators.
