package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;

/**
 * X17ShinyTrapSystem — v0.3.0
 *
 * ── GHOST NIGHT DECOY AMBUSH
 * ──────────────────────────────────────────────────
 *
 * Triggered exclusively on ghost nights (non-spawn). Sequence:
 *
 * 1. PREPARE — After a short delay, spawns a high-value weapon decoy 6 blocks
 * from the player, out of their FOV (behind them).
 *
 * 2. MOVING — The decoy glides toward a randomly chosen destination 20–60
 * blocks away. It always maintains exactly LEAD_DIST blocks ahead
 * of the nearest player — it cannot be "caught up to". It steps
 * over blocks (Y-snaps to ground each tick).
 *
 * 3. WAITING — Decoy has reached its destination. It stays put. As soon as any
 * player comes within PICKUP_RADIUS blocks, the trap fires:
 * • Decoy despawns (the item was poop — no visual needed).
 * • System transitions to SEEKING_SPAWN.
 *
 * 4. SEEKING_SPAWN — Scans 16 candidate positions ~8 blocks behind the nearest
 * player. Picks the first spot that is NOT inside the FOV
 * of ANY online player. If ≥2 players are covering all
 * angles, it waits until a gap opens. No timeout.
 *
 * 5. OBSERVING — X17 spawns at the chosen blind spot, facing the target player.
 * It does NOT move.
 * • Every tick: if ANY player's FOV overlaps X17's position →
 * VANISH immediately.
 * • After WATCH_TICKS (10 s) without being seen → VANISH.
 *
 * ── MULTI-PLAYER RULE
 * ─────────────────────────────────────────────────────────
 * During SEEKING_SPAWN, X17 waits indefinitely for a spawn spot that is hidden
 * from ALL players simultaneously. The check is per-spot per-tick — no angle
 * is assumed safe unless all player FOVs are tested.
 *
 * ── CONTRACT
 * ──────────────────────────────────────────────────────────────────
 * External API (called by X17AISystem):
 * resetShinyTrapNight(boolean isSpawnNight)
 *
 * This system is a TickingSystem<EntityStore> registered in X17Plugin.
 */
public class X17ShinyTrapSystem extends TickingSystem<EntityStore> {

    // ── Weapon pool (high-rarity, visually impressive IDs) ────────────────────
    private static final String[] DECOY_WEAPON_ROLES = {
            "Weapon_Sword_Onyxium",
            "Weapon_Sword_Mithril",
            "Weapon_Sword_Thorium",
            "Weapon_Sword_Silversteel",
            "Weapon_Sword_Adamantite",
            "Weapon_Sword_Nexus",
            "Weapon_Sword_Runic",
            "Weapon_Sword_Frost"
    };

    // ── Trigger probability (ghost nights only) ───────────────────────────────
    /** 25% chance the trap fires on a given ghost night. */
    private static final double TRAP_CHANCE = 0.25;

    // ── Decoy spawn geometry ──────────────────────────────────────────────────
    /** How far behind the player the decoy first appears (blocks). */
    private static final double SPAWN_BEHIND_DIST = 6.0;

    /**
     * Angular jitter applied to spawn angle so it's not perfectly behind (±rad).
     */
    private static final double SPAWN_ANGLE_JITTER = 0.6; // ≈ ±34°

    /** Destination distance from player at the moment of PREPARE (blocks). */
    private static final double DEST_DIST_MIN = 10.0;
    private static final double DEST_DIST_MAX = 30.0;

    /** Destination direction jitter relative to spawn angle (±rad). */
    private static final double DEST_ANGLE_JITTER = 0.45; // ≈ ±26°

    // ── Decoy movement ────────────────────────────────────────────────────────
    /**
     * Minimum gap the decoy keeps ahead of the nearest player.
     * If the player is within this distance, the decoy moves away instead of
     * waiting at its destination. Makes it impossible to "catch" the item.
     */
    private static final double LEAD_DIST = 6.0;

