# PerfectFlow

PerfectFlow is a Minecraft client mod for offline rendering and frame-perfect video capture.

The repository now targets Minecraft `1.18.2`, `1.19.2`, and `1.20.1` on both `Fabric` and `Forge` from the same codebase.
Use `-PmcVersion=<version>` to switch the active target during builds, for example:

```bash
./gradlew -PmcVersion=1.20.1 :fabric:build :forge:build
./gradlew -PmcVersion=1.19.2 :fabric:build :forge:build
./gradlew -PmcVersion=1.18.2 :fabric:build :forge:build
```

Shader prerequisites:

- Fabric: install `Iris`.
- Forge: install `Oculus`.

The codebase uses a multiloader layout:

- `common`: shared capture controller, config model, exporter interfaces, shader pipeline abstraction, and common mixins.
- `fabric`: Fabric client entrypoint, key binding, Iris bridge, and Fabric-specific hooks.
- `forge`: Forge client entrypoint, key binding bridge, Oculus bridge, and Forge-specific hooks.

<!-- See [PERFECT_FLOW_TECHNICAL_PRD.md](PERFECT_FLOW_TECHNICAL_PRD.md) for the implementation document. -->

## Current Status

The repository now exposes the mod publicly as `perfectflow` / `PerfectFlow`.

- `U` toggles recording.
- The right-side recording HUD is drawn after capture hooks so it is not intended to enter output frames.
- Vanilla framebuffer color readback, TGA export, FFmpeg pipe export via user-configured executable path, config loading, and Iris/Oculus adapter selection are in place as the v1 foundation. The default FFmpeg path now pads odd dimensions up to even values so libx264 can start reliably.
- Fabric and Forge now share MojMap-based source compatibility so the same code paths can be built against 1.18.2, 1.19.2, and 1.20.1.
- Fabric's Iris recording path captures from the main framebuffer immediately before GUI rendering so shader output tracks the final world image without pulling the recording HUD into exported frames.
- Alpha capture now exports a grayscale mask stream instead of duplicating the color stream, and depth capture remains opt-in with a stable grayscale output path.
- Motion blur is configurable in JSON config, with `FRAME_BLEND` and `ACCUMULATION` modes that affect only the color stream.
- Default config/output names are now `perfectflow.json` and `perfectflow_captures`.
- Local development expects an installed JDK 17 and does not require Gradle to download a toolchain from the network.
- Iris shader capture goes through a Fabric-side bridge, and Oculus shader capture now goes through a Forge-side bridge.
