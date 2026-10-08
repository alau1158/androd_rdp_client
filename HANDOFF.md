# DeX RDP — project handoff

Android remote-desktop client for Samsung DeX. One user's setup: DeX on a recent
Samsung phone, physical keyboard, connecting to a Windows PC on a LAN.

## Requirements (original ask)

1. 4K resolution support
2. Full keyboard shortcut passthrough (Super/Win key reaches the remote PC)
3. TCP **and** UDP transport (UDP is the whole point — "TCP-only is useless")
4. Deliverable: installable signed APK

## Repository

- Remote: `git@github.com:alau1158/androd_rdp_client.git` (pushes here use HTTPS +
  `gh auth git-credential`; a global `url.https://github.com/.insteadOf git@github.com:`
  rewrite forces HTTPS over SSH).
- Branches `main` and `master` are kept identical.
- Root: `/home/alau/android_rdp`
- `release.properties` is **git-ignored** (holds keystore passwords and the
  version). Copy `freerdp-upstream/client/Android/Studio/release.properties.template`.

## Layout

- `freerdp-upstream/` — FreeRDP fork + the Android Studio project.
  - `client/Android/Studio/app/` — the DeX RDP app (Kotlin/Compose launcher,
    diagnostics, engine screens).
  - `client/Android/Studio/freeRDPCore/` — the FreeRDP Android library engine.
- `ironrdp/` — vendored IronRDP (Devolutions) with two extra crates:
  - `crates/ironrdp-dexrdp-client/` — JNI engine over `ironrdp-client` (the UDP path).
  - `crates/ironrdp-dexrdp-udp/` — early C shim (superseded).

## Two engines in the app

- **FreeRDP engine** — launcher's plain **Connect** button. Rock solid today:
  connects, Super-key passthrough, 4K, clipboard, audio, correct timezone and the
  real Windows cursor, traditional scroll. **TCP only** (FreeRDP's Android build
  advertises multitransport but hard-declines UDP — see below).
- **IronRDP engine** — the **Connect (UDP engine)** button. Now completes a real
  RDP-UDP2 handshake against Windows. Under active development; see status.

## UDP status (as of 2026-10-07)

**It connects reliably now** (build 1.0.36+), but graphics are **laggy** (~2 fps
bursts) and throughput is well below TCP.

### The five interop bugs found and fixed (all committed)

1. **Client never advertised multitransport.** `ConfigBuilder` hard-coded
   `multitransport_flags: None`; the connector now sets
   `TRANSPORT_TYPE_UDP_FECR | SOFT_SYNC_TCP_TO_UDP` when UDP is enabled.
2. **Correlation id missing.** Windows only answers the client SYN when it carries
   the `RDPUDP_CORRELATION_ID_PAYLOAD` ([MS-RDPEUDP] 2.2.2.8). Generate a
   spec-compliant random 16-byte id and put it in the SYN.
3. **cookieHash byte order.** Windows compares the SYN's `cookieHash` against its
   SHA-256 digest held as UINT32s, so it wants the **DWORD-oriented (word-reversed)**
   view, not the canonical octet string. See `cookie_hash()` in
   `crates/ironrdp-rdpeudp-tokio/src/transport.rs`.
4. **Legacy final ACK for v3.** [MS-RDPEUDP2] 1.3.1: v3 switches to RDP-UDP2 right
   after the SYN+ACK and MUST NOT send the v1 final ACK. Windows parses the stale
   v1 ACK as a malformed v2 packet and never establishes. Skip it for v3.
5. **V2 data sequence base.** RDP-UDP2 starts a **fresh 16-bit DataSeqNum space at
   1**; the random 32-bit SYN ISNs are not carried over. Deriving the sequence from
   the ISN (as `transition_to_established` did) put the first data packet far
   outside Windows' receive window, so it silently dropped the ClientHello — the
   cause of the "flaky" connects (worked only when the random ISN landed in range).

### Earlier wins (committed)

- Timezone: advertise the device's real UTC offset (was `bias: 0`/UTC). Added
  `ConfigBuilder::with_timezone_info`; native side derives it via `libc::localtime_r`.
- Cursor: `with_pointer_software_rendering(true)` composites the real Windows cursor
  into the frame; `RemoteView` hides Android's own pointer (`PointerIcon.TYPE_NULL`).
