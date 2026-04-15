package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;

/**
 * X17ShadowsSystem - v0.3.0
 *
 * Rare paranormal event for ghost/silent nights (when X17 is NOT actively
 * spawned).
 * On qualifying nights, a single roll determines if the event triggers.
 * If enabled, 5 shadow copies of X17 spawn in a ring ~30 blocks around
 * the nearest player, all facing them, standing motionless.
 *
 * SHADOW BEHAVIOUR:
 * - All shadows face the player continuously.
 * - Each shadow has a 2-second (40 tick) lifetime.
 * - If the player looks directly at a shadow, it vanishes instantly.
 * - After 40 ticks, all remaining shadows vanish automatically.
 * - Shadows have 100 HP but cannot attack — purely psychological.
 *
 * ENTITY LIFECYCLE:
 * The Hytale ECS Store API does not expose a destroyEntity method.
 * Shadow entities are managed with a POOLING strategy:
 * 1. On first trigger: spawn SHADOW_COUNT fresh entities via NPCPlugin.
 * 2. To "despawn": teleport to Y=-200 (underground, invisible to player).
 * 3. On next trigger: REUSE existing refs if valid (teleport from -200 to
 * new positions). Only spawn new entities if refs became invalid.
 * This ensures zero entity accumulation across nights.
 *
 * ROLL SYSTEM:
 * Uses the same single-roll pattern as X17TorchExtinguishSystem.
 * resetShadowNight() is called by X17AISystem.resetTorchNight() at the
 * start of every night. The roll happens once; if true, the event is
 * activated after a delay.
 *
 * Note: Chance set to 100% for testing. Change SHADOW_CHANCE
 * to 0.35 (35%) for production.
 */
public class X17ShadowsSystem extends TickingSystem<EntityStore> {

    // ── Configuration ─────────────────────────────────────────────────────────

    /**
     * Chance to trigger the shadow event on non-spawn nights.
     * Set to 1.0 (100%) for testing — change to 0.35 (35%) for production.
     */
    private static final double SHADOW_CHANCE = 0.35;

    /** Number of shadow entities to spawn around the player. */
    private static final int SHADOW_COUNT = 5;

    /** Distance (blocks) from the player where shadows spawn. */
    private static final double SHADOW_DISTANCE = 30.0;

    /**
     * How long (ticks) each shadow remains before auto-vanish. 100t = 5 seconds.
     */
    private static final int SHADOW_LIFETIME_TICKS = 100;

    /**
     * Delay (ticks) after night starts before shadows can trigger. 2000 ticks for 1
     * min and 40 secs
     */
    private static final int SHADOW_ACTIVATION_DELAY = 2000;

    /** Y coordinate used to hide "despawned" shadow entities underground. */
    private static final double POOL_HIDE_Y = -200.0;

    // ── FOV detection constants (mirrors X17AISystem) ─────────────────────────

    private static final double PLAYER_FOV_HALF = 0.38;
    private static final double PLAYER_PITCH_HALF = 0.52;
    private static final double LOOK_RANGE = 72.0;

    // ── Night-time range ──────────────────────────────────────────────────────

    private static final double NIGHT_START = 0.792;
    private static final double NIGHT_END = 0.208;

    // ── Per-night state ───────────────────────────────────────────────────────

    private boolean shadowEnabledThisNight = false;
    private boolean shadowEventDoneThisNight = false;
    private int shadowDelayCounter = 0;
    private boolean shadowsActive = false;
    private int shadowLifetimeCounter = 0;

    /**
     * Entity pool — persists across nights. Refs are reused to avoid
     * accumulating entities. Only populated the first time shadows spawn.
     * Each entry tracks the entity ref, its pool slot index, and whether
     * it is currently "visible" (active in the world) or "hidden" (at Y=-200).
     */
    private final List<ShadowEntry> entityPool = new ArrayList<>();

    private final Random rng = new Random();

    // =========================================================================
    // PUBLIC API — called by X17AISystem.resetTorchNight()
    // =========================================================================

