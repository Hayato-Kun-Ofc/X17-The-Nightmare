package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;
import dev.hytalemod.x17.component.X17AIComponent;
import dev.hytalemod.x17.component.X17PlayerComponent;
import dev.hytalemod.x17.scheduler.X17NightScheduler;
import dev.hytalemod.x17.ui.X17WelcomePage;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Level;

/**
 * X17EventSystem - v2.4
 */
public class X17EventSystem {

    private final X17Plugin plugin;
    private final X17AISystem aiSystem;
    private final X17NightScheduler scheduler;
    private final X17SoundSystem soundSystem;
    private final Random rng = new Random();

    private boolean lastKnownNight = false;
    private boolean javaSpawnDoneThisNight = false;
    private int javaSpawnRetryCooldownTicks = 0;
    private int currentNightSpawnDelayTicks = 0;
    private int currentNightPresenceBudgetTicks = 0;
    private boolean currentNightAttackAllowed = false;

    // Tracks which night number was last fully configured on the AI component.
    // Prevents synchronizeNightDirective from resetting the budget on every tick.
    private int lastConfiguredNightNumber = -1;

    public X17EventSystem(X17Plugin plugin, X17AISystem aiSystem,
            X17NightScheduler scheduler, X17SoundSystem soundSystem) {
        this.plugin = plugin;
        this.aiSystem = aiSystem;
        this.scheduler = scheduler;
        this.soundSystem = soundSystem;
    }

    public void registerEvents() {
        EventRegistry events = plugin.getEventRegistry();

        try {
            events.register(PlayerConnectEvent.class, this::onPlayerConnect);
            plugin.log(Level.INFO, "Registered: PlayerConnectEvent");
        } catch (Exception e) {
            plugin.log(Level.SEVERE, "Failed: PlayerConnectEvent - " + e.getMessage());
        }

        try {
            events.registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
            plugin.log(Level.INFO, "Registered (global): PlayerReadyEvent");
        } catch (Exception e) {
            plugin.log(Level.SEVERE, "Failed: PlayerReadyEvent - " + e.getMessage());
        }

        try {
            events.register(PlayerDisconnectEvent.class, this::onPlayerDisconnect);
            plugin.log(Level.INFO, "Registered: PlayerDisconnectEvent");
        } catch (Exception e) {
            plugin.log(Level.SEVERE, "Failed: PlayerDisconnectEvent - " + e.getMessage());
        }
    }

    public void worldTick(Store<EntityStore> store) {
        if (store == null) {
            return;
        }

        boolean isNight = checkIsNight(store);
        String worldName = resolveWorldName(store);

        scheduler.tick(isNight, worldName);

        // Night transition: day night
        if (isNight && !lastKnownNight) {
            prepareNightDirective();
            applyNightDecision(store);
            javaSpawnDoneThisNight = false;
            javaSpawnRetryCooldownTicks = 0;

            // Reset the per-night cycle counter in the AI system.
            if (scheduler.shouldSpawnThisNight()) {
                aiSystem.resetNightCycles();
            }

            // BUG FIX 1: On a new spawn night, reconfigure the existing entity
            // (if it survived from a previous night) with the new night's budget
            // and directives don't just skip it because "entity count > 0".
            reconfigureExistingEntityForNewNight(store);
        }

        // This is the Day reset
        if (!isNight) {
            javaSpawnDoneThisNight = false;
            lastConfiguredNightNumber = -1;
            javaSpawnRetryCooldownTicks = 0;
        } else if (scheduler.shouldSpawnThisNight()) {
            tryEnsureJavaSpawn(store);
        }

        // BUG FIX 2: synchronizeNightDirective only pushes spawn/ghost flags,
        // NOT the budget, budget is only set once per night in
        // reconfigureExistingEntityForNewNight / ensureX17AIComponent.
        synchronizeSpawnFlags(store, isNight);

        lastKnownNight = isNight;
    }

    private void prepareNightDirective() {
        if (scheduler.shouldSpawnThisNight()) {
            currentNightSpawnDelayTicks = randomBetween(70, 200);
            currentNightPresenceBudgetTicks = randomBetween(
                    X17AIComponent.NIGHT_ACTIVITY_MIN_TICKS,
                    X17AIComponent.NIGHT_ACTIVITY_MAX_TICKS);
            currentNightAttackAllowed = rng.nextDouble() < 0.35;
        } else {
            currentNightSpawnDelayTicks = 0;
            currentNightPresenceBudgetTicks = 0;
            currentNightAttackAllowed = false;
        }
    }

