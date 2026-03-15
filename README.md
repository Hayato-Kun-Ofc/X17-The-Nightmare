## Introduction

**X-17 The Nightmare** is a server-side horror mod for Hytale that introduces a dynamic, persistent entity designed to stalk and terrify players over the course of the night cycle. 

Written entirely in Java within the Hytale modding API, this mod extends the base entity behavior to create an advanced AI presence that reacts to the player's vision, world time, and consecutive night cycles.

## Core Features

- **Persistent Night Presence**: X-17 does not simply spawn and despawn; it actively tracks players and silently repositions around them throughout the night, acting purely on custom Java ticking systems.
- **Dynamic Night Scheduler**: A sophisticated probability engine that decides whether a night will feature an active spawn, ambient ghost sounds, or complete silence. 
- **Tension System**: If X-17 hasn't spawned for several nights, the mod builds "Tension", guaranteeing an event or increasing ghost sound intensity.
- **Atomic State Saving**: Night cycles and decisions are safely persisted to the server's disk, ensuring that a server crash mid-night doesn't reset the nightmare.
- **Rage & Retreat Mechanics**: Attacking X-17 can trigger a high-speed Rage chase. Evading or landing enough hits causes the entity to strategically Retreat with a multi-night cooldown.

## Important Note

This mod was created with much work, including most of the assets. Assets available here like music and 
sounds are original compositions and recordings made exclusively for this mod and are 
**All Rights Reserved**. Extracting and using them from releases without permission is prohibited.
The character model is made by me, and it's inspired on FNAF universe, not affiliated with or endorsed by 
Scott Cawthon or Steel Wool Studios.