    /**
     * Rolls the per-night shadow decision. Called at the start of every night.
     * Only enables on non-spawn nights (ghost/silent).
     *
     * @param isSpawnNight true if X17 is scheduled to appear this night
     */
    public void resetShadowNight(boolean isSpawnNight) {
        // Reset per-night flags (pool is NOT cleared — intentional for reuse)
        shadowEventDoneThisNight = false;
        shadowDelayCounter = 0;
        shadowsActive = false;
        shadowLifetimeCounter = 0;

        // Ensure all pooled shadows are hidden at night start
        // (safety net in case a previous night didn't clean up properly)
        markAllPoolHidden();

        if (isSpawnNight) {
            // X17 is active tonight — no shadows
            shadowEnabledThisNight = false;
            log(Level.INFO, "[Shadows] Night rolled: spawn night — shadows disabled.");
            return;
        }

        // Single roll, same pattern as torch system
        double roll = rng.nextDouble();
        shadowEnabledThisNight = roll < SHADOW_CHANCE;
        log(Level.INFO, "[Shadows] Night rolled: non-spawn"
                + " | chance=" + String.format("%.0f%%", SHADOW_CHANCE * 100)
                + " | roll=" + String.format("%.4f", roll)
                + " | enabled=" + shadowEnabledThisNight);
    }

    // =========================================================================
    // TICK — called every server tick by the engine
    // =========================================================================

    @Override
    public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
        try {
            // Skip if not enabled or already done for this night
            if (!shadowEnabledThisNight || shadowEventDoneThisNight) {
                return;
            }

            // Only active at night
            if (!isNight(store)) {
                return;
            }

            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null) {
                return;
            }

            World world = es.getWorld();

            // ── Phase 1: Waiting for activation delay ─────────────────────────
            if (!shadowsActive) {
                shadowDelayCounter++;
                if (shadowDelayCounter < SHADOW_ACTIVATION_DELAY) {
                    return;
                }

                // Time to activate shadows
                Player targetPlayer = findAnyPlayer(world);
                if (targetPlayer == null || targetPlayer.getReference() == null) {
                    return;
                }

                TransformComponent playerTf = store.getComponent(
                        targetPlayer.getReference(), TransformComponent.getComponentType());
                if (playerTf == null) {
                    return;
                }

                activateShadowRing(store, world, playerTf);
                shadowsActive = true;
                shadowLifetimeCounter = 0;
                return;
            }

            // ── Phase 2: Shadows are active — tick every frame ────────────────
            shadowLifetimeCounter++;

            Player targetPlayer = findAnyPlayer(world);
            if (targetPlayer == null || targetPlayer.getReference() == null) {
                hideAllShadows(store, "no player found");
                return;
            }

            TransformComponent playerTf = store.getComponent(
                    targetPlayer.getReference(), TransformComponent.getComponentType());
            if (playerTf == null) {
                hideAllShadows(store, "player transform null");
                return;
            }

            int visibleCount = 0;

            for (int i = 0; i < entityPool.size(); i++) {
                ShadowEntry shadow = entityPool.get(i);

                // Skip already-hidden shadows
                if (!shadow.visible) {
                    continue;
                }

                // Validate ref is still usable
                if (shadow.ref == null || !shadow.ref.isValid()) {
                    shadow.visible = false;
                    continue;
                }

                TransformComponent shadowTf = store.getComponent(
                        shadow.ref, TransformComponent.getComponentType());
                if (shadowTf == null) {
                    shadow.visible = false;
                    continue;
                }

                // Always face the player
                faceTarget(shadowTf, playerTf.getPosition());

                // [DISABLED FOR TESTING] Shadows no longer vanish when observed.
                // Re-enable for production by uncommenting the block below.
                // if (isPlayerWatchingShadow(playerTf, shadowTf)) {
                // log(Level.INFO, "[Shadows] Shadow #" + shadow.index
                // + " vanished (observed by player) at "
                // + formatPos(shadowTf.getPosition()));
                // hideShadow(shadowTf, shadow);
                // continue;
                // }

                // Lifetime expired — auto vanish
                if (shadowLifetimeCounter >= SHADOW_LIFETIME_TICKS) {
                    log(Level.INFO, "[Shadows] Shadow #" + shadow.index
                            + " vanished (lifetime expired) at "
                            + formatPos(shadowTf.getPosition()));
                    hideShadow(shadowTf, shadow);
                    continue;
                }

                visibleCount++;
            }

