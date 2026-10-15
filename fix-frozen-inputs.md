# Beast Academy input recovery plan

P0 incident: https://github.com/JohnTheodore/beastacademy-android/issues/1

Status: A reproducible cancellation defect has been repaired and installed as version 0.7.6-input-diag (13). Baseline tests strand contacts, including contact 2; repaired tests clear them and allow subsequent dragging in the same WebView. All nine device tests now pass, including an explicit unrepaired frozen-drag reproduction and 50-interruption prevention test, along with ten unit tests. The original live broken session was lost earlier, and its triggering interaction remains unknown. Keep the P0 incident open for monitoring rather than claiming that every possible trigger is eliminated.

Current constraints: Do not lock the screen or run screen-off tests. Preserve any future real broken session without restart, reload, navigation, or reinstall. The historical research and recovery records below describe earlier stages; the execution results at the end supersede their pending status.

## Completion audit

The entire original investigation plan is **not complete**. The implemented prevention fix, baseline reproduction, regression testing, installation, diagnostics, and incident documentation are complete. Remaining work and intentionally stopped work are explicit below:

- Complete: research similar reports and inspect their actual patches; reproduce incomplete cancellation on the affected tablet; compare Compose and native hosts; implement and install the repair; validate baseline failures and repaired behavior, including 50 interruptions without reloading; retain diagnostic evidence; restore sleep settings.
- Open: identify the exact interaction that triggered the original real session. The controlled reproduction establishes a matching mechanism, not that historical trigger. Capture a future natural transition if it recurs, preserving the live session.
- Open: validate a user-invoked recovery for an already-stranded contact without restarting or reloading. Earlier recovery experiments did not establish this; the installed fix prevents the reproduced defect rather than claiming to repair arbitrary pre-existing browser state.
- Not performed: compare different provider/framework versions and complete the broader interaction matrix (including edge gestures and pin/unpin). The proven cancellation defect was repaired on the affected configuration; these broader comparisons remain investigation work.
- Stopped by user constraint: screen-off tests. Earlier attempts did not establish successful dragging afterward. Do not resume screen-lock testing.
- Open: monitoring and P0 closure after sufficient real use. Normal release delivery is recorded below; the investigative limits remain open independently of publication.

### Additional environment verification

Read-only ADB checks confirm Pixel Tablet (tangorpro), Android 17 / API 37, fingerprint google/tangorpro/tangorpro:17/CP3A.261005.002.A1/2026100601:user/release-keys. Installed package is versionCode 12, versionName 0.7.5-diag, matching the checkout's version metadata; binary/source identity remains unverified. Local cached Compose UI artifacts are 1.11.4; confirm the installed APK's resolved dependencies before attributing framework behavior.

### Similar reports: actual code comparisons and fix status

**1. Candy Browser — closest architectural comparison; same root cause NOT verified.**

Report: https://github.com/sk2andy/candy-browser/issues/19
Fix: https://github.com/sk2andy/candy-browser/commit/cc26745edbbf0b4bfb77c16ceb8fda57473d2c63

Inspected the v0.29 source and full v0.29→v0.30 patch, including regression tests. Like our app, it embeds a WebView through Compose AndroidView and a native host/container. Its fix requests that parent views not intercept an accepted touch stream, releasing ownership at termination, unhandled initial input, focus loss, and detach. Our source has no corresponding parent-interception request. The maintainer reports premature ACTION_CANCEL after ACTION_DOWN and successful physical-device validation.

Verified shared architecture and an absent protection, not a shared causal defect. Candy's reported problem is interrupted scrolling; ours is a trusted DOM TouchList containing a stale contact after Android has no active contacts. We must demonstrate whether premature cancellation produces that state here. Their tests do not establish that browser touch lists clear correctly on our provider. Our gesture exclusions address system gesture regions, a different mechanism from parent interception.

**2. Flutter Android platform views — verified merged event-translation fix; different code path.**

