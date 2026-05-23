# PerfectFlow

PerfectFlow is a Minecraft client mod for offline rendering and frame-perfect video capture.

The current public release target is Minecraft 1.20.4 on Fabric. The codebase still uses a multiloader layout:

- `common`: shared capture controller, config model, exporter interfaces, shader pipeline abstraction, and common mixins.
- `fabric`: Fabric client entrypoint, key binding, HUD/render hooks, Mod Menu and Cloth Config integration.
- `neoforge`: unreleased loader-specific implementation that is not part of the current 1.20.4 release plan.

<!-- See [PERFECT_FLOW_TECHNICAL_PRD.md](PERFECT_FLOW_TECHNICAL_PRD.md) for the implementation document. -->

## Current Status

The repository now exposes the mod publicly as `perfectflow` / `PerfectFlow`.

- `U` toggles recording.
- The right-side recording HUD is drawn after capture hooks so it is not intended to enter output frames.
- Vanilla framebuffer color readback, TGA export, FFmpeg pipe export via user-configured executable path, config loading, and Iris/Oculus adapter selection are in place as the v1 foundation. The default FFmpeg path now pads odd dimensions up to even values so libx264 can start reliably.
- The default Fabric development client now uses Yarn mappings, loads Iris + Sodium through Loom's remapped runtime path, and extracts Iris nested runtime libraries so shader packs can be enabled directly during local testing. Released jars still expect end users to install shader mods themselves.
- Fabric's Iris recording path currently captures from a later final-present hook on the main framebuffer so local shader testing aims to match the final on-screen image; a dedicated Iris internal final-pass provider is reserved as a future fallback if some shader packs still need it.
- Alpha capture now exports a grayscale mask stream instead of duplicating the color stream, and depth capture remains opt-in with a stable grayscale output path.
- Motion blur is configurable in JSON config and the Fabric Mod Menu, with `FRAME_BLEND` and `ACCUMULATION` modes that affect only the color stream.
- Default config/output names are now `perfectflow.json` and `perfectflow_captures`.
- Local development expects an installed JDK 17 and does not require Gradle to download a toolchain from the network.
- Iris shader capture now goes through a Fabric-side bridge for local testing, while Oculus remains a deferred follow-up on the NeoForge side.