    /**
     * BUG FIX 1 - called once at the start of each night.
     *
     * The X17 entity may still exist from a previous night (sitting DORMANT with
     * exhausted budget). Instead of skipping it because "entity count > 0",
     * we reconfigure it with this night's fresh budget and directives so it can
     * become active again.
     */
    private void reconfigureExistingEntityForNewNight(Store<EntityStore> store) {
        int currentNight = scheduler.getCurrentNight();
        if (lastConfiguredNightNumber == currentNight) {
            return; // Already configured for this night.
        }
        lastConfiguredNightNumber = currentNight;

        final boolean allowSpawn = scheduler.shouldSpawnThisNight();
        final boolean ghostNight = scheduler.isGhostSoundNight();

        store.forEachChunk(X17AIComponent.getComponentType(),
                (ArchetypeChunk<EntityStore> chunk,
                        com.hypixel.hytale.component.CommandBuffer<EntityStore> ignored) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        X17AIComponent ai = chunk.getComponent(i, X17AIComponent.getComponentType());
                        if (ai == null)
                            continue;

                        // Full reconfigure: resets budget and all night-specific flags.
                        ai.configureNightDirective(
                                currentNight,
                                allowSpawn,
                                ghostNight,
                                currentNightAttackAllowed,
                                currentNightSpawnDelayTicks,
                                currentNightPresenceBudgetTicks);

                        // Reset state to DORMANT so AI begins fresh each night.
                        ai.setCurrentState(X17AIComponent.X17State.DORMANT);
                        ai.setSpawnCheckDone(false);
                        ai.setSpawnCooldownTicks(currentNightSpawnDelayTicks);
                        ai.setVanishTimerTicks(0);
                        ai.setAttackAttemptedThisNight(false);
                        ai.setHuntCommitmentTicks(0);
                        ai.setLookExposureTicks(0);
                        ai.setAppearanceHoldTicks(0);

                        plugin.log(Level.INFO, "[EventSystem] Reconfigured existing X17 for night "
                                + currentNight + " | spawn=" + allowSpawn
                                + " | budget=" + currentNightPresenceBudgetTicks
                                + " | attack=" + currentNightAttackAllowed);
                    }
                });
    }

    /**
     * BUG FIX 2 -replaces synchronizeNightDirective.
     *
     * The old version called configureNightDirective every single tick, which
     * meant the presence budget was RESET to its starting value on every tick
     * 
     * X17 would never exhaust its budget. This version only pushes the lightweight
     * spawn/ghost boolean flags, leaving the budget untouched.
     */
    private void synchronizeSpawnFlags(Store<EntityStore> store, boolean isNight) {
        final boolean allowSpawn = isNight && scheduler.shouldSpawnThisNight();
        final boolean ghostNight = isNight && scheduler.isGhostSoundNight();

        store.forEachChunk(X17AIComponent.getComponentType(),
                (ArchetypeChunk<EntityStore> chunk,
                        com.hypixel.hytale.component.CommandBuffer<EntityStore> ignored) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        X17AIComponent ai = chunk.getComponent(i, X17AIComponent.getComponentType());
                        if (ai == null)
                            continue;

                        // Only update the flags that change at runtime, not the budget.
                        ai.setSpawnAllowedThisNight(allowSpawn);
                        ai.setGhostSoundNight(ghostNight);
                    }
                });
    }

    private void applyNightDecision(Store<EntityStore> store) {
        X17NightScheduler.NightDecision decision = scheduler.getCurrentDecision();

        plugin.log(Level.INFO, "[Scheduler] NIGHT " + scheduler.getCurrentNight()
                + " | Decision: " + decision
                + (scheduler.isTensionNight() ? " [TENSION]" : ""));

        switch (decision) {
            case SPAWN:
                plugin.log(Level.INFO, "[Scheduler] Java spawn allowed. Delay="
                        + currentNightSpawnDelayTicks + " ticks | Presence budget="
                        + currentNightPresenceBudgetTicks + " ticks | Attack night="
                        + currentNightAttackAllowed + ".");
                break;
            case GHOST_SOUNDS:
            case SILENT:
                plugin.log(Level.INFO, "[Scheduler] Spawn blocked tonight. Ghost="
                        + scheduler.isGhostSoundNight() + " x" + scheduler.getGhostSoundMultiplier());
                break;
            default:
                break;
        }
    }

    private void onPlayerConnect(PlayerConnectEvent event) {
        plugin.log(Level.INFO, "A soul entered the darkness...");
    }

    @SuppressWarnings("deprecation")
    private void onPlayerReady(PlayerReadyEvent event) {
        try {
            plugin.log(Level.INFO, "Player ready. Checking welcome UI...");

            PlayerRef playerRef = event.getPlayer().getPlayerRef();
            Store<EntityStore> store = event.getPlayerRef().getStore();
            UUID uuid = playerRef.getUuid();
            String worldName = resolveWorldName(store);

            X17PlayerComponent playerComp = store.getComponent(
                    playerRef.getReference(), X17PlayerComponent.getComponentType());

            if (playerComp == null) {
                store.addComponent(playerRef.getReference(), X17PlayerComponent.getComponentType());
                playerComp = store.getComponent(playerRef.getReference(), X17PlayerComponent.getComponentType());
            }

            if (playerComp == null) {
                plugin.log(Level.WARNING, "X17PlayerComponent is null for " + uuid + ". Showing welcome anyway.");
                X17WelcomePage.showTo(playerRef, store);
                return;
            }

            if (playerComp.hasSeenWelcomeInWorld(worldName)) {
                plugin.log(Level.INFO, "Welcome already seen by " + uuid + " in: " + worldName);
                return;
            }

            playerComp.markWelcomeAsSeen(worldName);
            plugin.log(Level.INFO, "Showing welcome UI to " + uuid + " in: " + worldName);
            X17WelcomePage.showTo(playerRef, store);
        } catch (Exception e) {
            plugin.log(Level.SEVERE, "Error in PlayerReady: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        plugin.log(Level.INFO, "A soul escaped... for now.");
    }

    public void applySpawnBlockIfNeeded(X17AIComponent ai) {
        if (scheduler.shouldSpawnThisNight()) {
            return;
        }

        ai.setSpawnAllowedThisNight(false);
        ai.setGhostSoundNight(scheduler.isGhostSoundNight());
        ai.setAttackNight(false);

        X17AIComponent.X17State state = ai.getCurrentState();
        if (state == X17AIComponent.X17State.DORMANT || state == X17AIComponent.X17State.VANISH) {
            if (ai.getSpawnCooldownTicks() < 200) {
                ai.setSpawnCooldownTicks(200);
            }
            if (ai.getAmbushScareCooldownTicks() < 12000) {
                ai.setAmbushScareCooldownTicks(12000);
            }
            return;
        }

        ai.setCurrentState(X17AIComponent.X17State.VANISH);
        ai.setVanishTimerTicks(1);
    }

    private boolean checkIsNight(Store<EntityStore> store) {
        try {
            WorldTimeResource tr = store.getResource(WorldTimeResource.getResourceType());
            return tr.isDayTimeWithinRange(0.792, 0.208);
        } catch (Exception e) {
            return false;
        }
    }

    private void tryEnsureJavaSpawn(Store<EntityStore> store) {
        if (!scheduler.shouldSpawnThisNight()) {
            return;
        }

        // BUG FIX 3: The old code checked entity count > 0 and skipped spawning.
        // This caused X17 to never respawn after night 1 because the entity
        // persists as DORMANT between nights. Instead, we only skip if
        // reconfigureExistingEntityForNewNight already handled an existing entity.
        int x17Count = store.getEntityCountFor(X17AIComponent.getComponentType());
        if (x17Count > 0) {
            javaSpawnDoneThisNight = true;
            return;
        }

        if (javaSpawnRetryCooldownTicks > 0) {
            javaSpawnRetryCooldownTicks--;
            return;
        }

        EntityStore es = (EntityStore) store.getExternalData();
        if (es == null || es.getWorld() == null) {
            return;
        }

        World world = es.getWorld();
        TransformComponent playerTransform = findNearestPlayerTransform(world, store);
        if (playerTransform == null) {
            return;
        }

        Vector3d spawnPos = buildSpawnPositionNear(playerTransform);
        double spawnDistance = spawnPos.distanceTo(playerTransform.getPosition());
        int spawnDelay = javaSpawnDoneThisNight ? 1 : currentNightSpawnDelayTicks;

        if (spawnJavaX17(store, spawnPos, spawnDelay)) {
            javaSpawnDoneThisNight = true;
            javaSpawnRetryCooldownTicks = 40;
            plugin.log(Level.INFO, "[Scheduler] X17 "
                    + (spawnDelay == 1 ? "respawned" : "spawned")
                    + " via Java at " + formatPos(spawnPos)
                    + " | Distance=" + String.format("%.1f", spawnDistance));
        } else {
            javaSpawnRetryCooldownTicks = 40;
        }
    }

    private boolean spawnJavaX17(Store<EntityStore> store, Vector3d spawnPos, int aiSpawnDelayTicks) {
        try {
            Class<?> npcPluginClass = Class.forName("com.hypixel.hytale.server.npc.NPCPlugin");
            Object npcPlugin = npcPluginClass.getMethod("get").invoke(null);
            if (npcPlugin == null) {
                plugin.log(Level.WARNING, "[Scheduler] NPCPlugin unavailable. Could not spawn X17.");
                return false;
            }

            int roleIndex = (int) npcPluginClass.getMethod("getIndex", String.class).invoke(npcPlugin, "X_17");
            if (roleIndex < 0) {
                roleIndex = (int) npcPluginClass.getMethod("getIndex", String.class).invoke(npcPlugin, "X17");
            }
            if (roleIndex < 0) {
                plugin.log(Level.WARNING, "[Scheduler] Role X_17 not found for Java spawn.");
                return false;
            }

            Method spawnMethod = null;
            for (Method method : npcPluginClass.getMethods()) {
                if ("spawnEntity".equals(method.getName()) && method.getParameterTypes().length == 6) {
                    spawnMethod = method;
                    break;
                }
            }

            if (spawnMethod == null) {
                plugin.log(Level.WARNING, "[Scheduler] Method spawnEntity not found on NPCPlugin.");
                return false;
            }

            Class<?> postSpawnType = spawnMethod.getParameterTypes()[5];
            Object postSpawn = null;
            if (postSpawnType.isInterface()) {
                final int delayForAi = aiSpawnDelayTicks;
                postSpawn = Proxy.newProxyInstance(
                        postSpawnType.getClassLoader(),
                        new Class<?>[] { postSpawnType },
                        (proxy, method, args) -> {
                            if (args != null && args.length >= 2 && args[1] instanceof Ref) {
                                @SuppressWarnings("unchecked")
                                Ref<EntityStore> spawnedRef = (Ref<EntityStore>) args[1];
                                ensureX17AIComponent(store, spawnedRef, delayForAi);
                            }
                            return null;
                        });
            }

            Object result = spawnMethod.invoke(
                    npcPlugin,
                    store,
                    roleIndex,
                    spawnPos,
                    new Vector3f(0f, 0f, 0f),
                    null,
                    postSpawn);

            if (result == null) {
                plugin.log(Level.WARNING, "[Scheduler] spawnEntity returned null for X17.");
                return false;
            }
            return true;
        } catch (Exception e) {
            plugin.log(Level.WARNING, "[Scheduler] Failed to spawn X17 via Java: " + e.getMessage());
            return false;
        }
    }

    private void ensureX17AIComponent(Store<EntityStore> store, Ref<EntityStore> entityRef, int spawnDelayTicks) {
        try {
            X17AIComponent ai = store.ensureAndGetComponent(entityRef, X17AIComponent.getComponentType());
            if (ai == null) {
                plugin.log(Level.WARNING, "[Scheduler] Failed to attach X17AIComponent.");
                return;
            }

            ai.configureNightDirective(
                    scheduler.getCurrentNight(),
                    scheduler.shouldSpawnThisNight(),
                    scheduler.isGhostSoundNight(),
                    currentNightAttackAllowed,
                    spawnDelayTicks,
                    currentNightPresenceBudgetTicks);
            ai.setCurrentState(X17AIComponent.X17State.DORMANT);
            ai.setSpawnCheckDone(false);
            ai.setSpawnCooldownTicks(spawnDelayTicks);
            ai.setVanishTimerTicks(0);
        } catch (Exception e) {
            plugin.log(Level.WARNING, "[Scheduler] Failed to ensure X17 AI component: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private TransformComponent findNearestPlayerTransform(World world, Store<EntityStore> store) {
        if (world.getPlayers() == null || world.getPlayers().isEmpty()) {
            return null;
        }

        for (Player player : world.getPlayers()) {
            if (player == null || player.getReference() == null) {
                continue;
            }

            TransformComponent pt = store.getComponent(player.getReference(), TransformComponent.getComponentType());
            if (pt == null) {
                continue;
            }
            return pt;
        }

        return null;
    }

    private Vector3d buildSpawnPositionNear(TransformComponent playerTransform) {
        Vector3d playerPos = playerTransform.getPosition();
        double playerYaw = playerTransform.getRotation().getYaw();
        // Prefer back/side back spawn to avoid immediate close-range reveal.
        double center = playerYaw + Math.PI;
        double spread = 2.6;
        double angle = center + randomRange(-spread / 2.0, spread / 2.0);
        double distance = randomRange(42.0, 64.0);
        return new Vector3d(
                playerPos.getX() + Math.cos(angle) * distance,
                playerPos.getY(),
                playerPos.getZ() + Math.sin(angle) * distance);
    }

    private double randomRange(double min, double max) {
        return min + (rng.nextDouble() * (max - min));
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

    private int randomBetween(int min, int max) {
        return min + rng.nextInt((max - min) + 1);
    }

    private String formatPos(Vector3d pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.getX(), pos.getY(), pos.getZ());
    }
}