Report: https://github.com/flutter/flutter/issues/176574
Merged fix: https://github.com/flutter/flutter/pull/178015

Inspected the Java/Dart patch and regression test. Flutter reconstructed multi-pointer MotionEvents using local pointer bookkeeping and could emit duplicates when it became inconsistent. The fix carries the original event's pointer count through translation. Our app uses Compose, not Flutter's AndroidTouchProcessor or AndroidViewController, so this specific defect and patch do not apply. It demonstrates why interop must be measured at both boundaries rather than assuming the webpage is at fault.

**3. Microsoft WebView2 — very close symptom; no verified transferable fix.**

https://github.com/MicrosoftEdge/WebView2Feedback/issues/3685

A reporter observed a fixed-position contact causing subsequent input to appear as a second touch under artificial CPU limits. This is Windows WebView2, not Android WebView. The thread does not establish a validated fix we can transfer. Similarity is useful evidence of a failure class, not proof of prevalence or our trigger.

**4. Rein missing touchcancel handler — reject as a verified fix precedent.**

https://github.com/AOSSIE-Org/Rein/issues/75

The maintainer could not reproduce it and closed it for lack of clarity; linked patches were not merged when checked. This cannot substantiate a known successful fix. Our captured puzzle-canvas/document listener inventory also lacks a dedicated touchcancel handler, but that would concern application drag bookkeeping. It does not by itself explain why trusted browser events contain a nonexistent contact; the captured lesson mouseDownView was null, not stuck pressed.

### What the platform sources establish

- Android's gesture contract ends a stream with ACTION_UP or ACTION_CANCEL: https://developer.android.com/reference/android/view/MotionEvent
- Compose's current PointerInteropFilter already forwards terminal events and synthesizes cancellation when input is taken away. Inspect the actual shipped version before concluding our app needs extra cancellation: https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/input/pointer/PointerInteropFilter.android.kt
- Chromium maintains touch contacts by ID and removes released/canceled contacts during processing. That makes a lost, mistranslated, or mishandled termination a plausible explanation, not a proven trigger: https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/input/touch_event_manager.cc
- Touch Events defines touches as current contacts, not a page-maintained drag array: https://www.w3.org/TR/touch-events/

No source reviewed establishes that this exact Android 17 / Vanadium 155 incident is common, or identifies a confirmed upstream fix for it.

### Execution plan, with decision gates

1. **Preserve evidence and instrument without changing behavior.** Keep a bounded, private event history at Activity dispatch, native host dispatch, WebView dispatch/onTouchEvent, and document capture. Include action/index, pointer IDs/count, timestamps, input source/device, delivery results, view generation, focus/lifecycle/cover transitions; capture DOM touch and pointer start/end/cancel lists and targets. Record all terminal events; sample moves to limit overhead. Correlate sequences/times rather than assuming native and DOM IDs are numerically identical. Existing ACTION_DOWN-only logs cannot recover the original loss.

2. **Capture an actual transition from working to broken.** On a test session, first establish one-contact press → zero-contact release. Exercise repeated drags; two/three fingers with different lift orders; bottom-edge gestures; pin/unpin; screen-off mid-drag; background/focus transitions; cover overlay; and rapid input during loading. Prioritize multi-contact and bottom-edge cases because the stale contact was ID 2 at y=765.5 of an 800px viewport, but those clues do not prove the trigger. Preserve unsaved work before resetting the child's session. Extend and restore sleep timeout during active device debugging.

3. **Find the first disagreement.** If Activity sees the ending but WebView does not, investigate host/Compose routing and interruption timing. If a correct ending reaches WebView but the DOM retains the contact, investigate provider/Chromium conversion and processing. If the native event sequence is already malformed at Activity entry, investigate platform/input integration. An OS snapshot with zero contacts alone cannot distinguish these paths.

