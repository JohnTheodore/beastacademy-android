# Beast Academy input reliability

Read `docs/incidents/input-reliability-status.txt` and `docs/adb-input-runbook.txt`
before debugging a tablet input failure. This is a recurring unresolved problem:
the user reports at least three incidents and two or three unsuccessful attempts
to fix it permanently. A passing narrow regression or successful live workaround
is not proof that recurring frozen input is fixed.

When the user asks for wireless ADB debugging, preserve the currently running
app and lesson. Do not restart, reload, force-stop, reinstall, switch WebView
providers, clear data, navigate away, unlock/lock, or run screen-off experiments
without the user's explicit authorization. Capture evidence first. Authorization
from the current user overrides these defaults, but never infer restart approval
from “keep going”, “debug”, or “fix it”.

Use `scripts/capture-input-diagnostics.sh SERIAL OUTDIR` before test input or
recovery. New builds support release-safe private export and shell-authorized
inspection without reload; the runbook documents both. Export first, then
enable inspection only when needed, and restore the disabled state afterward
if you enabled it. Verify process/document identity before and after any authorized live
change. Capture into a private directory and preserve a durable copy outside
`/tmp`. Record versions, native/DOM transition, reproduction limits, code changes,
and the user's actual validation. Never store pairing codes in repository files.

Use the `.inputlab` application (`-PinputLab=true`) for destructive native tests,
preferably on an isolated emulator. Never instrument the child's main package.
Do not wake or manipulate the physical tablet merely to finish routine checks.

Keep proven observations, source hypotheses, temporary recovery, and permanent
prevention separate. Consult the incident ledger before claiming a fix or
repeating an earlier experiment. Update that ledger and the runbook when new
evidence or a deployment changes what the next ADB session can capture.
