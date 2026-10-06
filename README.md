# DeX RDP

An Android remote-desktop (RDP) client built for **Samsung DeX** — a recent
Samsung phone, a physical keyboard, and a Windows PC on the LAN. It targets
desktop-class input and display: 4K output, full keyboard-shortcut passthrough
(including the Windows/Super key), and RDP over both TCP and UDP.

## Features

- **4K display support**
- **Full keyboard passthrough** — Super/Win key and extended scancodes reach the
  remote PC via an accessibility-based physical-keyboard service
- **Mouse input** — left/right/middle buttons, vertical and horizontal scroll,
  and back/forward (X1/X2) side buttons
- **TCP and UDP transport** — FreeRDP for the standard TCP path; an IronRDP
  engine that negotiates the RDP-UDP sideband (soft-sync + DVC tunnel-switching)
- **Clipboard and audio** — via FreeRDP (engine-dependent)
- **In-app diagnostics** — TCP port probe, engine failure reasons, shared log viewer

## Two engines

The app ships two connect paths, selectable from the launcher:

| Button | Engine | Transport | Status |
| --- | --- | --- | --- |
| **Connect** | FreeRDP | TCP | Works today: connect, 4K, Super-key passthrough, clipboard, audio, diagnostics |
| **Connect (UDP engine)** | IronRDP | RDP-UDP | Under active development; currently falls back to TCP |

RDP-UDP is not just "open a UDP socket" — it requires soft-sync and DVC
tunnel-switching (MS-RDPEMT, MS-RDPEUDP, MS-RDPEUDP2). IronRDP implements these;
FreeRDP does not, which is why the project carries both engines.

## Repository layout

- `freerdp-upstream/` — FreeRDP fork plus the Android Studio project.
  - `client/Android/Studio/app/` — the DeX RDP app (Kotlin/Compose launcher,
    diagnostics, engine screens). Package `com.dexrdp`.
  - `client/Android/Studio/freeRDPCore/` — the FreeRDP Android library engine.
- `ironrdp/` — vendored IronRDP (Devolutions), including the JNI engine:
  - `crates/ironrdp-dexrdp-client/` — JNI engine over `ironrdp-client`.
  - `crates/ironrdp-dexrdp-udp/` — early C shim over IronRDP's UDP crates.
- `docs/` — design notes.

## Building

Toolchain: Java 17, Android SDK (API 37, build-tools 37), NDK 29, CMake 4.1.2,
Rust 1.94.1 (pinned by `ironrdp/rust-toolchain.toml`) plus `cargo-ndk`.

Signing credentials live in `release.properties`, which is **git-ignored**. Copy
`freerdp-upstream/client/Android/Studio/release.properties.template` and fill in
your own keystore before building a release APK.

### Android APK (FreeRDP engine)

```
cd freerdp-upstream/client/Android/Studio
ANDROID_HOME=/path/to/android-sdk ./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

### IronRDP engine native library (arm64)

```
source ~/.cargo/env
cd ironrdp
ANDROID_NDK_HOME=/path/to/android-sdk/ndk/29.0.13113456 \
  cargo ndk -t arm64-v8a -o /tmp/clientout build --release \
  -p ironrdp-dexrdp-client
# copy the resulting .so into
# freerdp-upstream/client/Android/Studio/app/src/main/jniLibs/arm64-v8a/
```

## Notes

- The keyboard accessibility service is exported and labelled
  "DeX RDP Physical Keyboard". Sideloaded installs need **Allow restricted
  settings** enabled to turn it on.
- Security is negotiable (Auto by default); NLA is not forced.

See [`HANDOFF.md`](HANDOFF.md) for the full project state, architecture, and
current development notes.

## License

MIT — see [`LICENSE`](LICENSE). Vendored FreeRDP and IronRDP components retain
their own upstream licenses.