4. **Use controlled comparisons.** Run identical gesture scripts against a tiny local touch-test page and the affected lesson. Compare our Compose host with a plain native FrameLayout host on the same device/provider. Compare provider or framework versions on a separate test environment where supported. Change one factor per trial. Replay a captured failure sequence through the native path; do not use synthetic DOM events as a substitute for browser input state.

5. **Choose the fix from that evidence.** Test Candy-style parent interception protection if traces show premature arbitration. Repair a proven missing termination at the ownership/interruption boundary if found, preserving valid multi-pointer events. If WebView receives valid events but strands contacts, reduce and report upstream and test an appropriate provider update or targeted workaround. Verify whether native cancellation actually clears the existing stale state; do not assume it does. Avoid rewriting touches, disabling multitouch, or applying blanket cancel-on-every-down.

6. **Provide recovery and verify.** Separately validate a user-invoked recovery path when JS is responsive but touch state is broken, with explicit unsaved-work handling. For the causal fix, require a before-fix failing reproduction and after-fix successful repeated runs; all contacts clear on release/cancel, normal single/two-finger use remains correct, interrupted gestures recover, and no accidental submissions or reload loops occur. If the original trigger remains unknown, label any shipped mitigation as such and retain diagnostics; do not report root cause as proven.

The research stage did not change app source. Subsequent live recovery experiments are recorded below.

## Live recovery experiment

Date: 2026-10-15. The user authorized testing recovery over wireless ADB without first reloading the lesson.

1. Preserve the current browser target, puzzle state, and touch event evidence. Temporarily extend the sleep timeout and restore it afterward.
2. Repeat a small single-finger drag to establish whether the same stale contact remains.
3. Send a native cancellation through Android input during an injected gesture, and inspect trusted DOM contact lists. A shell command succeeding is not proof that WebView received cancellation.
4. If that does not recover input, test a browser-protocol touch cancellation separately. This exercises a different layer and cannot validate a native wrapper fix by itself.
5. Verify recovery using an Android-injected drag, inspect the puzzle engine and contact lists, and ask the user to confirm real dragging. Do not submit an answer or reset the puzzle.
6. Remove temporary observers and restore device settings. Record both successful and unsuccessful experiments and their limits here.

### Results and loss of the live failure state

The original process was 8919. Testing began with the original browser target and the same stale contact 2 at (413.5, 765.5). The following sequence is preserved in local captures:

| Experiment | Observed result | Conclusion |
| --- | --- | --- |
| Baseline Android swipe | Trusted touchstart contained contacts 0 and 2; touchend left contact 2. | Original failure still present. |
| Android DOWN followed by CANCEL | Trusted touchcancel removed contact 0 but left contact 2. The next swipe still saw two contacts. | Generic native cancellation did not recover the stale contact in this session. |
| Browser protocol cancellation without an injected start | Protocol rejected it with “Must send a TouchStart first to start a new touch.” | No recovery occurred. |
| Browser protocol start using contact 2, then cancellation | A complete start/cancel sequence in one protocol session produced a trusted touchcancel with touches=[] and changed contact 2. | The stale DOM contact could be removed, but native recovery was not established. |
| Subsequent native swipes | Activity/WebView logged input, but document observers saw no corresponding touch events. | Clearing the DOM contact alone did not demonstrate recovery. |
| Browser emulation toggles and a complete native three-contact press/release | No restoration of native DOM touch delivery was observed. | These attempts did not demonstrate recovery. |
| App force-stop and restart | New process 13076; original browser target destroyed. Returning to the lesson showed the saved trial arrangement. | The original live failure state is lost. No permanent fix is proven. |

The browser-protocol experiments were initially split across different protocol sessions; a start was accepted and its cancellation was rejected in another session. Later commands used one session. This introduced additional synthetic-input state and may explain the later native-event suppression. Treat those later results as potentially affected by the experiment, not as proof that the original defect has multiple causes. Chromium's injection path uses a touch emulator that can suppress native streams while injected streams remain active. The exact internal state was not captured.

