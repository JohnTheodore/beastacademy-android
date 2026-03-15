# Beast Academy Android Wrapper

![Version](https://img.shields.io/badge/version-0.4-blue.svg)

A streamlined Android application that provides a native container for the Beast Academy online learning platform. This project is unofficial, and it's not affiliated with beastacademy.com at all. I built this for my kids.

## Visual Assets

### Launcher Icon & Splash Logo
| Launcher Icon | Splash Logo |
| :---: | :---: |
| ![Launcher Icon](app/src/main/res/drawable/logo.png) | ![Splash Logo](app/src/main/res/drawable/splash.png) |

## Overview

This project is a WebView-based Android application designed to provide a seamless experience for Beast Academy users on Android devices.

## Current Version: 0.2
* Initial release of the Beast Academy Android wrapper.
* Implementation of the native Speech Synthesis bridge.
* Custom adaptive launcher icon and splash screen.
* Refactored and cleaned up codebase.
* Full-screen (Edge-to-Edge) implementation.

## Key Features

*   **Native Speech Synthesis Bridge:** Implements a custom `AndroidSpeechSynthesis` bridge that polyfills the web `speechSynthesis` API using native Android TTS, ensuring that lesson narrations work reliably.
*   **Audio Context Management:** Includes specialized logic to handle and resume `Howler.js` and Web Audio contexts, bypassing standard browser restrictions on autoplay audio.
*   **Desktop Experience:** Uses a custom Desktop User Agent to ensure the full Beast Academy web interface is rendered correctly on tablet devices.
*   **Modern Android UI:** Built with **Jetpack Compose** and **Material 3**, featuring full **Edge-to-Edge** support for an immersive learning environment.
*   **Performance Optimized:** Utilizes hardware acceleration and advanced WebView features like `DOCUMENT_START_SCRIPT` for early polyfill injection.

## Project Structure

*   `MainActivity.kt`: The main entry point using Compose to host the `WebView`.
*   `AndroidSpeechSynthesis.kt`: Native implementation of the Text-to-Speech bridge.
*   `res/`: Optimized resources including an adaptive launcher icon specifically tuned for the Pixel Tablet experience.

## Build Requirements

*   Android Studio Ladybug or newer.
*   Android SDK 34+.
*   Gradle 8.0+.

## How to Build

1. Clone the repository.
2. Open the project in Android Studio.
3. Run the `:app:assembleDebug` task or click the **Run** button in Android Studio.

## License

MIT License
