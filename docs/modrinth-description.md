# PerfectFlow

PerfectFlow is a client-side mod for offline rendering and frame-perfect video capture on Minecraft.

## Features

- Frame-perfect video capture for offline rendering workflows
- MP4 export through an external FFmpeg executable
- TGA sequence export
- Optional alpha and depth output
- Motion blur support
- Iris shader capture support

## Required Dependencies

- Fabric builds require Fabric API and Cloth Config
- Fabric builds recommend Mod Menu for easy access to the in-game config screen
- Forge builds have no required library dependency beyond Forge itself

## Setup

- Configure the FFmpeg executable path manually before using MP4 export
- Open the mod configuration through Mod Menu on Fabric
- Default hotkey: `U`

## Limitations

- Audio recording currently works only through the Windows process-loopback path
- Multiplayer capture downgrades sync mode to `Client Only`
- Shader compatibility may vary depending on the shader pack

## Basic Usage

- Press `U` to start or stop capture
- Choose output mode, resolution, shader capture, and motion blur settings in Mod Menu
- Use TGA sequence export if FFmpeg is not configured
