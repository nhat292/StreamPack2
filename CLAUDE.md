# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew assemble                  # Build all modules
./gradlew test                      # Run unit tests
./gradlew connectedCheck            # Run instrumented tests (requires device/emulator)
./gradlew :core:test                # Run unit tests for a specific module
./gradlew :demos:camera:installDebug  # Install camera demo on device
./gradlew dokkaGeneratePublicationHtml  # Generate API docs
```

## Architecture

StreamPack is an Android streaming SDK structured as a multi-module Gradle project. The core pipeline is:

```
Sources → Encoders → Muxer → Sink (endpoint)
```

### Module Layout

- **`core/`** — The streaming pipeline engine. Defines all key interfaces and implementations.
- **`ui/`** — `PreviewView` for camera preview; depends on `core`.
- **`services/`** — `MediaProjectionService` for screen capture; depends on `core`.
- **`extensions/flv/`** — FLV container muxer.
- **`extensions/rtmp/`** — RTMP/RTMPS sink; depends on `core` and `flv`.
- **`extensions/srt/`** — SRT sink via SRTdroid; depends on `core`.
- **`demos/camera/`** and **`demos/screenrecorder/`** — Demo apps.

Build convention plugins live in `buildSrc/` (`android-library-convention.gradle.kts`, etc.).

### Entry Points: Streamers

The public API surface is the **Streamer** layer in `core/`:

| Class | Purpose |
|---|---|
| `SingleStreamer` | One output — live OR record |
| `DualStreamer` | Two independent outputs — live AND record simultaneously |
| `StreamerPipeline` | Custom multi-output pipelines |

Factory extensions like `cameraSingleStreamer()` and `cameraDualStreamer()` are the typical starting point for consumers.

### Pipeline Internals

- **Sources**: `CameraSource`, `MediaProjectionVideoSource`, `BitmapSource` (video); `MicrophoneSource`, `MediaProjectionAudioSource` (audio).
- **Encoders**: Thin wrappers over Android's `MediaCodec` API — no third-party codec libraries.
- **Endpoints**: Composed of a **Muxer** (FLV, MPEG-TS, MP4, WebM, fMP4) + a **Sink** (File, Content URI, OutputStream, or network sinks from extensions). `DynamicEndpoint` infers the right endpoint from a `UriMediaDescriptor`.

### Coroutine-Based Design

All streaming lifecycle methods (`open`, `close`, `startStream`, `stopStream`) are `suspend` functions. State is exposed as `StateFlow`s (`isStreamingFlow`, `throwableFlow`, `isOpenFlow`). Always call these from an appropriate coroutine scope (e.g. `lifecycleScope`, `viewModelScope`).

### Interfaces to Know

- `IStreamer` / `IOpenableStreamer` / `IConfigurableStreamer` — streamer lifecycle hierarchy.
- `IVideoSource` / `IAudioSource` — implement these for custom input sources.
- `IMuxer` / `ISink` — implement these for custom output formats/destinations.
- `MediaDescriptor` / `UriMediaDescriptor` — describes an output destination.
- `BitrateRegulator` / `IBitrateRegulatorController` — adaptive bitrate for SRT streams.

### Configuration

- `AudioConfig` — bitrate, sample rate, channel count, codec.
- `VideoConfig` — bitrate, resolution, FPS, codec (H.264/H.265/VP9/AV1).
- `IConfigurationInfo` — query device/protocol capability before configuring.

### Text Overlay System

The overlay system composites sport scoreboard graphics on top of every encoded video frame using OpenGL ES. The full data flow is:

```
OverlayParams
  → TextOverlayBitmapFactory          (Android Canvas → Bitmap, cached per layer)
      → ISurfaceProcessorInternal      (thread-safe atomic hand-off)
          → OpenGlRenderer             (GL thread: upload Bitmap → GL_TEXTURE_2D, draw quad per frame)
