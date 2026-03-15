# X17 - The Nightmare

An advanced, psychological horror mod for the Hytale engine. 

**X-17** is not a simple monster that spawns, attacks, and dies. It is a persistent, director-style artificial intelligence designed to stalk players over multiple in-game nights, manipulate the environment, create intense paranoia, and ensure that no two nights feel exactly the same.

## 🦇 Core Features

* **Persistent Night Scheduler:** The mod tracks a continuous cycle of nights. The creature remembers if it failed to scare you, gradually building "Tension" until a terrifying encounter is guaranteed.
* **Psychological Audio Engine:** X-17 hijacks Hytale's night music, replacing it with dreadful silence or localized ambient sounds. You might hear footsteps on your roof, knocking on your doors and windows, or localized whispers meant to disorient you.
* **Singleton Guard:** There can only ever be *one* X-17 active per world. It manages its own entity lifespan, teleporting silently between vantage points rather than awkwardly despawning and respawning.
* **Director AI State Machine:** 
  * **Stalking:** Watches you from deep within the treeline, always repositioning to stay in your blind spots.
  * **False Ambushes:** Might quickly approach you just to freeze, scream, and vanish.
  * **Rage Chase:** If provoked or attacked, X-17 engages in a high-speed chase.
* **Seamless Integration:** Uses Hytale's native ticking systems, component architecture, and cleanly intercepts standard vanilla behaviors.

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
- **`scheduler/`**: Contains the `X17NightScheduler`, responsible for randomizing nights and writing outcomes to local `.properties` files.
- **`ui/`**: Contains Java code rendering the `X17WelcomePage` UI.

## ⚠️ Requirements

- Hytale Server API access
- `com.hypixel.hytale.*` core packages
- Java 17+

---
*The darkness awakens...*