- Logging: `EnvFilter` drops the per-bitmap fast-path DEBUG flood; logs inbound UDP
  and the handshake SYN hex.

### Lag / throughput — root cause found and fixed in 1.0.38

Re-reading `android-rdp.pcapng` (Wireshark, 2026-10-07) changes the earlier
picture:

- **The UDP sideband carries no graphics.** Every large server→client UDP packet
  is an RDP-UDP2 `AOA,DUMMY` probe (114 per connection, all sent in an ~8 ms burst
  right after the TLS tunnel comes up). The desktop content — ~20 MB over the
  same session — still flows over **TCP**. Only tiny (51–58 byte) DVC control
  rides the UDP sideband. So "UDP throughput" was never actually being measured;
  the desktop was running the legacy TCP path the whole time.
- The client does send **one standalone ACK per received packet** (22 × 18-byte
  ACKs, one per seq, in the first 2 ms of the dummy burst): IronRDP's
  `poll_transmit` emits the standalone ACK immediately when `ack_pending` instead
  of batching behind the delay timer. Real, but negligible (a few KB) — not the
  bottleneck.

**Actual bottleneck (client-side):** the JNI engine did **not** opt into dirty
regions, so every graphics update (a) repacked the entire 4K framebuffer into a
fresh `Vec<u32>` (`pack_desktop_update`, an 8.3M-pixel scan + ~33 MB allocation),
(b) ran an 8.3M-iteration per-pixel colour conversion into the Kotlin buffer, and
(c) had Kotlin copy the full 33 MB back into the bitmap. At 4K that pins the CPU
and thrashes the allocator/GC — which matches the "~2 fps" feel.

Fix (1.0.38): `ironrdp-dexrdp-client` now calls `RdpClient::with_desktop_updates()`
and blits only the changed region into the Kotlin-owned direct framebuffer
(`write_frame_region`). Per-update cost is now proportional to the damage, and no
full-frame allocation happens per frame. Kotlin side is unchanged.

### IronRDP upstream sync + Windows RDP-UDP interop (2026-10-08)

**Key discovery:** `winrdp.app` (github `AKolenda/winrdp`) is a Linux client on a
forked IronRDP that works over reliable UDP against live Windows 11. Its author
upstreamed the Windows-interop fixes to Devolutions/IronRDP as PRs #2007–#2017
(soft-sync with declined channels, channels+graphics on the tunnel, tunnel
auto-detect, RFX-progressive SRL decoding, bitmap cache across `ResetGraphics`,
UDP/EGFX options, resize, transport events).

**What was done (1.0.38–1.0.48):**

- 1.0.38: JNI engine opted into dirty regions (`with_desktop_updates` +
  `write_frame_region`), removing the full-4K repack per frame.
- 1.0.40: enabled EGFX (`RNS_UD_CS_SUPPORT_DYNVC_GFX_PROTOCOL`), which finally
  made Windows create the RDPGFX DVC and move it onto the UDP tunnel.
- 1.0.44: ported the fork's #2010 (RFX-progressive SRL) and #2011 (bitmap cache
  across reset) — the `Srl(Truncated)` decode failure and the black/blotchy
  regions are gone.
- 1.0.45: **replaced the whole vendored `ironrdp/` with the fork's `winrdp`
  branch** (all fixes + the new `ironrdp-autodetect` crate), keeping our JNI
  `ironrdp-dexrdp-client`. API deltas: `with_support_dyn_vc_gfx_protocol` →
  `with_graphics_pipeline`, and our `with_timezone_info` re-added. Ring TLS
  backend re-applied for Android (`ironrdp-tls/rustls-ring`,
  `ironrdp-rdpeudp-tokio/rustls-ring`).
- 1.0.46–1.0.48: the fork's UDP handshake omits three fields our old vendored code
  had added and Windows requires; re-added to
  `ironrdp-rdpeudp-tokio/src/transport.rs`:
  1. the 16-byte **correlation id** (`RDPUDP_CORRELATION_ID_PAYLOAD`),
  2. a random **snInitialSequenceNumber** (default 0 is silently ignored),
  3. the **cookie-hash DWORD byte order** (Windows compares the digest as UINT32s).

