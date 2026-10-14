# Pill DT2L (Double Tap Pill to Lock)

A lightweight and battery-friendly Xposed module designed to lock your device by double-tapping the gesture navigation bar (pill), without interfering with standard system gestures or Circle to Search.

## Features

- **Double Tap to Lock**: Fast and native screen-off by double-tapping the navigation handle.
- **Biometrics-Friendly**: Instantly locks the keyguard via native system calls while preserving fingerprint and face unlock capabilities (no fallback to mandatory PIN/password entry).
- **Non-Intrusive**: Runs purely as a passive observer—will not intercept or break full-screen navigation gestures (swipe up, quick switch) or Circle to Search (long-press).
- **Zero-GC & Ultra Battery Efficient**: Built with fast short-circuit evaluation on high refresh rate (120Hz+) touch streams, zero allocation during standard touch sequences, and precomputed display boundaries.

## Tested Environments

The module has been tested and verified on:

- **Google Pixel 10 Pro XL** — Android 17 QPR1 (Stock Pixel UI)
- **Redmi K40** — Android 15 QPR2 (GenesisOS / AOSP-based Custom ROM)

## Usage

1. Download and install the latest signed release APK from the GitHub Actions workflow artifacts or Releases page.
2. Open your Xposed framework manager (e.g., **LSPosed**).
3. Enable the module and make sure the target scope **System UI (`com.android.systemui`)** is selected.
4. Restart System UI (via the LSPosed menu or by running `su -c "killall com.android.systemui"` in Termux/shell).
5. Double-tap the centered bottom navigation pill on any screen to lock your device.

## Acknowledgements

- **Gemini 4 Argon** — For technical consulting, state machine design, and architectural optimizations.

## License

This project is licensed under the [MIT License](LICENSE).