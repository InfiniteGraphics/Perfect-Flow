# PerfectFlow

PerfectFlow is a client-side Fabric mod for offline rendering and frame-perfect video capture on Minecraft 1.20.4.

## Features

- Frame-perfect video capture for offline rendering workflows
- MP4 export through an external FFmpeg executable
- TGA sequence export
- Optional alpha and depth output
- Motion blur support
- Iris shader capture support

## Required Dependencies

- Fabric API
- Mod Menu
- Cloth Config

## Setup

- Configure the FFmpeg executable path manually before using MP4 export
- Open the mod configuration through Mod Menu
- Default hotkey: `U`

## Limitations

- Audio recording currently works only through the Windows process-loopback path
- Multiplayer capture downgrades sync mode to `Client Only`
- This public release is Fabric-only for Minecraft 1.20.4
- Shader compatibility may vary depending on the shader pack

## Basic Usage

- Press `U` to start or stop capture
- Choose output mode, resolution, shader capture, and motion blur settings in Mod Menu
- Use TGA sequence export if FFmpeg is not configured