At approximately 12:49 EDT, the agent restarted the app to pursue immediate usability. That destroyed the valuable live reproduction. The user objected to losing it. Saved logs, screenshots, and lesson state do not preserve the browser's internal failure state. Further resets were stopped.

The lesson's trial state after restart exactly matched the saved state without needing a state write:

```json
{"keyFocusID":null,"trial":[[3,0,[[1],[1],[1],[1],[1]]]]}
```

This preserves the puzzle arrangement, not the input bug. Ordinary dragging after restart was not independently verified before stopping further tests in response to the user's objection.

### Revised next steps

1. Preserve any future broken session. Do not restart, reload, recreate the WebView, update the provider, or navigate away without a separate explicit agreement that the live reproduction will be destroyed. Saving puzzle progress does not preserve debugging state.
2. Before reproducing the failure again, add passive native/DOM tracing covering all pointer transitions. Record the working-to-broken transition rather than relying only on post-failure snapshots.
3. Keep browser input experiments in one persistent protocol session with every injected start paired with a verified end/cancel. Check real native input separately. Do not mistake synthetic-input interference for the original defect.
4. Retain the Candy parent-interception hypothesis as unverified. The live generic-cancel test failed, so do not ship generic cancellation as the established recovery fix.
5. Reproduce on a separate test session/device where possible. Keep the child's future broken session available for comparison.

### Evidence and cleanup

Local captures are under `/tmp/beastacademy-diagnostics/`: `recovery-baseline.json`, `recovery-native-cancel.json`, `recovery-cdp-empty-cancel.json`, `recovery-cdp-id2-cancel.json`, `recovery-cdp-id2-sequence.json`, `recovery-native-verification.json`, `recovery-native-multitouch.json`, `recovery-engine-before.txt`, `recovery-engine-restarted.txt`, and `puzzle-state-to-restore.json`. They are temporary files; the essential observations are recorded above.

The temporary Android helper was removed from `/data/local/tmp`. Restart removed the old process's temporary JavaScript observers. The sleep timeout was restored to its original 300000 ms (5 minutes), and DevTools forwarding was removed. No app source fix was applied.



## Implementation and prevention validation — 2026-10-15

### Reproduced cause

An isolated local touch page on the affected tablet reproduces a concrete native-to-browser cancellation defect without the Beast Academy website or browser-protocol input injection. Removing a Compose AndroidView host during multitouch sends a minimal ACTION_CANCEL containing dummy pointer 0. WebView cancels only the listed contact; other contacts remain active in the document. In the closest reproduction, press contacts 0 and 2, lift 0, and interrupt the host: cancellation names 0 even though only 2 remains. Baseline leaves trusted DOM contact 2 active, matching the original incident's stale-contact signature. Ordinary complete touch streams pass in the baseline.

This proves a defect in our input path and explains how a finger that has physically lifted can remain in the browser's contact list. It does not establish which interaction interrupted the child's original gesture. Candy's parent-interception patch is not the chosen repair because our measured defect is the cancellation's missing pointer IDs. Generic cancellation after a contact has already been lost also remains an unproven recovery method.

### Implemented repair

- `BeastAcademyWebView` tracks active native contacts, removes lifted contacts, and expands incomplete cancellations to contain the complete active set with the original stream metadata. Focus loss, detachment, and the controller's cover/pause path end known active gestures. Physical moves/lifts after cancellation do not restart tracking before a new DOWN.
- Normal touch delivery and valid complete cancellations remain intact. The repair uses native events, with no page reload, timer, synthetic DOM events, disabled multitouch, or restart workaround.
- Debug builds retain bounded private native/DOM traces and preserve the first suspected stale-contact snapshot. `scripts/capture-input-diagnostics.sh` reads evidence without resetting or navigating the app. Asynchronous DOM reporting can produce a suspected warning; it is diagnostic, not proof or an automatic reset trigger.
- A debug-only, separate `com.example.beast_academy_apk.inputlab` package provides a local fixture and a repair-disabled baseline. Instrumentation refuses to target the child's application package.

