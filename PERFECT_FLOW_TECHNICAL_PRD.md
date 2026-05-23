# PerfectFlow Technical PRD

## Summary

PerfectFlow targets Minecraft `1.20.4` as a Fabric + NeoForge client mod for offline rendering and deterministic video capture. It is a rebuild inspired by Minema, not a direct 1.12.2 ASM port.

Core goals:

- Toggle recording with the default `U` key.
- Capture color, alpha, and depth passes.
- Export TGA image sequences or pipe raw frames into FFmpeg for MP4 output.
- Keep output timing stable by driving capture from a fixed virtual frame timeline.
- Support vanilla rendering, Fabric + Iris detection, and NeoForge + Oculus detection.
- Keep the PerfectFlow recording HUD visible in-game but excluded from final output.

Default project identity:

- Mod ID: `perfectflow`
- Mod name: `PerfectFlow`
- Base package: `com.perfectflow`
- Java target: `17`
- Fabric config UI: Mod Menu + Cloth Config
- FFmpeg v1 packaging: custom executable path only

## Architecture

### Capture Controller

`CaptureController` owns the recording state machine:

`IDLE -> STARTING -> RECORDING -> STOPPING -> IDLE`

Responsibilities:

- Handle key-toggle requests.
- Create and close `CaptureSession`.
- Select the active shader pipeline adapter.
- Route captured frames to exporters.
- Stop safely on capture/export errors.
- Auto-stop when `frameLimit` is reached.

### Capture Session

`CaptureSession` stores a snapshot for one recording:

- Output root directory.
- Session name, currently `yyyy-MM-dd_HH-mm-ss`.
- Target FPS.
- Engine speed.
- Captured frame count.
- `FrameScheduler`.

The config is intentionally snapshotted at start. Runtime config changes affect the next recording, not the active one.

### Frame Scheduler

`FrameScheduler` tracks the deterministic video timeline:

- `videoSeconds = frameIndex / targetFps`
- `virtualGameSeconds = videoSeconds * engineSpeed`
- `fixedPartialTick` is derived from the virtual 20 TPS game timeline.

Current implementation exposes the timeline to the capture system. The next step is to inject this timeline into the Minecraft 1.20.4 render delta/timer path so singleplayer worlds can advance with fully deterministic tick/render timing.

Multiplayer behavior:

- Do not control server tick.
- Capture local frames only.
- Keep output frame spacing deterministic.

### Render Capture Pipeline

`RenderCapturePipeline` captures after world rendering and before PerfectFlow HUD drawing.

Current pass behavior:

- `color`: reads BGR24 or BGRA32 from the selected color framebuffer.
- `alpha`: reads BGRA32 as an independent stream when enabled.
- `depth`: reads the depth buffer and linearizes it to grayscale BGR24 when the active adapter supports depth capture.

Implementation notes:

- Vanilla uses `Minecraft.getMainRenderTarget()`.
- The current readback path is synchronous `glReadPixels`.
- The intended next optimization is a PBO ring buffer with a synchronous fallback.
- Queue-backed exporters should be added before long recordings are considered production-ready.

### Exporters

`FrameExporter` is the output boundary.

Implemented exporters:

- `TgaSequenceExporter`
  - Writes `%06d.tga` under `perfectflow_captures/<session>/<stream>/`.
  - Supports BGR24/alpha-mask-as-BGR24/depth-as-BGR24.

- `FfmpegPipeExporter`
  - Starts FFmpeg.
  - Writes rawvideo frames to stdin.
  - Uses tokenized args:
    - `%PIX_FMT%`
    - `%WIDTH%`
    - `%HEIGHT%`
    - `%FPS%`
    - `%NAME%`
    - `%STREAM%`

FFmpeg defaults:

```text
-y -f rawvideo -pix_fmt %PIX_FMT% -s %WIDTH%x%HEIGHT% -r %FPS% -i - -vf vflip,pad=ceil(iw/2)*2:ceil(ih/2)*2 -c:v libx264 -preset ultrafast -tune zerolatency -qp 18 -pix_fmt yuv420p %NAME%_%STREAM%.mp4
```

FFmpeg path policy:

- The config stores a user-supplied path to the FFmpeg executable itself.
- Old `BUNDLED` configs migrate to `CUSTOM_PATH` on load.
- The path must resolve to an actual executable file before capture starts.
- Directory values are only accepted when they contain a usable FFmpeg binary.

### Shader Pipeline Adapter

`ShaderPipelineAdapter` abstracts capture targets.

Adapters:

- `VanillaPipelineAdapter`
  - Always available.
  - Uses the main Minecraft render target.
  - Marks depth capture as available.