            // All shadows gone — event complete for this night
            if (visibleCount == 0) {
                shadowsActive = false;
                shadowEventDoneThisNight = true;
                log(Level.INFO, "[Shadows] Shadow event complete — all shadows vanished.");
            }

        } catch (Exception e) {
            log(Level.SEVERE, "[Shadows] Exception in tick: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================================
    // ACTIVATION: Position shadows around the player
    // =========================================================================

    /**
     * Activates SHADOW_COUNT shadows in a ring around the player.
     * Reuses pooled entities if available; spawns new ones only if needed.
     */
    private void activateShadowRing(Store<EntityStore> store, World world,
            TransformComponent playerTf) {

        Vector3d playerPos = playerTf.getPosition();
        double baseAngle = rng.nextDouble() * Math.PI * 2.0;
        double angleStep = (Math.PI * 2.0) / SHADOW_COUNT;

        log(Level.INFO, "[Shadows] *** SHADOW EVENT TRIGGERED *** Activating "
                + SHADOW_COUNT + " shadows around player at " + formatPos(playerPos));

        // Prune invalid refs from the pool before activation
        pruneInvalidPoolEntries();

        int reused = 0;
        int spawned = 0;

        for (int i = 0; i < SHADOW_COUNT; i++) {
            // Calculate position for this shadow
            double jitter = (rng.nextDouble() - 0.5) * 0.35; // ±10° randomness
            double angle = baseAngle + (angleStep * i) + jitter;
            double distance = SHADOW_DISTANCE + (rng.nextDouble() - 0.5) * 6.0;

            Vector3d spawnPos = new Vector3d(
                    playerPos.getX() + Math.cos(angle) * distance,
                    playerPos.getY(),
                    playerPos.getZ() + Math.sin(angle) * distance);

            // Try to reuse a hidden pool entry
            ShadowEntry existing = findHiddenPoolEntry();
            if (existing != null) {
                TransformComponent tf = store.getComponent(
                        existing.ref, TransformComponent.getComponentType());
                if (tf != null) {
                    tf.teleportPosition(spawnPos);
                    faceTarget(tf, playerPos);
                    existing.visible = true;
                    reused++;
                    log(Level.INFO, "[Shadows] Shadow #" + existing.index
                            + " REUSED at " + formatPos(spawnPos)
                            + " | dist=" + String.format("%.1f", distance));
                    continue;
                } else {
                    // Ref valid but no transform — mark for removal
                    existing.ref = null;
                }
            }

            // No reusable entry — spawn a fresh entity
            boolean success = spawnShadowEntity(store, spawnPos, playerPos, i);
            if (success) {
                spawned++;
                log(Level.INFO, "[Shadows] Shadow #" + i + " SPAWNED at "
                        + formatPos(spawnPos)
                        + " | dist=" + String.format("%.1f", distance)
                        + " | angle=" + String.format("%.2f", Math.toDegrees(angle)) + "°");
            } else {
                log(Level.WARNING, "[Shadows] Shadow #" + i + " FAILED to spawn.");
            }
        }

        int totalVisible = 0;
        for (ShadowEntry e : entityPool) {
            if (e.visible)
                totalVisible++;
        }

        log(Level.INFO, "[Shadows] Activation complete — " + totalVisible
                + " / " + SHADOW_COUNT + " shadows active"
                + " (reused=" + reused + ", spawned=" + spawned
                + ", pool size=" + entityPool.size() + ").");
    }

    /**
     * Spawns a single shadow entity using NPCPlugin reflection.
     * Same spawn chain as X17EventSystem.spawnJavaX17().
     */
    private boolean spawnShadowEntity(Store<EntityStore> store, Vector3d spawnPos,
            Vector3d playerPos, int shadowIndex) {
        try {
            Class<?> npcPluginClass = Class.forName("com.hypixel.hytale.server.npc.NPCPlugin");
            Object npcPlugin = npcPluginClass.getMethod("get").invoke(null);
            if (npcPlugin == null) {
                log(Level.WARNING, "[Shadows] NPCPlugin unavailable.");
                return false;
            }

            int roleIndex = (int) npcPluginClass.getMethod("getIndex", String.class)
                    .invoke(npcPlugin, "X_17");
            if (roleIndex < 0) {
                roleIndex = (int) npcPluginClass.getMethod("getIndex", String.class)
                        .invoke(npcPlugin, "X17");
            }
            if (roleIndex < 0) {
                log(Level.WARNING, "[Shadows] Role X_17 not found.");
                return false;
            }

            Method spawnMethod = null;
            for (Method method : npcPluginClass.getMethods()) {
                if ("spawnEntity".equals(method.getName())
                        && method.getParameterTypes().length == 6) {
                    spawnMethod = method;
                    break;
                }
            }

            if (spawnMethod == null) {
                log(Level.WARNING, "[Shadows] Method spawnEntity not found on NPCPlugin.");
                return false;
            }

            // Calculate initial facing rotation toward player
            double dx = playerPos.getX() - spawnPos.getX();
            double dz = playerPos.getZ() - spawnPos.getZ();
            float yaw = (float) Math.atan2(-dx, dz);

            final int idx = shadowIndex;
            Class<?> postSpawnType = spawnMethod.getParameterTypes()[5];
            Object postSpawn = null;
            if (postSpawnType.isInterface()) {
                postSpawn = Proxy.newProxyInstance(
                        postSpawnType.getClassLoader(),
                        new Class<?>[] { postSpawnType },
                        (proxy, method, args) -> {
                            if (args != null && args.length >= 2 && args[1] instanceof Ref) {
                                @SuppressWarnings("unchecked")
                                Ref<EntityStore> spawnedRef = (Ref<EntityStore>) args[1];
                                ShadowEntry entry = new ShadowEntry(spawnedRef, idx);
                                entry.visible = true;
                                entityPool.add(entry);
                                log(Level.INFO, "[Shadows] Shadow #" + idx
                                        + " entity ref captured (pool slot "
                                        + (entityPool.size() - 1) + ").");
                            }
                            return null;
                        });
            }

            Object result = spawnMethod.invoke(
                    npcPlugin,
                    store,
                    roleIndex,
                    spawnPos,
                    new Vector3f(0f, yaw, 0f),
                    null,
                    postSpawn);

            return result != null;

        } catch (Exception e) {
            log(Level.WARNING, "[Shadows] Failed to spawn shadow #" + shadowIndex
                    + ": " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // HIDE / SHOW — the "despawn" mechanism
    // =========================================================================

    /**
     * Hides a single shadow by teleporting it underground and marking it
     * as not visible. The entity ref stays in the pool for future reuse.
     */
    private void hideShadow(TransformComponent shadowTf, ShadowEntry shadow) {
        try {
            shadowTf.teleportPosition(new Vector3d(
                    shadowTf.getPosition().getX(),
                    POOL_HIDE_Y,
                    shadowTf.getPosition().getZ()));
            shadow.visible = false;
        } catch (Exception e) {
            log(Level.WARNING, "[Shadows] Failed to hide shadow #" + shadow.index
                    + ": " + e.getMessage());
            shadow.visible = false;
        }
    }

    /**
     * Hides all visible shadows — used when the event ends abruptly
     * (player disconnects, etc).
     */
    private void hideAllShadows(Store<EntityStore> store, String reason) {
        for (ShadowEntry shadow : entityPool) {
            if (!shadow.visible)
                continue;
            if (shadow.ref == null || !shadow.ref.isValid()) {
                shadow.visible = false;
                continue;
            }
            TransformComponent tf = store.getComponent(
                    shadow.ref, TransformComponent.getComponentType());
            if (tf != null) {
                hideShadow(tf, shadow);
            } else {
                shadow.visible = false;
            }
        }
        shadowsActive = false;
        shadowEventDoneThisNight = true;
        log(Level.INFO, "[Shadows] All shadows hidden: " + reason);
    }

    /**
     * Marks all pool entries as hidden without touching transforms.
     * Used as a safety net at the start of each night.
     */
    private void markAllPoolHidden() {
        for (ShadowEntry shadow : entityPool) {
            shadow.visible = false;
        }
    }

    // =========================================================================
    // POOL MANAGEMENT
    // =========================================================================

    /**
     * Removes entries from the pool whose refs have become invalid
     * (e.g. after server restart, world unload).
     */
    private void pruneInvalidPoolEntries() {
        int before = entityPool.size();
        entityPool.removeIf(e -> e.ref == null || !e.ref.isValid());
        int removed = before - entityPool.size();
        if (removed > 0) {
            log(Level.INFO, "[Shadows] Pruned " + removed
                    + " invalid pool entries. Pool size: " + entityPool.size());
        }
    }

    /**
     * Finds a hidden (not visible) entry in the pool that can be reused.
     * Returns null if all pool entries are currently visible or the pool is empty.
     */
    private ShadowEntry findHiddenPoolEntry() {
        for (ShadowEntry entry : entityPool) {
            if (!entry.visible && entry.ref != null && entry.ref.isValid()) {
                return entry;
            }
        }
        return null;
    }

    // =========================================================================
    // LOOK DETECTION (mirrors X17AISystem.isPlayerWatchingX17)
    // =========================================================================

    /**
     * Returns true if the player is looking directly at the shadow entity.
     * Uses the same FOV cone check as the main AI system.
     */
    private boolean isPlayerWatchingShadow(TransformComponent playerTf,
            TransformComponent shadowTf) {
        Vector3d pPos = playerTf.getPosition();
        Vector3d sPos = shadowTf.getPosition();
        double dx = sPos.getX() - pPos.getX();
        double dy = sPos.getY() - pPos.getY();
        double dz = sPos.getZ() - pPos.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.001 || dist > LOOK_RANGE) {
            return false;
        }

        double yawToShadow = Math.atan2(dx, dz);
        double yawDelta = normalizeAngle(yawToShadow - playerTf.getRotation().getYaw());
        if (Math.abs(yawDelta) > PLAYER_FOV_HALF) {
            return false;
        }

        double pitchToShadow = Math.atan2(dy, Math.sqrt(dx * dx + dz * dz));
        double pitchDelta = normalizeAngle(pitchToShadow - playerTf.getRotation().getX());
        return Math.abs(pitchDelta) <= PLAYER_PITCH_HALF;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private void faceTarget(TransformComponent tf, Vector3d target) {
        Vector3d pos = tf.getPosition();
        tf.setRotation(new Vector3f(0f,
                (float) Math.atan2(-(target.getX() - pos.getX()),
                        target.getZ() - pos.getZ()),
                0f));
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
        if (world.getPlayers() == null) {
            return null;
        }
        for (Player player : world.getPlayers()) {
            if (player != null && player.getReference() != null) {
                return player;
            }
        }
        return null;
    }

    private double normalizeAngle(double a) {
        while (a > Math.PI)
            a -= Math.PI * 2;
        while (a < -Math.PI)
            a += Math.PI * 2;
        return a;
    }

    private String formatPos(Vector3d pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.getX(), pos.getY(), pos.getZ());
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, "[X17-Shadows] " + msg);
        }
    }

    // =========================================================================
    // INNER CLASS: Shadow entity pool entry
    // =========================================================================

    private static final class ShadowEntry {
        Ref<EntityStore> ref;
        final int index;
        boolean visible;

        ShadowEntry(Ref<EntityStore> ref, int index) {
            this.ref = ref;
            this.index = index;
            this.visible = false;
        }
    }
}