### Before/after evidence

Baseline host removal leaves contacts `[1,2]`; baseline removal with only nonzero contact 2 leaves `[2]`. These are expected failing regression reproductions, saved in `docs/incidents/2026-10-15-input/`.

The repaired seven-test device suite passes repeated three-contact gestures in Compose and native hosts, host removal, background interruption, nonzero-contact interruption, valid full cancellation, and the cover cancellation path. A separate eighth test repeats the nonzero-contact host interruption 50 times in one WebView. After every interruption it verifies zero active contacts and a working single-finger drag. It also asserts unchanged `performance.timeOrigin`, establishing that the document did not reload between failures and successful drags. All 50 repetitions passed.

Ten existing unit tests passed. Debug and unsigned release APK builds passed. Installed version 0.7.6-input-diag (13) was also checked on the actual lesson: trusted native dragging reached the puzzle engine (`isDragging`, piece `isMoving` and `hasMoved` true), release left zero contacts, and the saved puzzle arrangement matched. This actual-lesson check is a smoke test after installation, not the prevention proof; the same-document interruption tests provide the latter.

### Deployment, cleanup, and remaining limits

The fixed debug build is installed. A stable APK copy is `app/build/outputs/apk/beastacademy-0.7.6-input-diag.apk`; the usual debug output path can contain the isolated lab build after test builds. The test packages were removed after validation. The original five-minute sleep timeout (300000 ms) was restored. After the final prevention test, the child's app retained process 18771; bringing it forward did not restart it. No further screen-lock tests were run after the user's prohibition.

Earlier screen-off testing cleared contacts but did not validate a subsequent drag, so that scenario is explicitly unverified. No provider-version comparison was completed. The original real-world triggering interaction is still unknown, and prevention of unrelated provider/platform defects has not been established. Keep diagnostics and the P0 incident open; if a real failure recurs, capture the first transition while leaving the session alive. Do not describe normal dragging after a restart as proof that the bug is fixed.


## Normal release v0.7.6

Requested delivery: commit and push the fix and regression tests, then publish a normal GitHub release with the debug APK, following v0.7.4's naming and packaging. Version metadata is 0.7.6, code 13; the release has no diagnostic suffix and is not a prerelease. The uploaded asset is `beastacademy-android-v0.7.6.apk`, with the main application ID and the same debug signing certificate as previous release APKs.

The suite now also contains `unrepairedCancellationReproducesFrozenSingleFingerDrag`. It deliberately disables the repair, strands contact 2 through host interruption, then verifies that a native single-finger drag is ignored while the phantom contact persists. It asserts that the document did not reload. The companion repaired tests exercise the same interruption and verify no stranded contacts plus a delivered drag. This tests the failure's user-visible consequence as well as the browser contact list. The debug fixture keeps its window awake; tests stop immediately if the tablet is locked and do not change its lock state.

Release: https://github.com/JohnTheodore/beastacademy-android/releases/tag/v0.7.6

Publishing the normal release does not complete the remaining investigation tasks in the completion audit above, or establish recovery from an arbitrary pre-existing frozen state. The child's currently installed diagnostic-version build was not replaced during release preparation, preserving the running session.


Release validation completed: all nine native device tests passed in one run (`test-normal-release-suite.txt`), including the repair-disabled frozen-drag reproduction and 50 no-reload interruptions. Ten unit tests, full debug lint, and debug/release builds passed for normal version 0.7.6 (13). The APK's main application ID, version, signature, and SHA-256 were verified. The first release test attempt was stopped because the device was already locked; the successful run followed the user's unlock. Cleanup restored 300000 ms sleep timeout and removed the lab packages while retaining main process 18771.