**Current blocker (1.0.48):** the UDP sideband still does not establish — the
server does not answer the SYN and the client falls back to TCP
(`UDP multitransport bootstrap failed failure="handshake-timeout"`). The SYN is
structurally correct now (random ISN + correlation id + reversed cookie hash,
verified in `rdp_udp8.pcapng`), so either a further field differs or the Windows
RDP-UDP stack is wedged after many test sessions. **Try restarting the Windows
RDP service / rebooting before more code changes.**

**Resume hints:** tshark is installed on the Windows host; SSH `alan-@192.168.1.97`
works with key auth. Compare the client SYN byte-for-byte against a known-good
build (captures `rdp_udp4.pcapng` = 1.0.42, `rdp_udp5.pcapng` = 1.0.44). Fork
clone is at `/tmp/opencode/ironrdp-winrdp`. When the sideband did establish
(1.0.44) one session ran ~100 s; the fork's per-second `session perf` log is now
present and is the quickest way to read transport/fps.

**Performance caveat:** even with the tunnel up, EGFX (RFX-progressive) at 4K is
heavy; the legacy fast-path path (EGFX off) is much lighter. EGFX-over-UDP may not
beat the FreeRDP TCP engine for 4K.

### Safety net

- UDP TLS handshake timeout is capped at **8 s** when no interactive cert callback
  is set (was 130 s), so a stalled sideband fails fast and IronRDP **continues on
  TCP** instead of hanging. (`UdpTransportConfig` default stays 130 s for
  interactive-cert callers.)

## Handy references (used to root-cause the Windows interop)

- `zhongbai2333/NativeMacRDP` — a macOS RDP **server** that interoperates with real
  Windows clients. Its `protocol/RDPUDPBootstrap.*` and `protocol/RDPUDP2.c`
  contain the authoritative notes on the DWORD cookieHash view and the fresh
  v2 sequence space.
- Microsoft Open Specs: MS-RDPEUDP, MS-RDPEUDP2, MS-RDPEMT, MS-RDPBCGR §2.2.15.

## Build commands

FreeRDP/Android APK:
```
cd freerdp-upstream/client/Android/Studio
ANDROID_HOME=/home/alau/android-sdk ./gradlew :app:assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

IronRDP engine native lib (arm64), then copy into the app:
```
source ~/.cargo/env
cd ironrdp
ANDROID_NDK_HOME=/home/alau/android-sdk/ndk/29.0.13113456 \
  cargo ndk -t arm64-v8a -o /tmp/opencode/clientout build --release \
  -p ironrdp-dexrdp-client
cp /tmp/opencode/clientout/arm64-v8a/libdexrdp_client.so \
  ../freerdp-upstream/client/Android/Studio/app/src/main/jniLibs/arm64-v8a/
```

Toolchain: Java 17, Android SDK (API 37, build-tools 37), NDK 29, CMake 4.1.2,
Rust 1.94.1 (pinned by `ironrdp/rust-toolchain.toml`) + `cargo-ndk`.

Version lives in `freerdp-upstream/client/Android/Studio/release.properties`
(`VERSION_NAME` / `VERSION_CODE`). Bump both before building a release APK —
the filename version alone does not change what the phone sees. Latest: 1.0.48.

## Engine JNI surface (com.dexrdp.engine.NativeRdp)

`nativeConnect(host, port, user, pass, domain, w, h, frameBuffer, callback)`,
`nativeSendKey`, `nativeSendMouse`, `nativeSendMouseEx`, `nativeDisconnect`,
`nativeFree`, `nativeSetLogPath`.
Callback: `onConnected`, `onFrame(buffer, w, h)`, `onFailure(reason)`,
`onTerminated`. Frames are RGBA in a Kotlin-owned direct ByteBuffer.

## Git / artifacts hygiene

- Git-ignored: `error.txt`, `*.log`, `*.apk`, `*.pcap`, `*.pcapng`,
  `release.properties`, `*.jks`, `**/jniLibs/`, build dirs.
- Commits go to **both** `master` and `main` (identical). Push with:
  `git -c credential.helper='!/home/alau/bin/gh auth git-credential' push \
   https://github.com/alau1158/androd_rdp_client.git master:master master:main`
