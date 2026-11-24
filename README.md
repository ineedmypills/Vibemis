# Vibemis (Android)

**Vibemis** (`com.ineedmypills.vibemis`) is an open-source, low-latency game and desktop streaming client for Android. It targets full compatibility with **[Vibepollo](https://github.com/Nonary/Vibepollo)** while supporting **[Apollo](https://github.com/ClassicOldSong/Apollo)** and **[Sunshine](https://github.com/LizardByte/Sunshine)**.

> The core streaming stack runs in pure Rust (`rust/vibemis_core`), and the Android UI and platform bindings are written in Kotlin with Jetpack Compose Material 3 Expressive.

---

## Architecture & Highlights

### 1. Pure Rust Streaming Core (`rust/vibemis_core`)
- **Zero C/C++ or BouncyCastle dependencies**: Protocol state machines, cryptography, X.509 certificate assembly, forward error correction, Wake-on-LAN, CPU topology detection, and DSP math run inside [`vibemis_core`](rust/vibemis_core/src/lib.rs) with `catch_unwind` JNI boundaries.
- **Cryptography and pairing** ([`crypto.rs`](rust/vibemis_core/src/protocol/crypto.rs), [`control_plane.rs`](rust/vibemis_core/src/control_plane.rs)): FIPS-197 AES-128-ECB/CBC, NIST SP 800-38D AES-128-GCM, and RFC 5280 X.509v3 ASN.1 DER/PEM certificate generation.
- **Reed-Solomon FEC** ([`fec.rs`](rust/vibemis_core/src/protocol/fec.rs)): Erasure coding over GF(2^8) for multi-block video packets.
- **RTSP, SDP, Control & WoL** ([`rtsp.rs`](rust/vibemis_core/src/protocol/rtsp.rs), [`sdp.rs`](rust/vibemis_core/src/protocol/sdp.rs), [`control.rs`](rust/vibemis_core/src/protocol/control.rs), [`wol.rs`](rust/vibemis_core/src/protocol/wol.rs)): Plaintext and encrypted RTSP 1.0 handshake, Vibepollo/Apollo capability flags, reliable ENet IDR frame requests (`requestIdrFrame`), and Wake-on-LAN Magic Packets.
- **Video, Audio & Thread Affinity** ([`video.rs`](rust/vibemis_core/src/protocol/video.rs), [`audio.rs`](rust/vibemis_core/src/protocol/audio.rs), [`cpu_affinity.rs`](rust/vibemis_core/src/cpu_affinity.rs)): Multi-threaded RTP/UDP receivers and H.264/HEVC depacketizers (plus AV1 frame assembly and PyroWave capability detection) with sysfs cluster parsing to pin hot paths to Prime/Big performance cores (`pinHotThreadsToBigCores`, `pinCurrentThreadToCoreSet`).
- **Input & DSP Math** ([`input.rs`](rust/vibemis_core/src/protocol/input.rs), [`dsp_math.rs`](rust/vibemis_core/src/dsp_math.rs), [`fsr_shaders.rs`](rust/vibemis_core/src/render/fsr_shaders.rs)): Packet serialization, radial stick deadzones, and AMD FSR 1.0 RCAS sharpness mapping.

### 2. Low-Latency Video & Kotlin UI (Android minSdk 26 / targetSdk 35 / compileSdk 37)
- **Kotlin + Jetpack Compose Material 3 Expressive**: All Android sources (`app/src/main/kotlin` and `app/src/test/kotlin`) target JVM 17 and Compose Material 3 Expressive (`1.5.0-beta02`), with adaptive layouts for phones, tablets, foldables, ChromeOS, and Android TV.
- **GLES3 FSR 1.0 Upscaler** ([`GlUpscaleRenderer.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/render/GlUpscaleRenderer.kt), [`GlUpscalerBridge.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/binding/video/GlUpscalerBridge.kt)): Native Kotlin OpenGL ES 3.0 implementation of EASU (Quality/Balanced/Performance), RCAS (2D/OES/HDR Luma), GPU timer queries, Choreographer/EGL presentation, and direct decoder surface bridging.
- **Bounded NVIDIA latency recovery** ([`NvidiaLatencyRecoveryController.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/binding/video/NvidiaLatencyRecoveryController.kt), [`NvidiaLatencyRecoveryGate.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/binding/video/NvidiaLatencyRecoveryGate.kt), [`OutputDebtTracker.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/binding/video/OutputDebtTracker.kt), [`NvidiaLatencyRecoveryEligibility.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/binding/video/NvidiaLatencyRecoveryEligibility.kt)): Allocation-free O(1) PTS debt tracker and bounded frame-drop/phase-recovery gate for `MediaCodec` queues, wired with compatibility reporting in settings.
- **Thermal-Aware CPU Warm-Up** ([`CpuWarmUp.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/perf/CpuWarmUp.kt)): Optional ADPF performance hint session and big/prime CPU cluster pinning during active streaming.
- **Modular Streaming Helpers**: [`Game.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/Game.kt), [`GameDisplayHelper.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/GameDisplayHelper.kt), [`GameClipboardHelper.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/GameClipboardHelper.kt), [`GameTouchPenHelper.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/GameTouchPenHelper.kt), and [`GameMenu.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/GameMenu.kt).

### 3. Vibepollo & Apollo Synergy
- **Paired-Client Sleep Protocol v1** ([`VibepolloClient.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/nvstream/http/VibepolloClient.kt), [`NvHTTP.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/nvstream/http/NvHTTP.kt)): Discovers `/power-actions/v1` capabilities and executes mutual-TLS with SHA-256 certificate pinning `POST /power-actions/v1/sleep?token=...` requests from the host card or the in-stream Quit and Sleep action.
- **Live Hardware Telemetry & Control Panel**: Polls `GET /api/hardware-metrics` inside the in-game Vibepollo panel ([`GameMenu.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/GameMenu.kt)) to display CPU, GPU, GPU encoder utilization, temperatures, and VRAM alongside on-demand IDR frame requests and DualSense speaker routing.
- **Synthetic Apps & Concurrent Launch** ([`NvApp.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/nvstream/http/NvApp.kt), [`AppView.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/AppView.kt), [`NvConnection.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/nvstream/NvConnection.kt)): Recognizes Vibepollo synthetic UUIDs (`9a1c5a25-58fe-40e0-b9aa-7d3f00000001..00000007`), runs control-action entries in place without leaving the app grid, and launches remote input/monitor sessions alongside active streams.
- **PlayStation Slot Mask, VRR & Warnings** ([`ControllerHandler.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/binding/input/ControllerHandler.kt), [`StreamConfiguration.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/nvstream/StreamConfiguration.kt)): Sends `&psmap=`, `&clientVrrRequested=`, and `&clientName=` during `/launch` and `/resume`, surfaces `<clientWarning>` messages on connect, and extracts JSON error details from host responses.
- **DualSense Speaker PCM & Trigger Rumble** ([`VibemisBridge.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/nvstream/jni/VibemisBridge.kt), [`ControllerRumbleMapper.kt`](app/src/main/kotlin/com/ineedmypills/vibemis/binding/input/ControllerRumbleMapper.kt)): Routes 48 kHz stereo S16LE controller speaker audio (`bridgeClControllerSpeakerPcm`) to a low-latency `AudioTrack` alongside controller trigger rumble, RGB LEDs, and PyroWave host capability detection.

---

## Building & Testing

### Prerequisites
- **JDK 21**
- **Android SDK** (API 35 to 37 and NDK `27.0.12077973`)
- **Rust Toolchain** (`rustup` with `cargo` and Android targets):
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android
  ```

### Commands
```bash
# Run pure-Rust core unit tests
cargo test --manifest-path rust/vibemis_core/Cargo.toml

# Run Android unit tests (Robolectric) and build debug APKs
./gradlew testDebugUnitTest assembleDebug

# Build release APKs (per-ABI and universal)
./gradlew assembleRelease
```

Signed release builds are produced automatically in GitHub Actions (`.github/workflows/ci.yml`) when `SIGNING_STORE_FILE` (or `ANDROID_KEYSTORE_BASE64`), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD` secrets are configured; otherwise Gradle signs release APKs with the local debug keystore.

---

## Credits & Upstream Projects

Vibemis builds upon the work of these open-source projects and authors:

### Original Moonlight Android & `moonlight-common-c`
- **[Moonlight Android](https://github.com/moonlight-stream/moonlight-android)** & **[moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c)**
- **[Cameron Gutman](https://github.com/cgutman)**
- **[Diego Waxemberg](https://github.com/dwaxemberg)**
- **Aaron Neyer**
- **[Andrew Hennessy](https://github.com/yetanothername)**
- **Karim Mreisi** *(On-screen virtual controller and touch controls)*
- **[Michel](https://github.com/moonlight-stream)** *(Dynamic bitrate and pacing contributions)*

*Moonlight started at [Case Western Reserve University](https://case.edu) during [MHacks](https://mhacks.org).*

### Artemis, Artemide, Apollo & Sunshine Ecosystem
- **[ClassicOldSong](https://github.com/ClassicOldSong)**: Author of **[Artemis](https://github.com/ClassicOldSong/moonlight-android)** and **[Apollo](https://github.com/ClassicOldSong/Apollo)**.
- **[derflacco](https://github.com/derflacco)** & **Artemide Contributors**: Author of **[Artemide](https://github.com/derflacco/moonlight-android)** (GLES3 FSR 1.0 upscaler, NVIDIA latency recovery telemetry/gating, CPU cluster pinning and warm-up, settings profiles, and async decoder optimizations).
- **[LizardByte](https://github.com/LizardByte/Sunshine)**: Authors of **Sunshine**.

### Vibepollo Host & Protocol
- **[Nonary](https://github.com/Nonary)**: Author of **[Vibepollo](https://github.com/Nonary/Vibepollo)** (Paired-Client Sleep Protocol v1, `/api/hardware-metrics`, PyroWave wavelet codec, synthetic apps, and DualSense Speaker PCM extensions).

### Vibemis
- **[ineedmypills](https://github.com/ineedmypills)**: Pure Rust `vibemis_core` streaming engine, Kotlin + Jetpack Compose Material 3 Expressive UI, and Vibepollo protocol integration (`com.ineedmypills.vibemis`).

---

## License

Licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE.txt](LICENSE.txt) for details.
