package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;
import dev.hytalemod.x17.component.X18AIComponent;
import dev.hytalemod.x17.scheduler.X18CaveDayScheduler;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * Creates the first X_18 entity with the same Java NPCPlugin pattern used by
 * X_17. After creation, X18AISystem pools/reuses that entity.
 *
 */
public class X18CaveSpawnSystem extends TickingSystem<EntityStore> {

    private static final double CAVE_Y_LIMIT          = X18AIComponent.CAVE_ENTER_Y;
    private static final double POOL_HIDE_Y           = 2.0;
    private static final int    TEST_CAVE_DELAY_TICKS = 20;

    // World restriction
    // X-18 only spawns in the main overworld. All instances, dungeons,
    // creative hubs, and alternate worlds are completely ignored.
    // Mirrors the same set used by X17EventSystem.
    private static final Set<String> ALLOWED_WORLD_NAMES = new HashSet<>(
            Arrays.asList("default", "default_world", "world"));

    private int caveTicks          = 0;
    private int retryCooldownTicks = 0;

    @Override
    public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
        try {
            // World restriction guard - skip non-overworld stores entirely
            if (!isAllowedWorld(resolveWorldName(store))) {
                return;
            }

            if (X18AIComponent.getComponentType() == null) {
                return;
            }

            if (store.getEntityCountFor(X18AIComponent.getComponentType()) > 0) {
                // X_18 already exists in this store - nothing to do
                return;
            }

            if (retryCooldownTicks > 0) {
                retryCooldownTicks--;
                return;
            }

            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null) {
                return;
            }
            World world = es.getWorld();

            if (!X18CaveDayScheduler.isRealCaveDay(world, store)) {
                caveTicks = 0;
                return;
            }

            TransformComponent playerTf = findCavePlayer(world, store);
            if (playerTf == null) {
                caveTicks = 0;
                return;
            }

            caveTicks++;
            if (caveTicks < TEST_CAVE_DELAY_TICKS) {
                return;
            }

            // Spawn X_18 at the player's position (valid world bounds).
            // X18AISystem.tickHidden() immediately re-hides it underground on
            // the first tick - spawning at y=POOL_HIDE_Y caused the engine to
            // throw a bounds exception (e.getMessage() == null) because y=-31
            // is outside the world floor (limit y=0).
            Vector3d spawnPos = new Vector3d(playerTf.getPosition());