    /** Movement speed toward destination (blocks/tick, ~20 tps → 0.4 b/s). */
    private static final double DECOY_SPEED = 0.02 * 20; // ≈ 0.4 blocks/tick

    /** Arrival threshold — decoy is "at destination" when this close (sq). */
    private static final double ARRIVE_DIST_SQ = 1.5 * 1.5;

    // ── Trap trigger ──────────────────────────────────────────────────────────
    /** Radius within which a player "picks up" the arrived decoy (blocks). */
    private static final double PICKUP_RADIUS = 3.0;

    // ── X17 spawn geometry ────────────────────────────────────────────────────
    /** How far behind the player X17 spawns during SEEKING_SPAWN (blocks). */
    private static final double X17_SPAWN_DIST = 8.0;

    /** Candidate directions sampled when looking for a blind-spot. */
    private static final int SPAWN_CANDIDATES = 16;

    // ── Observing ─────────────────────────────────────────────────────────────
    /**
     * How long X17 stands and watches before vanishing on its own (ticks, 10 s).
     */
    private static final int WATCH_TICKS = 200; // 10 s at 20 tps

    // ── Startup delay ─────────────────────────────────────────────────────────
    /**
     * Ticks to wait after night-start before spawning the decoy (2400 ticks = 2
     * mins).
     */
    private static final int PREPARE_DELAY_TICKS = 2400;

    // ── Night time constants (mirrors X17AISystem) ────────────────────────────
    private static final double NIGHT_START = 0.792;
    private static final double NIGHT_END = 0.208;

    // ── FOV detection (mirrors X17AISystem) ───────────────────────────────────
    private static final double PLAYER_FOV_HALF = 0.38; // ~43° half-angle horizontal
    private static final double PLAYER_PITCH_HALF = 0.52; // ~60° half-angle vertical
    private static final double FOV_RANGE = 72.0; // max detection distance

    // ── State machine ─────────────────────────────────────────────────────────
    private enum TrapState {
        INACTIVE, // night not eligible or already done
        PREPARE, // waiting for PREPARE_DELAY_TICKS
        MOVING, // decoy gliding toward destination
        WAITING, // decoy at destination, waiting for player to get close
        SEEKING_SPAWN, // decoy collected, searching for X17 blind-spot
        OBSERVING, // X17 standing and watching
        DONE // finished — will not act again this night
    }

    // ── Instance state ────────────────────────────────────────────────────────
    private TrapState state = TrapState.INACTIVE;
    private boolean activeNight = false;

    private int prepareCounter = 0;
    private int watchCounter = 0;

    private Ref<EntityStore> decoyRef = null;
    private Ref<EntityStore> x17Ref = null;

    /**
     * The player chosen at PREPARE time (decoy chases nearest player each tick).
     */
    private Ref<EntityStore> victimRef = null;

    /** Final resting position of the decoy. */
    private Vector3d decoyDestination = null;

    /** The weapon role currently used as the decoy NPC. */
    private String decoyRole = DECOY_WEAPON_ROLES[0];

    private final Random rng = new Random();

    // =========================================================================
    // PUBLIC API — called by X17AISystem once per night transition
    // =========================================================================

    /**
     * Called by X17AISystem when the night decision is finalised.
     *
     * @param isSpawnNight true if X17 will physically spawn this night (SPAWN
     *                     decision).
     *                     The trap is disabled on spawn nights — X17 has better
     *                     things to do.
     */
    public void resetShinyTrapNight(boolean isSpawnNight) {
        // Always clean up previous night's entities first
        hardCleanup();

        if (isSpawnNight) {
            // X17 is already manifesting — no decoy needed
            activeNight = false;
            log(Level.INFO, "[ShinyTrap] Spawn night — trap skipped.");
            return;
        }

        double roll = rng.nextDouble();
        activeNight = roll < TRAP_CHANCE;
        state = activeNight ? TrapState.PREPARE : TrapState.INACTIVE;

        log(Level.INFO, "[ShinyTrap] Ghost night | roll=" + String.format("%.2f", roll)
                + " | enabled=" + activeNight);
    }