- `IrisPipelineAdapter`
  - Available when Fabric reports `iris` loaded.
  - Currently falls back to vanilla color target.
  - Depth bridge is explicitly disabled until the Iris framebuffer API is wired.

- `OculusPipelineAdapter`
  - Available when NeoForge reports `oculus` loaded.
  - Currently falls back to vanilla color target.
  - Depth bridge is explicitly disabled until the Oculus framebuffer API is wired.

Adapter selection:

- `AUTO`: Iris, then Oculus, then vanilla.
- `VANILLA`: force vanilla.
- `IRIS`: use Iris if loaded, otherwise vanilla.
- `OCULUS`: use Oculus if loaded, otherwise vanilla.

Depth capture must fail loudly or disable itself with a visible message if the active shader adapter cannot provide a valid depth target.

## Config And UI

Config file:

```text
.minecraft/config/perfectflow.json
```

Main config fields:

- `capture.fps`: default `60`
- `capture.outputPath`: default `perfectflow_captures`
- `capture.outputMode`: `FFMPEG_MP4` or `TGA_SEQUENCE`
- `capture.recordColor`: default `true`
- `capture.recordAlpha`: default `false`
- `capture.recordDepth`: default `false`
- `capture.frameLimit`: default `-1`
- `capture.showRecordingHud`: default `true`
- `sync.enabled`: default `true`
- `sync.engineSpeed`: default `1.0`
- `ffmpeg.mode`: `CUSTOM_PATH`
- `ffmpeg.customPath`: default empty string, expected to be a full executable path
- `ffmpeg.videoArgs`: tokenized FFmpeg args
- `shader.captureMode`: `AUTO`, `VANILLA`, `IRIS`, or `OCULUS`

Fabric UI:

- Mod Menu entrypoint: `com.perfectflow.fabric.config.PerfectFlowModMenu`
- Cloth Config screen exposes core capture/sync/FFmpeg settings.

NeoForge UI:

- The current implementation loads and saves the same JSON config.
- A matching NeoForge config screen is planned after the capture backend stabilizes.

## Implementation Roadmap

1. Template reset
   - Replace the template identity with `perfectflow`.
   - Switch to Minecraft 1.20.4 and Java 17.
   - Update loader metadata and mixin configs.

2. Core recording scaffold
   - Implement config, state machine, session, scheduler, HUD, and exporters.
   - Wire Fabric and NeoForge key/render hooks.

3. Capture backend hardening
   - Replace synchronous readback with PBO ring-buffer readback.
   - Add bounded export queues.
   - Add frame backpressure instead of frame dropping.

4. Time sync
   - Inject fixed render delta into the 1.20.4 render path.
   - Add singleplayer integrated-server tick synchronization.
   - Keep multiplayer render-only.

5. Shader integrations
   - Fabric: implement concrete Iris color/depth target bridge.
   - NeoForge: implement concrete Oculus color/depth target bridge.
   - Add compatibility tests with shaderpacks.

6. FFmpeg path validation
   - Validate the configured executable path before capture starts.
   - Surface clear errors for missing, invalid, or non-executable paths.
   - Keep legacy config migration for older `BUNDLED` values.

## Acceptance Tests

- Fabric 1.20.4 client starts.
- NeoForge 1.20.4 client starts.
- Pressing `U` starts and stops recording.
- Recording HUD appears in-game.
- Recording HUD is not present in captured frames.
- TGA sequence output creates one file per captured frame.
- FFmpeg custom executable path can generate MP4.
- Legacy `BUNDLED` configs migrate to the custom-path flow and keep their previous path value.
- Color-only, color+alpha, and color+depth settings do not crash.
- Missing shader depth target emits a visible warning and disables depth output.
- Fabric + Iris loaded chooses the Iris adapter.
- NeoForge + Oculus loaded chooses the Oculus adapter.
- Multiplayer recording does not attempt to control server tick.

## Known Limits In This Implementation

- Iris and Oculus adapters currently detect the mods but still use vanilla framebuffer fallback for color.
- Shader depth capture is disabled until concrete Iris/Oculus depth target APIs are wired.
- Readback is synchronous; lower-risk buffer/FBO reuse is preferred before adding PBO ring buffering.
- FFmpeg writing uses a background queue, but render-thread readback is still synchronous.
- FFmpeg is never bundled; users must provide the executable path themselves.
- The default FFmpeg filter chain pads odd frame dimensions up to even values so libx264+yuv420p can start cleanly.
- Full tick/render delta synchronization is represented by `FrameScheduler` but still needs high-version Mixin injection into Minecraft's timing path.
