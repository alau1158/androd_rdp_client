# DeX RDP — project handoff

Android remote-desktop client for Samsung DeX. Written for one user's setup:
DeX on a recent Samsung phone, physical keyboard, connecting to a Windows PC on
a LAN.

## Requirements (original ask)

1. 4K resolution support
2. Full keyboard shortcut passthrough (Super/Win key reaches the remote PC)
3. TCP **and** UDP transport (UDP is the whole point — "TCP-only is useless")
4. Deliverable: installable signed APK

## Repository

- Remote: `git@github.com:alau1158/androd_rdp_client.git`
- Branches `main` and `master` are kept identical.
- Root: `/home/alau/android_rdp`
- `release.properties` is **git-ignored** (holds keystore passwords). Copy
  `freerdp-upstream/client/Android/Studio/release.properties.template` and fill in
  your own signing key to build a release APK.

## Layout

- `freerdp-upstream/` — FreeRDP fork + the Android Studio project.
  - `client/Android/Studio/app/` — the DeX RDP app (Kotlin/Compose launcher,
    diagnostics, engine screens).
  - `client/Android/Studio/freeRDPCore/` — the FreeRDP Android library engine.
- `ironrdp/` — vendored IronRDP (Devolutions). Contains:
  - `crates/ironrdp-dexrdp-udp/` — early C shim over IronRDP's UDP crates.
  - `crates/ironrdp-dexrdp-client/` — JNI engine over `ironrdp-client`
    (the current UDP path).
  - `crates/ironrdp-client/Cargo.toml` — patched: TLS backend switched from
    `aws-lc-rs` to `ring` (cross-compiles cleanly for Android).

## Two engines in the app

- **FreeRDP engine** — the launcher's plain **Connect** button. Works today:
  connects, Super-key passthrough, 4K, clipboard, audio, diagnostics, traditional
  scroll. TCP only.
- **IronRDP engine** — the **Connect (UDP engine)** button. Negotiates the
  RDP-UDP sideband. Under active development.

## Why the engine switch

RDP-UDP is not just "open a UDP socket". It needs **soft-sync** and **DVC
tunnel-switching**. FreeRDP has none of that (`grep` for soft-sync / tunnel data
in `libfreerdp` is empty). IronRDP implements all of it. Hence the move.

RDP-UDP facts:
- Server-initiated via the Initiate Multitransport Request PDU (MS-RDPBCGR
  2.2.15.1). Confirmed the host offers reliable UDP `0x0001` (user's Wireshark).
- Specs: MS-RDPEMT, MS-RDPEUDP, MS-RDPEUDP2.
- UDP carries the graphics dynamic channel; input/control stay on TCP.

## Build commands

FreeRDP/Android APK:
```
cd freerdp-upstream/client/Android/Studio
ANDROID_HOME=/home/alau/android-sdk ./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

IronRDP engine native lib (arm64):
```
source ~/.cargo/env
cd ironrdp
ANDROID_NDK_HOME=/home/alau/android-sdk/ndk/29.0.13113456 \
  cargo ndk -t arm64-v8a -o /tmp/opencode/clientout build --release \
  -p ironrdp-dexrdp-client
# then copy the .so into
# freerdp-upstream/client/Android/Studio/app/src/main/jniLibs/arm64-v8a/
```

Toolchain: Java 17, Android SDK (API 37.2, build-tools 37), NDK 29, CMake 4.1.2,
Rust 1.94.1 (pinned by `ironrdp/rust-toolchain.toml`) + `cargo-ndk`.

## Notable fixes already landed (FreeRDP engine)

- Missing Application class caused an instant crash on connect (session registry
  NPE). Fixed via `DexRdpApp extends GlobalApp`.
- A clipboard event could tear down a healthy session (`android_check_handle`
  treated as fatal). Fixed in `android_event.c` / `android_freerdp.c`.
- Keyboard accessibility service was `exported="false"` and mislabelled; now
  exported and labelled "DeX RDP Physical Keyboard". Needs "Allow restricted
  settings" for the sideloaded app to enable it.
- Forced NLA removed; security is negotiable (Auto default).
- Diagnostics added: TCP port probe, engine failure reason, in-app log viewer.

## Current blocker (as of last build 1.0.17)

The IronRDP engine crashes natively the moment **Connect (UDP engine)** is used.
The app log showed **no engine lines at all**, i.e. a native crash before any
Rust logging — not a Java exception.

1.0.17 adds:
- Kotlin breadcrumbs written to the shared log (`engine: ...`) at each step:
  before class load, after setLogPath, before/after nativeConnect.
- Rust SIGSEGV/SIGABRT/SIGBUS/SIGILL handlers that dump a backtrace to the log.
- `nativeSetLogPath` + a tracing subscriber so the engine logs to `freerdp.log`.
- Session-thread panics caught and logged.

**Next step:** read the 1.0.17 log from the app's Copy log after a crash; the last
breadcrumb / native backtrace pinpoints the failure. Then fix and rebuild.

## Engine JNI surface (com.dexrdp.engine.NativeRdp)

`nativeConnect(host, port, user, pass, domain, w, h, frameBuffer, callback)`,
`nativeSendKey(handle, scancode, down)`, `nativeSendMouse(handle, x, y, flags)`,
`nativeDisconnect(handle)`, `nativeFree(handle)`, `nativeSetLogPath(path)`.

Callback: `onConnected`, `onFrame(buffer, w, h)`, `onFailure(reason)`,
`onTerminated`. Frames are ARGB in a direct ByteBuffer owned by Kotlin.

Known engine-path gaps: Super-key passthrough not yet wired (accessibility
service currently feeds only the FreeRDP client); keyboard mapping is a subset;
no clipboard/audio; mouse absolute positioning likely needs `MouseEventEx`.
