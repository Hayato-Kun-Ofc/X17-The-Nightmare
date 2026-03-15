## Introduction

**X-17 The Nightmare** is a server-side horror mod for Hytale that introduces a dynamic, persistent entity designed to stalk and terrify players over the course of the night cycle. 

Written entirely in Java within the Hytale modding API, this mod extends the base entity behavior to create an advanced AI presence that reacts to the player's vision, world time, and consecutive night cycles.

## Core Features

- **Persistent Night Presence**: X-17 does not simply spawn and despawn; it actively tracks players and silently repositions around them throughout the night, acting purely on custom Java ticking systems.
- **Dynamic Night Scheduler**: A sophisticated probability engine that decides whether a night will feature an active spawn, ambient ghost sounds, or complete silence. 
- **Tension System**: If X-17 hasn't spawned for several nights, the mod builds "Tension", guaranteeing an event or increasing ghost sound intensity.
- **Atomic State Saving**: Night cycles and decisions are safely persisted to the server's disk, ensuring that a server crash mid-night doesn't reset the nightmare.
- **Rage & Retreat Mechanics**: Attacking X-17 can trigger a high-speed Rage chase. Evading or landing enough hits causes the entity to strategically Retreat with a multi-night cooldown.

Welcome to the true nightmare experience. Proceed to the subsequent pages to learn about installation, technical mechanics, and testing.
