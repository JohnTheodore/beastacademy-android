# WebView stale contact reproduction

Incident: https://github.com/JohnTheodore/beastacademy-android/issues/1

On the affected Pixel Tablet running Android 17 and Vanadium WebView 155.0.8059.79.0, removing a Compose AndroidView host during native multi-touch produces a minimal cancellation containing only dummy pointer 0. WebView forwards cancellation for that pointer, and Blink keeps omitted contacts active. The local input fixture reproduces the failure without the Beast Academy website or DevTools input injection.

The nonzero-pointer case matches the original incident's contact identity: press pointers 0 and 2, lift pointer 0, then remove the Compose host. Baseline leaves DOM contact 2 active. This establishes a reproducible defect and a preventive fix; it does not establish which interaction triggered the original session's interruption.

## Repair

`BeastAcademyWebView` tracks active pointers delivered through its native dispatch boundary, excluding a pointer after ACTION_POINTER_UP. When a cancellation omits active IDs, it forwards a cancellation containing the complete active set, using their original stream metadata. Focus loss, detach, and the controller's pause/cover path also terminate a known active stream. Normal events and valid full cancellations retain their original delivery. This prevents stranded contacts; it does not recover arbitrary contacts already stranded before tracking began.

## Repeat the tests

Build the isolated package and its instrumentation APK:

```sh
./gradlew -PinputLab=true :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Run the baseline nonzero-contact reproduction with repair disabled only in the debug fixture. It should fail with remaining contact `[2]`:

```sh
adb -s "$SERIAL" shell am instrument -w -r -e baseline true \
  -e class com.example.beast_academy_apk.InputLifecycleInstrumentedTest#composeRemovalWithOnlyNonzeroPointerClearsContact \
  com.example.beast_academy_apk.inputlab.test/androidx.test.runner.AndroidJUnitRunner
```

Run the repaired suite:

```sh
adb -s "$SERIAL" shell am instrument -w -r \
  -e class com.example.beast_academy_apk.InputLifecycleInstrumentedTest \
  com.example.beast_academy_apk.inputlab.test/androidx.test.runner.AndroidJUnitRunner
```

Instrumentation refuses to run against the child's application ID. The isolated fixture is disposable and uses no account or lesson data. Full native multi-pointer events are injected through Android, not browser automation. When writing new tests, release every injected contact even if an assertion fails; Android 17 rejects subsequent DOWN events if a test strands injected contacts in its verifier.

## Preserve a real failure

Run `scripts/capture-input-diagnostics.sh "$SERIAL"`. It reads private trace files, logcat and system state without resetting the app. The debug APK retains bounded rotating traces and freezes the first suspected stale-contact trace. Review raw captures before sharing them; keep the live app session intact.

The text results in this directory record baseline failures and repaired device tests. Earlier fixture setup errors (incorrect initial coordinates and leftover injected contacts) were corrected before final validation; they are not product failures or claimed reproductions. Screen-off tests cleared DOM contacts but did not validate subsequent input delivery. They were stopped and removed at the user’s request; no further screen-locking tests are authorized.


Final validation: the seven-test repaired suite passed, followed by `repeatedInterruptionsKeepSamePageUsable` (50 interruptions in one WebView). Each cycle verifies zero remaining contacts, a delivered single-finger drag, and unchanged document `performance.timeOrigin`. See `test-prevention-stress.txt`. This distinguishes prevention from a successful drag after restart. Ten existing unit tests also passed. Version 0.7.6-input-diag (13) is installed; the P0 remains open because the original session's triggering interaction is unknown.


## Normal release validation (0.7.6)

All nine device tests passed together; see `test-normal-release-suite.txt`. The added `unrepairedCancellationReproducesFrozenSingleFingerDrag` test explicitly disables the fix, reproduces stale contact 2, and verifies that subsequent native one-finger movement does not reach the fixture's single-finger drag handler. Repaired companion tests assert cleared contacts and successful drags. The 50-interruption test is included in this full run.

An initial release-validation attempt encountered an already sleeping/locked tablet and was stopped before meaningful gesture validation. After the user unlocked it, the complete suite passed. Tests now fail immediately on a locked tablet; the fixture keeps its own window awake. The child's app retained process 18771, the lab packages were removed, and the original 300000 ms sleep timeout was restored.

`:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and `:app:assembleRelease` passed for normal version 0.7.6 (code 13). The release asset is the main application's debug APK, `beastacademy-android-v0.7.6.apk`, signed with the previous release certificate. The diagnostic-version APK already installed on the tablet was left running during publication preparation.