    // =========================================================================
    // TICK — called every server tick by the engine
    // =========================================================================

    @Override
    public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
        try {
            if (!activeNight || state == TrapState.INACTIVE || state == TrapState.DONE)
                return;

            // Abort if daylight returns
            if (!isNight(store)) {
                done(store, "Daylight — aborting trap.");
                return;
            }

            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null)
                return;
            World world = es.getWorld();

            switch (state) {
                case PREPARE:
                    tickPrepare(store, world);
                    break;
                case MOVING:
                    tickMoving(store, world);
                    break;
                case WAITING:
                    tickWaiting(store, world);
                    break;
                case SEEKING_SPAWN:
                    tickSeekingSpawn(store, world);
                    break;
                case OBSERVING:
                    tickObserving(store, world);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            log(Level.SEVERE, "[ShinyTrap] Tick exception: " + e.getMessage());
            state = TrapState.DONE; // fail-safe
        }
    }

    // =========================================================================
    // STATE: PREPARE
    // =========================================================================

    private void tickPrepare(Store<EntityStore> store, World world) {
        prepareCounter++;
        if (prepareCounter < PREPARE_DELAY_TICKS)
            return;

        // Pick victim — any player in the world
        Player victim = findAnyPlayer(world);
        if (victim == null)
            return;
        victimRef = victim.getReference();

        TransformComponent vTf = store.getComponent(victimRef, TransformComponent.getComponentType());
        if (vTf == null)
            return;

        Vector3d vPos = vTf.getPosition();

        // ── Spawn position: BEHIND the player ────────────────────────────────
        double vYaw = normalizeAngle(vTf.getRotation().getYaw());
        double backAngle = vYaw + Math.PI;
        double spawnAngle = backAngle + (rng.nextDouble() - 0.5) * SPAWN_ANGLE_JITTER;

        Vector3d spawnRaw = new Vector3d(
                vPos.getX() + Math.cos(spawnAngle) * SPAWN_BEHIND_DIST,
                vPos.getY(),
                vPos.getZ() + Math.sin(spawnAngle) * SPAWN_BEHIND_DIST);
        Vector3d spawnPos = snapToGround(world, spawnRaw);

        // ── Destination: away from player in roughly the same direction ───────
        double destDist = DEST_DIST_MIN + rng.nextDouble() * (DEST_DIST_MAX - DEST_DIST_MIN);
        double destAngle = spawnAngle + (rng.nextDouble() - 0.5) * DEST_ANGLE_JITTER;

        Vector3d destRaw = new Vector3d(
                vPos.getX() + Math.cos(destAngle) * destDist,
                vPos.getY(),
                vPos.getZ() + Math.sin(destAngle) * destDist);
        decoyDestination = snapToGround(world, destRaw);

        // ── Pick weapon skin ──────────────────────────────────────────────────
        decoyRole = DECOY_WEAPON_ROLES[rng.nextInt(DECOY_WEAPON_ROLES.length)];

        // ── Spawn the decoy NPC facing the destination ────────────────────────
        boolean spawned = spawnNpc(store, spawnPos, decoyDestination, true);
        if (!spawned) {
            done(store, "Failed to spawn decoy.");
            return;
        }

        log(Level.INFO, "[ShinyTrap] Decoy spawned as \"" + decoyRole + "\" at "
                + fmt(spawnPos) + " → destination " + fmt(decoyDestination)
                + " (" + String.format("%.1f", destDist) + " m away)");

        state = TrapState.MOVING;
    }

    // =========================================================================
    // STATE: MOVING
    // =========================================================================

    private void tickMoving(Store<EntityStore> store, World world) {
        if (!isDecoyAlive()) {
            done(store, "Decoy disappeared during MOVING.");
            return;
        }

        TransformComponent decoyTf = store.getComponent(decoyRef, TransformComponent.getComponentType());
        if (decoyTf == null || decoyDestination == null) {
            done(store, "Decoy transform lost.");
            return;
        }

        Vector3d dPos = decoyTf.getPosition();

        // Distance to final destination
        double toDx = decoyDestination.getX() - dPos.getX();
        double toDz = decoyDestination.getZ() - dPos.getZ();
        double toDestSq = toDx * toDx + toDz * toDz;

        // ── Enforce lead distance from the nearest player ─────────────────────
        // If any player is within LEAD_DIST of the decoy, nudge it away from them
        // regardless of how close it is to the destination.
        Player nearest = findNearestPlayerTo(world, store, dPos);
        if (nearest != null) {
            TransformComponent pTf = store.getComponent(nearest.getReference(), TransformComponent.getComponentType());
            if (pTf != null) {
                double ppx = dPos.getX() - pTf.getPosition().getX();
                double ppz = dPos.getZ() - pTf.getPosition().getZ();
                double distToPlayerSq = ppx * ppx + ppz * ppz;

                if (distToPlayerSq < LEAD_DIST * LEAD_DIST) {
                    // Player is too close — push decoy away from them
                    double dist = Math.sqrt(distToPlayerSq);
                    if (dist > 0.01) {
                        double nx = ppx / dist;
                        double nz = ppz / dist;
                        Vector3d pushed = new Vector3d(
                                dPos.getX() + nx * DECOY_SPEED,
                                dPos.getY(),
                                dPos.getZ() + nz * DECOY_SPEED);
                        moveDecoyTo(store, snapToGround(world, pushed), decoyDestination);
                        return; // don't also do destination-approach this tick
                    }
                }
            }
        }

        // ── Normal approach to destination ────────────────────────────────────
        if (toDestSq <= ARRIVE_DIST_SQ) {
            // Arrived — freeze the decoy at destination
            decoyTf.teleportPosition(decoyDestination);
            log(Level.INFO, "[ShinyTrap] Decoy arrived at destination. Waiting for player…");
            state = TrapState.WAITING;
            return;
        }

        double dist = Math.sqrt(toDestSq);
        double mx = (toDx / dist) * DECOY_SPEED;
        double mz = (toDz / dist) * DECOY_SPEED;

        Vector3d nextRaw = new Vector3d(dPos.getX() + mx, dPos.getY(), dPos.getZ() + mz);
        moveDecoyTo(store, snapToGround(world, nextRaw), decoyDestination);
    }

    /** Teleport the decoy to nextPos and rotate it to face faceTarget. */
    private void moveDecoyTo(Store<EntityStore> store, Vector3d nextPos, Vector3d faceTarget) {
        TransformComponent decoyTf = store.getComponent(decoyRef, TransformComponent.getComponentType());
        if (decoyTf == null)
            return;
        decoyTf.teleportPosition(nextPos);

        double dx = faceTarget.getX() - nextPos.getX();
        double dz = faceTarget.getZ() - nextPos.getZ();
        decoyTf.setRotation(new Vector3f(0f, (float) Math.atan2(dx, dz), 0f));
    }

    // =========================================================================
    // STATE: WAITING
    // =========================================================================

    private void tickWaiting(Store<EntityStore> store, World world) {
        if (!isDecoyAlive()) {
            done(store, "Decoy disappeared during WAITING.");
            return;
        }

        TransformComponent decoyTf = store.getComponent(decoyRef, TransformComponent.getComponentType());
        if (decoyTf == null)
            return;

        // Check every player
        for (Player p : getAllPlayers(world)) {
            if (p == null || p.getReference() == null)
                continue;
            TransformComponent pTf = store.getComponent(p.getReference(), TransformComponent.getComponentType());
            if (pTf == null)
                continue;

            double dx = pTf.getPosition().getX() - decoyTf.getPosition().getX();
            double dz = pTf.getPosition().getZ() - decoyTf.getPosition().getZ();

            if (dx * dx + dz * dz <= PICKUP_RADIUS * PICKUP_RADIUS) {
                // Player "picked up" the decoy
                log(Level.INFO, "[ShinyTrap] Player triggered the trap!");
                sendToVoid(store, decoyRef);
                decoyRef = null;
                state = TrapState.SEEKING_SPAWN;
                return;
            }
        }
    }

    // =========================================================================
    // STATE: SEEKING_SPAWN
    // =========================================================================

    /**
     * Scans SPAWN_CANDIDATES positions in a ring ~X17_SPAWN_DIST blocks behind
     * the victim. A position is valid only if it is outside every online player's
     * FOV. Prefers positions directly behind the victim; falls back to any valid
     * blind spot. Waits indefinitely if all spots are covered.
     */
    private void tickSeekingSpawn(Store<EntityStore> store, World world) {
        if (victimRef == null || !victimRef.isValid()) {
            done(store, "Victim gone.");
            return;
        }

        TransformComponent vTf = store.getComponent(victimRef, TransformComponent.getComponentType());
        if (vTf == null)
            return;

        List<Player> allPlayers = getAllPlayers(world);
        List<TransformComponent> allTfs = new ArrayList<>();
        for (Player p : allPlayers) {
            if (p != null && p.getReference() != null) {
                TransformComponent tf = store.getComponent(p.getReference(), TransformComponent.getComponentType());
                if (tf != null)
                    allTfs.add(tf);
            }
        }

        Vector3d vPos = vTf.getPosition();
        double vYaw = normalizeAngle(vTf.getRotation().getYaw());

        Vector3d bestSpot = null;
        double bestBehindScore = -1.0; // higher = more "behind" victim

        for (int i = 0; i < SPAWN_CANDIDATES; i++) {
            double angle = (Math.PI * 2.0 / SPAWN_CANDIDATES) * i;
            Vector3d candRaw = new Vector3d(
                    vPos.getX() + Math.cos(angle) * X17_SPAWN_DIST,
                    vPos.getY(),
                    vPos.getZ() + Math.sin(angle) * X17_SPAWN_DIST);
            Vector3d cand = snapToGround(world, candRaw);

            // Reject spots inside ANY player's FOV
            boolean visible = false;
            for (TransformComponent ptf : allTfs) {
                if (isWatchedBy(ptf, cand)) {
                    visible = true;
                    break;
                }
            }
            if (visible)
                continue;

            // Score by how "behind" the victim this spot is (dot product trick)
            // victimForward ≈ (sin(yaw), 0, cos(yaw)); behind → angle ~π from forward
            double spotDx = cand.getX() - vPos.getX();
            double spotDz = cand.getZ() - vPos.getZ();
            double spotLen = Math.sqrt(spotDx * spotDx + spotDz * spotDz);
            if (spotLen < 0.01)
                continue;
            double dotFwd = (spotDx / spotLen) * Math.sin(vYaw)
                    + (spotDz / spotLen) * Math.cos(vYaw);
            // dotFwd ≈ -1 means directly behind victim → highest priority
            double score = -dotFwd; // we want the most negative dot (most behind)

            if (bestSpot == null || score > bestBehindScore) {
                bestSpot = cand;
                bestBehindScore = score;
            }
        }

        if (bestSpot == null) {
            // All angles covered — players are watching everywhere. Wait.
            return;
        }

        // Spawn X17 at the chosen blind spot, facing the victim
        boolean spawned = spawnNpc(store, bestSpot, vPos, false);
        if (!spawned) {
            done(store, "Failed to spawn X17.");
            return;
        }

        watchCounter = 0;
        log(Level.INFO, "[ShinyTrap] X17 materialised at " + fmt(bestSpot)
                + " — observing (behind-score=" + String.format("%.2f", bestBehindScore) + ")");
        state = TrapState.OBSERVING;
    }

    // =========================================================================
    // STATE: OBSERVING
    // =========================================================================

    private void tickObserving(Store<EntityStore> store, World world) {
        if (x17Ref == null || !x17Ref.isValid()) {
            done(store, "X17 ref lost.");
            return;
        }

        TransformComponent x17Tf = store.getComponent(x17Ref, TransformComponent.getComponentType());
        if (x17Tf == null)
            return;

        watchCounter++;

        // X17 faces the victim at all times (static except rotation)
        if (victimRef != null && victimRef.isValid()) {
            TransformComponent vTf = store.getComponent(victimRef, TransformComponent.getComponentType());
            if (vTf != null) {
                double dx = vTf.getPosition().getX() - x17Tf.getPosition().getX();
                double dz = vTf.getPosition().getZ() - x17Tf.getPosition().getZ();
                x17Tf.setRotation(new Vector3f(0f, (float) Math.atan2(-dx, dz), 0f));
            }
        }

        // Check if ANY player has X17 in their FOV
        for (Player p : getAllPlayers(world)) {
            if (p == null || p.getReference() == null)
                continue;
            TransformComponent pTf = store.getComponent(p.getReference(), TransformComponent.getComponentType());
            if (pTf == null)
                continue;

            if (isWatchedBy(pTf, x17Tf.getPosition())) {
                log(Level.INFO, "[ShinyTrap] X17 spotted — vanishing instantly.");
                done(store, "Seen by player.");
                return;
            }
        }

        // Timer expiry — leave quietly
        if (watchCounter >= WATCH_TICKS) {
            log(Level.INFO, "[ShinyTrap] X17 watched long enough — fading away.");
            done(store, "Watch timer expired.");
        }
    }

    // =========================================================================
    // SPAWN / CLEANUP HELPERS
    // =========================================================================

    /**
     * Spawns either the decoy weapon NPC or the real X17 NPC via reflection.
     *
     * @param spawnPos where to place the entity
     * @param facePos  the entity faces this point on spawn
     * @param isDecoy  true → use decoyRole; false → use "X_17"
     */
    private boolean spawnNpc(Store<EntityStore> store, Vector3d spawnPos, Vector3d facePos, boolean isDecoy) {
        if (isDecoy) {
            return spawnRealItem(store, spawnPos, facePos);
        }

        try {
            Class<?> npcPluginClass = Class.forName("com.hypixel.hytale.server.npc.NPCPlugin");
            Object npcPlugin = npcPluginClass.getMethod("get").invoke(null);

            int idx = (int) npcPluginClass.getMethod("getIndex", String.class).invoke(npcPlugin, "X_17");

            if (idx < 0) {
                idx = (int) npcPluginClass.getMethod("getIndex", String.class).invoke(npcPlugin, "X17");
            }
            if (idx < 0) {
                log(Level.WARNING, "[ShinyTrap] Role not found: X_17");
                return false;
            }

            Method spawnMethod = null;
            for (Method m : npcPluginClass.getMethods()) {
                if ("spawnEntity".equals(m.getName()) && m.getParameterTypes().length == 6) {
                    spawnMethod = m;
                    break;
                }
            }
            if (spawnMethod == null)
                return false;

            double dx = facePos.getX() - spawnPos.getX();
            double dz = facePos.getZ() - spawnPos.getZ();
            float yaw = (float) Math.atan2(-dx, dz);

            Class<?> cbType = spawnMethod.getParameterTypes()[5];
            Object callback = null;
            if (cbType.isInterface()) {
                callback = Proxy.newProxyInstance(
                        cbType.getClassLoader(),
                        new Class<?>[] { cbType },
                        (proxy, method, args) -> {
                            if (args != null && args.length >= 2 && args[1] instanceof Ref) {
                                @SuppressWarnings("unchecked")
                                Ref<EntityStore> spawnedRef = (Ref<EntityStore>) args[1];
                                x17Ref = spawnedRef;
                            }
                            return null;
                        });
            }

            spawnMethod.invoke(npcPlugin, store, idx, spawnPos, new Vector3f(0f, yaw, 0f), null, callback);
            return true;

        } catch (Exception e) {
            log(Level.WARNING, "[ShinyTrap] spawnNpc(X17) failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Spawns a physical ItemDrop entity using Hytale's ItemComponent generator.
     */
    private boolean spawnRealItem(Store<EntityStore> store, Vector3d spawnPos, Vector3d facePos) {
        try {
            // 1. Create the ItemStack
            Class<?> itemStackCls = Class.forName("com.hypixel.hytale.server.core.inventory.ItemStack");
            Object itemStack = itemStackCls.getConstructor(String.class).newInstance(decoyRole);

            // 2. Find ItemComponent.generateItemDrop
            Class<?> itemCompCls = Class.forName("com.hypixel.hytale.server.core.modules.entity.item.ItemComponent");
            Method generateDrop = null;
            for (Method m : itemCompCls.getMethods()) {
                if ("generateItemDrop".equals(m.getName()) && m.getParameterCount() == 7) {
                    generateDrop = m;
                    break;
                }
            }
            if (generateDrop == null)
                return false;

            double dx = facePos.getX() - spawnPos.getX();
            double dz = facePos.getZ() - spawnPos.getZ();
            float yaw = (float) Math.atan2(-dx, dz);

            Object holder = generateDrop.invoke(null, store, itemStack, spawnPos, new Vector3f(0f, yaw, 0f), 9999f,
                    9999f, 0f);

            if (holder != null) {
                // Get AddReason.SPAWN enum
                Class<?> addReasonClass = Class.forName("com.hypixel.hytale.component.AddReason");
                Object addReasonSpawn = null;
                for (Object ec : addReasonClass.getEnumConstants()) {
                    if (ec.toString().equals("SPAWN")) {
                        addReasonSpawn = ec;
                        break;
                    }
                }

                Class<?> holderClass = Class.forName("com.hypixel.hytale.component.Holder");
                Method addEntityMethod = store.getClass().getMethod("addEntity", holderClass, addReasonClass);
                @SuppressWarnings("unchecked")
                Ref<EntityStore> itemRef = (Ref<EntityStore>) addEntityMethod.invoke(store, holder, addReasonSpawn);

                decoyRef = itemRef;
                return itemRef != null && itemRef.isValid();
            }
            return false;

        } catch (Exception e) {
            log(Level.WARNING, "[ShinyTrap] Failed to spawn physical item drop: " + e.getMessage());
            return false;
        }
    }

    /**
     * Sends an entity to Y = –500 (effectively despawns it without a death event).
     */
    private void sendToVoid(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid())
            return;
        TransformComponent tf = store.getComponent(ref, TransformComponent.getComponentType());
        if (tf != null) {
            tf.teleportPosition(new Vector3d(tf.getPosition().getX(), -500.0, tf.getPosition().getZ()));
        }
    }

    /** Transitions to DONE and clears all spawned entities. */
    private void done(Store<EntityStore> store, String reason) {
        sendToVoid(store, decoyRef);
        sendToVoid(store, x17Ref);
        decoyRef = null;
        x17Ref = null;
        state = TrapState.DONE;
        log(Level.INFO, "[ShinyTrap] Done — " + reason);
    }

    /** Hard cleanup with no store access (called at night start). */
    private void hardCleanup() {
        // Refs become invalid automatically when entities are gone;
        // we just null our pointers and reset all state.
        decoyRef = null;
        x17Ref = null;
        victimRef = null;
        decoyDestination = null;
        state = TrapState.INACTIVE;
        activeNight = false;
        prepareCounter = 0;
        watchCounter = 0;
    }

    // =========================================================================
    // WORLD / GEOMETRY HELPERS
    // =========================================================================

    /**
     * Snaps a world position to the surface directly below it.
     * Sweeps from (pos.Y + 5) downward up to 20 blocks looking for solid ground.
     * Falls back to the original position on chunk load failure.
     */
    private Vector3d snapToGround(World world, Vector3d pos) {
        int x = (int) Math.floor(pos.getX());
        int z = (int) Math.floor(pos.getZ());
        int startY = (int) Math.floor(pos.getY());

        BlockAccessor accessor;
        try {
            accessor = world.getNonTickingChunk(ChunkUtil.indexChunkFromBlock(x, z));
        } catch (Exception e) {
            return pos;
        }
        if (accessor == null)
            return pos;

        for (int y = startY + 5; y >= startY - 15; y--) {
            BlockType bt = world.getBlockType(x, y, z);
            if (bt != null && !isPassable(bt)) {
                return new Vector3d(pos.getX(), y + 1.0, pos.getZ());
            }
        }
        return pos;
    }

    private boolean isPassable(BlockType bt) {
        String id = bt.getId().toLowerCase();
        return id.contains("air") || id.contains("water") || id.contains("lava")
                || id.equals("empty");
    }

    private boolean isDecoyAlive() {
        return decoyRef != null && decoyRef.isValid();
    }

    // =========================================================================
    // FOV DETECTION (mirrors X17AISystem logic)
    // =========================================================================

    /**
     * Returns true if {@code target} falls within the given player's FOV cone.
     *
     * @param playerTf the player's TransformComponent
     * @param target   world position to test
     */
    private boolean isWatchedBy(TransformComponent playerTf, Vector3d target) {
        Vector3d pPos = playerTf.getPosition();
        double dx = target.getX() - pPos.getX();
        double dy = target.getY() - pPos.getY();
        double dz = target.getZ() - pPos.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.001 || dist > FOV_RANGE)
            return false;

        // Horizontal check
        double yawToTarget = Math.atan2(dx, dz);
        double yawDelta = normalizeAngle(yawToTarget - playerTf.getRotation().getYaw());
        if (Math.abs(yawDelta) > PLAYER_FOV_HALF)
            return false;

        // Vertical check
        double pitchToTarget = Math.atan2(dy, Math.sqrt(dx * dx + dz * dz));
        double pitchDelta = normalizeAngle(pitchToTarget - playerTf.getRotation().getX());
        return Math.abs(pitchDelta) <= PLAYER_PITCH_HALF;
    }

    // =========================================================================
    // GENERAL UTILITIES
    // =========================================================================

    private double normalizeAngle(double a) {
        while (a > Math.PI)
            a -= Math.PI * 2;
        while (a < -Math.PI)
            a += Math.PI * 2;
        return a;
    }

    private boolean isNight(Store<EntityStore> store) {
        try {
            return store.getResource(WorldTimeResource.getResourceType())
                    .isDayTimeWithinRange(NIGHT_START, NIGHT_END);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private Player findAnyPlayer(World world) {
        if (world.getPlayers() == null)
            return null;
        for (Player p : world.getPlayers()) {
            if (p != null && p.getReference() != null)
                return p;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private Player findNearestPlayerTo(World world, Store<EntityStore> store, Vector3d pos) {
        Player nearest = null;
        double nearestSq = Double.MAX_VALUE;
        if (world.getPlayers() == null)
            return null;
        for (Player p : world.getPlayers()) {
            if (p == null || p.getReference() == null)
                continue;
            TransformComponent tf = store.getComponent(p.getReference(), TransformComponent.getComponentType());
            if (tf == null)
                continue;
            double dx = tf.getPosition().getX() - pos.getX();
            double dz = tf.getPosition().getZ() - pos.getZ();
            double sq = dx * dx + dz * dz;
            if (sq < nearestSq) {
                nearestSq = sq;
                nearest = p;
            }
        }
        return nearest;
    }

    @SuppressWarnings("deprecation")
    private List<Player> getAllPlayers(World world) {
        List<Player> list = new ArrayList<>();
        if (world.getPlayers() != null) {
            for (Player p : world.getPlayers()) {
                if (p != null)
                    list.add(p);
            }
        }
        return list;
    }

    private String fmt(Vector3d v) {
        return String.format("(%.1f, %.1f, %.1f)", v.getX(), v.getY(), v.getZ());
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, msg);
        }
    }
}