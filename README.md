# X17 - The Nightmare

An advanced, psychological horror mod for the Hytale engine. 

**X-17** is not a simple monster that spawns, attacks, and dies. It is a persistent, director-style artificial intelligence designed to stalk players over multiple in-game nights, manipulate the environment, create intense paranoia, and ensure that no two nights feel exactly the same.

## 🦇 Core Features

- **Persistent Night Presence:** X-17 does not simply spawn and despawn; it actively tracks players and silently repositions around them throughout the night, acting purely on custom Java ticking systems.
- **Dynamic Night Scheduler:** A sophisticated probability engine that decides whether a night will feature an active spawn, ambient ghost sounds, or complete silence.
- **Tension System:** If X-17 hasn't spawned for several nights, the mod builds "Tension", guaranteeing an event or increasing ghost sound intensity.
- **Atomic State Saving:** Night cycles and decisions are safely persisted to the server's disk, ensuring that a server crash mid-night doesn't reset the nightmare.
- **Natural Aggression & Reactive Combat:** X-17 enters aggressive cycles (HUNT, AMBUSH) naturally through its state machine. Player attacks do not trigger this initial aggression, but instead facilitate a reactive CHASE or force a strategic RETREAT (Vanish) after 2 successful hits.
- **Observational Intelligence:** The AI uses vision cone evaluation (dot products and yaw/pitch deltas) to detect if it is being watched. It responds to player gaze by vanishing or repositioning to maintain the illusion of a peripheral threat.
- **Nightly Personalities:** Every night, X-17 rolls a personality (Cautious, Bold, or Erratic) that dynamically alters its aggression, look-exposure limits, and movement patterns.
- **Silent Pilfering & Environment Hijack:** Beyond physical stalking, X-17 can silently steal priority loot from containers through deep reflection or extinguish nearby torches to force the player into darkness.
- **Psychological Stalking Patterns:** The AI is designed to avoid direct confrontation, preferring to appear in peripheral vision and then creeping toward the player only when they aren't looking.
- **Advanced Spawn Scoring:** Selection of spawn points is based on a scoring algorithm that weighs foliage cover, player yaw deltas, and distance to create the most unsettling encounters.

## 📖 Installation & Documentation

We have provided an extensive, multi-chapter Wiki explaining every detail of the mod's architecture, from the Java `EntityTickingSystem` to the audio layer JSONs.

**[Read the Full Documentation Wiki Here](https://wiki.hytalemodding.dev/mod/x17-the-nightmare/introduction)**

### Quick Start
1. Ensure you are running Hytale Server version `2026.02.19-1a311a592` or compatible.
2. Place the `X17NIGHTMARE` folder into your `UserData/Mods/` directory.
3. Start your world. 
4. The mod will silently initialize. Use `/time set night` to begin testing the AI's schedule.

## 🛠️ Architecture Overview

The mod is written in Java utilizing the Hytale ECS (Entity Component System) and focuses heavily on data persistence and thread safety.

- **`X17Plugin.java`**: The main class. Registers the custom components (`x17:ai_controller`, `x17:player_state`) and initializes the systems.
- **`component/`**: Contains data wrappers attached to the entity (`X17AIComponent`) and to players (`X17PlayerComponent`).
- **`system/`**: Contains the ticking logic. 
  - `X17AISystem`: Controls movement, teleportation, stalking logic, and rage chases.
  - `X17DamageSystem`: Handles the hit counter necessary for escaping Rage.
  - `X17EventSystem`: Hooks into server dusk/dawn events and manages player logins.
  - `X17SoundSystem`: Controls dynamic, spatial audio cues.
  - `X17TorchExtinguishSystem`: Logic for scanning and extinguishing light sources in a radius.
  - `X17ItemStealSystem`: Implements the silent theft of priority items from player chests.
- **`scheduler/`**: Contains the `X17NightScheduler`, responsible for randomizing nights and writing outcomes to local `.properties` files.
- **`ui/`**: Contains Java code rendering the `X17WelcomePage` UI.

## ⚠️ Requirements

- Hytale Server API access
- `com.hypixel.hytale.*` core packages
- Java 17+

---
*The darkness awakens...*