            if (spawnJavaX18(store, spawnPos, playerTf.getPosition())) {
                retryCooldownTicks = 40;
                caveTicks          = 0;
                log(Level.INFO, "[Spawner] X_18 spawned @ " + formatPos(spawnPos));
            } else {
                retryCooldownTicks = 40;
                caveTicks          = 0; // reset so delay restarts on next attempt
            }
        } catch (Exception e) {
            log(Level.WARNING, "[Spawner] Exception: " + e.getMessage());
        }
    }

    /**
     * initialise lastKnownPlayerPos to a meaningful location rather than (0,0,0).
     */
    private boolean spawnJavaX18(Store<EntityStore> store, Vector3d spawnPos,
            Vector3d playerInitialPos) {
        try {
            Class<?> npcPluginClass = Class.forName("com.hypixel.hytale.server.npc.NPCPlugin");
            Object   npcPlugin      = npcPluginClass.getMethod("get").invoke(null);
            if (npcPlugin == null) {
                log(Level.WARNING, "[Spawner] NPCPlugin unavailable. Could not spawn X_18.");
                return false;
            }

            int roleIndex = (int) npcPluginClass
                    .getMethod("getIndex", String.class).invoke(npcPlugin, "X_18");
            if (roleIndex < 0) {
                roleIndex = (int) npcPluginClass
                        .getMethod("getIndex", String.class).invoke(npcPlugin, "X18");
            }
            if (roleIndex < 0) {
                log(Level.WARNING, "[Spawner] Role X_18 not found for Java spawn.");
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
                log(Level.WARNING, "[Spawner] Method spawnEntity not found on NPCPlugin.");
                return false;
            }

            Class<?> postSpawnType = spawnMethod.getParameterTypes()[5];
            Object   postSpawn     = null;
            if (postSpawnType.isInterface()) {
                final Vector3d capturedPlayerPos = playerInitialPos;
                postSpawn = Proxy.newProxyInstance(
                        postSpawnType.getClassLoader(),
                        new Class<?>[] { postSpawnType },
                        (proxy, method, args) -> {
                            if (args != null && args.length >= 2 && args[1] instanceof Ref) {
                                @SuppressWarnings("unchecked")
                                Ref<EntityStore> spawnedRef = (Ref<EntityStore>) args[1];
                                ensureX18AIComponent(store, spawnedRef, capturedPlayerPos);
                            }
                            return null;
                        });
            }

            Object result = spawnMethod.invoke(
                    npcPlugin,
                    store,
                    roleIndex,
                    spawnPos,
                    new com.hypixel.hytale.math.vector.Rotation3f(0f, 0f, 0f),
                    null,
                    postSpawn);

            if (result == null) {
                log(Level.WARNING, "[Spawner] spawnEntity returned null - X_18 not created.");
                return false;
            }
            return true;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            String msg = cause != null
                    ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    : "InvocationTargetException (no cause)";
            log(Level.WARNING, "[Spawner] Failed to spawn X_18 via Java: " + msg);
            if (cause != null) {
                for (StackTraceElement el : cause.getStackTrace()) {
                    log(Level.WARNING, "    at " + el);
                }
            }
            return false;
        } catch (Exception e) {
            log(Level.WARNING, "[Spawner] Failed to spawn X_18 via Java: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Called from the postSpawn callback immediately after the engine creates
     * the X_18 entity. Attaches the AI component and teleports the entity
     * underground so it is never visible during the 1-tick window between
     * spawn and the first X18AISystem tick.
     *
     * that the first hide() call in X18AISystem has valid coordinates.
     */
    private void ensureX18AIComponent(Store<EntityStore> store,
            Ref<EntityStore> entityRef, Vector3d playerInitialPos) {
        try {
            X18AIComponent ai = store.ensureAndGetComponent(
                    entityRef, X18AIComponent.getComponentType());
            if (ai == null) {
                log(Level.WARNING, "[Spawner] Failed to attach X18AIComponent.");
                return;
            }
            ai.setCurrentState(X18AIComponent.X18State.HIDDEN);
            ai.setSpawnCooldownTicks(X18AIComponent.INITIAL_SPAWN_COOLDOWN);
            ai.setActionTimerTicks(0);
            ai.setDamageDone(false);
            ai.setHitsTaken(0);
            if (playerInitialPos != null) {
                ai.setLastKnownPlayerPos(
                        playerInitialPos.x(),
                        playerInitialPos.y(),
                        playerInitialPos.z());
            }

            // Teleport to pool immediately - entity was spawned at the player's
            // position (valid y) to satisfy the engine's bounds check, but must
            // be invisible before X18AISystem takes over on the next tick.
            TransformComponent tf = store.getComponent(
                    entityRef, TransformComponent.getComponentType());
            if (tf != null && playerInitialPos != null) {
                tf.teleportPosition(new Vector3d(
                        playerInitialPos.x(), POOL_HIDE_Y, playerInitialPos.z()));
            }
        } catch (Exception e) {
            log(Level.WARNING, "[Spawner] Failed to ensure X_18 AI component: " + e.getMessage());
        }
    }

    // =========================================================================
    // WORLD RESTRICTION HELPERS
    // =========================================================================

    private static boolean isAllowedWorld(String worldName) {
        if (worldName == null)
            return false;
        return ALLOWED_WORLD_NAMES.contains(worldName.toLowerCase());
    }

    private String resolveWorldName(Store<EntityStore> store) {
        try {
            EntityStore es = (EntityStore) store.getExternalData();
            if (es != null && es.getWorld() != null && es.getWorld().getName() != null) {
                return es.getWorld().getName();
            }
        } catch (Exception ignored) {
        }
        return "default";
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Returns the TransformComponent of the first player found in a cave
     * (y <= CAVE_Y_LIMIT). Returns null if no cave player exists.
     *
     */
    private TransformComponent findCavePlayer(World world, Store<EntityStore> store) {
        if (world == null || world.getPlayerRefs() == null) {
            return null;
        }
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            if (playerRef == null || playerRef.getReference() == null) {
                continue;
            }
            Player player = store.getComponent(
                    playerRef.getReference(), Player.getComponentType());
            if (player == null) {
                continue;
            }
            TransformComponent tf = store.getComponent(
                    playerRef.getReference(), TransformComponent.getComponentType());
            if (tf != null && tf.getPosition().y() <= CAVE_Y_LIMIT) {
                return tf;
            }
        }
        return null;
    }

    private String formatPos(Vector3d pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.x(), pos.y(), pos.z());
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, "[X18-CaveSpawn] " + msg);
        }
    }
}