```

**Key files:**
- [TextOverlayBitmapFactory.kt](core/src/main/java/io/github/thibaultbee/streampack/core/elements/processing/video/overlay/TextOverlayBitmapFactory.kt) — Builds `Bitmap`s from `OverlayParams` using `android.graphics.Canvas`. Contains all layout constants (colours, font sizes, padding, column widths). Has per-layer caching; only the changed layer is rebuilt.
- [ISurfaceProcessor.kt](core/src/main/java/io/github/thibaultbee/streampack/core/elements/processing/video/ISurfaceProcessor.kt) — Defines the overlay API surface (`setOverlayBitmaps`, `setTickerBitmap`, `setLinkBitmaps`, `applyOverlayParams`).
- [DefaultSurfaceProcessor.kt](core/src/main/java/io/github/thibaultbee/streampack/core/elements/processing/video/DefaultSurfaceProcessor.kt) — Thread-safe bridge: stores `Bitmap`s in `AtomicReference`, passes them to `OpenGlRenderer` which consumes them on the GL thread during the next `render()` call. Also manages downloading link corner images on a dedicated background thread with `ConcurrentHashMap` caching.
- [OpenGlRenderer.kt](core/src/main/java/io/github/thibaultbee/streampack/core/elements/processing/video/OpenGlRenderer.kt) — Uploads bitmaps to `GL_TEXTURE_2D` units and draws one textured quad per layer per frame using a simple alpha-blended overlay program.

**Three visual layers (stacked top-left):**
1. **text3** — yellow match/group label bar (`GL_TEXTURE1`)
2. **Player rows** — scoreboard with name, turn indicator, match score, set score, point, tie-break (`GL_TEXTURE2`)
3. **text4** — blue venue/event bar (`GL_TEXTURE3`)

**Ticker** (`GL_TEXTURE4`) — scrolls right-to-left at the bottom of the frame every render call (`TICKER_SPEED = 0.003f` clip-space units/frame). Resets to off-screen right when it scrolls fully off the left edge.

**Link corner images** (`GL_TEXTURE5–7`) — three remote image URLs downloaded on a single background thread (`LinkBitmapDownloader`): `link1` → top-right, `link2` → bottom-left, `link3` → bottom-right (both bottom images sit just above the ticker). Note: `link1/2/3` are **not** rendered by `TextOverlayBitmapFactory` — only by the GL renderer after download.

**Usage pattern** (call from a background dispatcher):
```kotlin
val layers = TextOverlayBitmapFactory.createLayers(params)   // returns up to 3 bitmaps
streamer.videoInput?.processor?.setOverlayBitmaps(layers)

val ticker = TextOverlayBitmapFactory.createTickerBitmap(params.tickerText)
streamer.videoInput?.processor?.setTickerBitmap(ticker)

// Or the convenience one-shot:
streamer.videoInput?.processor?.applyOverlayParams(params)   // handles layers + ticker + link downloads
```

**Caching rules:** `TextOverlayBitmapFactory` caches each layer by its content (text3 string, `PlayerCacheKey`, text4 string, ticker string). A layer bitmap is only rebuilt when its cache key changes or its `Bitmap.isRecycled` is true. Do not call the factory on the main thread — bitmap measurement and drawing can be slow.

**Layout invariants:** Both player rows always share the same `ScoreboardLayout` (same column widths and row height). A column appears in the layout if *either* player row uses it, so both rows are always the same pixel width. All size constants in `TextOverlayBitmapFactory` must be kept in sync with the GL-side scale factor `OVERLAY_SCALE = 0.8f` in `OpenGlRenderer`.

## Key Conventions

- **Min SDK 21**, **Target/Compile SDK 36**, **JVM target 18**.
- Group ID: `io.github.thibaultbee.streampack`; version managed in `gradle/libs.versions.toml`.
- Kotlin is the primary language; Java interop is maintained.
- All public API is in `io.github.thibaultbee.streampack.*` namespaces.
- Extensions (rtmp, srt) are published as separate artifacts so consumers only pull what they need.
