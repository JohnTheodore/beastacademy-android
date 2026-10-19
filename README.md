# Beast Academy Android Wrapper

![Version](https://img.shields.io/badge/version-0.7.8-blue.svg)

A streamlined Android application that provides a native container for the Beast Academy online learning platform. This project is unofficial, and it's not affiliated with beastacademy.com at all. I built this for my kids.

## Visual Assets

### Launcher Icon & Splash Logo
| Launcher Icon | Splash Logo |
| :---: | :---: |
| ![Launcher Icon](app/src/main/res/drawable/logo.png) | ![Splash Logo](app/src/main/res/drawable/splash.png) |

## Overview

This project is a WebView-based Android application designed to provide a seamless experience for Beast Academy users on Android devices.

### 0.7.8 Lesson Input Compatibility

Installs a guarded compatibility patch before each new Beast Academy document,
including reloads. For qualified shared engines, it isolates a drag's owning
finger, tolerates previously ended contacts reappearing, and clears engine input
tracking on interruption without synthesizing a drop. Unknown website versions
or changed handlers keep their original behavior and report limited protection.

This is an incremental release. Per-tool cancellation cleanup, separate controls,
and real-lesson validation on the affected Vanadium provider remain incomplete;
the recurring incident stays open. See [implementation and limits](docs/retired-touch-guard.txt)
and [release validation](docs/releases/v0.7.8.txt).

### 0.7.7 Input and Session Reliability

The source now uses an Activity-owned native WebView host, explicit visibility
state, complete gesture cancellation, and generation guards for late callbacks.
An unresponsive renderer offers wait/reload; actual renderer exits still recover
automatically. The tablet's installed 0.7.6 app has not been replaced.

See [input architecture and design precedents](docs/input-architecture.txt) and
[the October 19 investigation](docs/incidents/2026-10-19-input/findings.txt).
The experimental early canvas gesture policy remains debug-only pending
validation against the natural Vanadium failure. Persistent private diagnostics,
automatic incident snapshots, and shell-protected release export/inspection
prepare future wireless ADB investigations without requiring a reload. See the
[capture-first runbook](docs/adb-input-runbook.txt) and
[recurring-incident status](docs/incidents/input-reliability-status.txt).

### 0.7.6 Release Highlights:
*   **Interrupted Gesture Repair:** Ends interrupted touch gestures with every active contact. This fixes the reproduced cancellation failure; the separate October 19 provider failure remains under investigation.
*   **Reproduction Tests:** Native input tests reproduce the frozen state with the repair disabled and verify that repaired gestures remain usable through 50 interruptions without reloading.

### Previous Reliability Improvements:
*   **Hung-Renderer Detection:** Versions before 0.7.7 automatically recovered; 0.7.7 preserves an unresponsive renderer and offers wait/reload.
*   **Robust Renderer-Crash Recovery:** Automatically replaces failed WebViews while preserving session state.
*   **Immediate Screen-off Audio Muting:** Uses the native WebView mute API before pausing page processing, while preserving the lesson on supported WebView providers.
*   **Improved Full-screen Cleanup:** Centralized handling of custom media views to prevent touch blocking.
*   **TTS Generation Safety:** Prevents races between Text-to-Speech callbacks and WebView lifecycle.

## Key Features

*   **Native Speech Synthesis Bridge:** Implements a custom `AndroidSpeechSynthesis` bridge that polyfills the web `speechSynthesis` API using native Android TTS, ensuring that lesson narrations work reliably.
*   **Audio Context Management:** Includes specialized logic to handle and resume `Howler.js` and Web Audio contexts, bypassing standard browser restrictions on autoplay audio.
*   **Desktop Experience:** Uses a custom Desktop User Agent to ensure the full Beast Academy web interface is rendered correctly on tablet devices.
*   **Native Browser Host:** A stable native view hierarchy with sibling controls and **Edge-to-Edge** support. Compose remains in the debug input lab to reproduce the previous hosting behavior.
*   **Performance Optimized:** Utilizes hardware acceleration and advanced WebView features like `DOCUMENT_START_SCRIPT` for early polyfill injection.
*   **Full-screen (Edge-to-Edge) implementation.**

## Project Structure

*   `MainActivity.kt`: Activity-owned browser session, screen/lifecycle events, cover monitor, and file picker.
*   `BrowserChrome.kt`: Native browser host and sibling progress/recovery/cover controls.
*   `BeastAcademyWebView.kt`: Native touch-stream ownership and diagnostic observation.
*   `SessionVisibility.kt` / `PauseCompletion.kt`: Testable lifecycle and asynchronous pause coordination.
*   `WebViewScripts.kt`: Browser-side speech and audio adapters.
*   `BeastAcademyWebViewController.kt`: Authoritative controller for WebView lifecycle and reliability.
*   `AndroidSpeechSynthesis.kt`: Native implementation of the Text-to-Speech bridge.
*   `res/`: Optimized resources including an adaptive launcher icon specifically tuned for the Pixel Tablet experience.

## Build Requirements

*   Android SDK 37.
*   Android Gradle Plugin 9.3.1 and a compatible JDK (the current Android Studio bundled JDK works).
*   The included Gradle 9.6.1 wrapper.

## How to Build

1. Clone the repository.
2. Open the project in Android Studio.
3. Run the `:app:assembleDebug` task or click the **Run** button in Android Studio.

## License

MIT License
