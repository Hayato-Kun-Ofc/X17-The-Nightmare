package dev.hytalemod.x17;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.component.X17AIComponent;
import dev.hytalemod.x17.component.X17PlayerComponent;
import dev.hytalemod.x17.component.X18AIComponent;
import dev.hytalemod.x17.scheduler.X17NightScheduler;
import dev.hytalemod.x17.scheduler.X18CaveDayScheduler;
import dev.hytalemod.x17.system.X17AISystem;
import dev.hytalemod.x17.system.X17DamageSystem;
import dev.hytalemod.x17.system.X17EventSystem;
import dev.hytalemod.x17.system.X17SoundSystem;
import dev.hytalemod.x17.system.X17TorchExtinguishSystem;
import dev.hytalemod.x17.system.X17ItemStealSystem;
import dev.hytalemod.x17.system.X17ShadowsSystem;
import dev.hytalemod.x17.system.X17ShinyTrapSystem;
import dev.hytalemod.x17.system.X18AISystem;
import dev.hytalemod.x17.system.X18BlackScreenSafetySystem;
import dev.hytalemod.x17.system.X18CaveSpawnSystem;
import dev.hytalemod.x17.system.X18CaveGhostSoundSystem;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;

/**
 * X17Plugin - v0.3.8
 *
 */
public class X17Plugin extends JavaPlugin {

    private static volatile X17Plugin instance;
    private PrintWriter x17LogWriter;
    private volatile boolean loggerInitialised = false;
    private ComponentType<EntityStore, X17AIComponent> aiComponentType;
    private ComponentType<EntityStore, X18AIComponent> x18AIComponentType;
    private ComponentType<EntityStore, X17PlayerComponent> playerComponentType;

    public X17Plugin(JavaPluginInit init) {
        super(init);
    }

    public static X17Plugin getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        super.setup();
        instance = this;
        setupLogger();

        log(Level.INFO, "=== X-17 NIGHTMARE v0.3.8 ===");
        log(Level.INFO, "The darkness awakens...");

        aiComponentType = getEntityStoreRegistry().registerComponent(
                X17AIComponent.class, "x17:ai_controller", X17AIComponent.CODEC);
        X17AIComponent.init(aiComponentType);
        log(Level.INFO, "Registered: x17:ai_controller");

        x18AIComponentType = getEntityStoreRegistry().registerComponent(
                X18AIComponent.class, "x17:x18_ai_controller", X18AIComponent.CODEC);
        X18AIComponent.init(x18AIComponentType);
        log(Level.INFO, "Registered: x17:x18_ai_controller");

        playerComponentType = getEntityStoreRegistry().registerComponent(
                X17PlayerComponent.class, "x17:player_state", X17PlayerComponent.CODEC);
        X17PlayerComponent.init(playerComponentType);
        log(Level.INFO, "Registered: x17:player_state");

        final X17NightScheduler scheduler = new X17NightScheduler();
        log(Level.INFO, "Created: X17NightScheduler");

        final X17AISystem aiSystem = new X17AISystem();
        final X17SoundSystem soundSystem = new X17SoundSystem();
        final X17TorchExtinguishSystem torchSystem = new X17TorchExtinguishSystem();
        final X17ItemStealSystem stealSystem = new X17ItemStealSystem();
        final X17ShadowsSystem shadowsSystem = new X17ShadowsSystem();
        final X17ShinyTrapSystem shinyTrapSystem = new X17ShinyTrapSystem();

        aiSystem.setSoundSystem(soundSystem);
        aiSystem.setTorchSystem(torchSystem);
        aiSystem.setStealSystem(stealSystem);
        aiSystem.setShadowsSystem(shadowsSystem);
        aiSystem.setShinyTrapSystem(shinyTrapSystem);
        aiSystem.setScheduler(scheduler);
        soundSystem.setScheduler(scheduler);

        try {
            getEntityStoreRegistry().registerSystem(aiSystem);
            getEntityStoreRegistry().registerSystem(new X18CaveDayScheduler());
            getEntityStoreRegistry().registerSystem(new X18AISystem());
            getEntityStoreRegistry().registerSystem(new X18BlackScreenSafetySystem());
            getEntityStoreRegistry().registerSystem(new X18CaveSpawnSystem());
            getEntityStoreRegistry().registerSystem(new X18CaveGhostSoundSystem());
            getEntityStoreRegistry().registerSystem(new X17DamageSystem(aiSystem));
            getEntityStoreRegistry().registerSystem(soundSystem);
            getEntityStoreRegistry().registerSystem(shadowsSystem);
            getEntityStoreRegistry().registerSystem(shinyTrapSystem);
            log(Level.INFO,
                    "Registered: X17AISystem, X18CaveDayScheduler, X18AISystem, X18BlackScreenSafetySystem, X18CaveSpawnSystem, X18CaveGhostSoundSystem, X17DamageSystem, X17SoundSystem, X17ShadowsSystem, X17ShinyTrapSystem");
        } catch (Exception e) {
            log(Level.WARNING, "Failed to register ticking systems: " + e.getMessage());
        }

        final X17EventSystem eventSystem = new X17EventSystem(this, aiSystem, scheduler, soundSystem);
        try {
            eventSystem.registerEvents();
            log(Level.INFO, "Registered: X17EventSystem");
        } catch (Exception e) {
            log(Level.WARNING, "Failed to register EventSystem: " + e.getMessage());
        }

        try {
            TickingSystem<EntityStore> schedulerWorldTick = new TickingSystem<EntityStore>() {
                @Override
                public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
                    try {
                        eventSystem.worldTick(store);
                    } catch (Exception e) {
                        log(Level.WARNING, "[SchedulerWorldTick] " + e.getMessage());
                    }
                }
            };
            getEntityStoreRegistry().registerSystem(schedulerWorldTick);
            log(Level.INFO, "Registered: SchedulerWorldTick");
        } catch (Exception e) {
            log(Level.WARNING, "Failed to register SchedulerWorldTick: " + e.getMessage());
        }

        try {
            EntityTickingSystem<EntityStore> forceDespawnTick = new EntityTickingSystem<EntityStore>() {
                @Override
                public void tick(float deltaTime, int index,
                        ArchetypeChunk<EntityStore> chunk,
                        Store<EntityStore> store,
                        CommandBuffer<EntityStore> commandBuffer) {
                    try {
                        X17AIComponent ai = (X17AIComponent) chunk.getComponent(
                                index, X17AIComponent.getComponentType());
                        if (ai != null) {
                            eventSystem.applySpawnBlockIfNeeded(ai);
                        }
                    } catch (Exception e) {
                        // Silent on purpose: this runs every entity tick.
                    }
                }

                @Override
                public Query<EntityStore> getQuery() {
                    return X17AIComponent.getComponentType();
                }
            };
            getEntityStoreRegistry().registerSystem(forceDespawnTick);
            log(Level.INFO, "Registered: SchedulerForceDespawnTick");
        } catch (Exception e) {
            log(Level.WARNING, "Failed to register SchedulerForceDespawnTick: " + e.getMessage());
        }

        log(Level.INFO, "=== All systems online. X-17 is watching. ===");
        log(Level.INFO, "    N1:30%  N2:45%  N3:55%  N4:60%  N5+:65%");
        log(Level.INFO, "=== X-18 JUMPSCARE SPAWNER ACTIVE ===");
        log(Level.INFO, "    X-18 will spawn in caves and assault players!");
    }

    @Override
    protected void shutdown() {
        super.shutdown();
        log(Level.INFO, "X-17 Nightmare Mod shutting down.");
        if (x17LogWriter != null) {
            x17LogWriter.close();
        }
    }

    private void setupLogger() {
        File logDir = new File("UserData/Logs/x17_logs");
        if (!logDir.exists() && !logDir.mkdirs()) {
            System.err.println("[X-17] Could not create log dir "
                    + logDir.getAbsolutePath()
                    + " - falling back to ./x17_nightmare.log");
            logDir = new File(".");
        }
        try {
            x17LogWriter = new PrintWriter(
                    new FileWriter(new File(logDir, "x17_nightmare.log"), true),
                    true); // autoFlush = true
            x17LogWriter.println("=== X-17 Logger Started ===");
            loggerInitialised = true;
        } catch (IOException e) {
            System.err.println("[X-17] Failed to initialize X17 logger: "
                    + e.getMessage());
            // x17LogWriter stays null - log() will skip file output,
            // console output still works.
        }

        // Safety-net shutdown hook so the writer is closed even if shutdown()
        // is never called (e.g. JVM crash, SIGKILL during a hard stop).
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (x17LogWriter != null) {
                    try {
                        x17LogWriter.close();
                    } catch (Exception ignored) {
                    }
                }
            }, "X17-Logger-Shutdown"));
        } catch (IllegalStateException ignored) {
            // Shutdown already in progress - ignore.
        } catch (Exception ignored) {
            // Security manager may block - ignore.
        }
    }

    public void log(Level level, String message) {
        // X_18 messages go exclusively to the file log - never to the main client log.
        // Identified by the prefixes used in X18AISystem, X18CaveSpawnSystem, and
        // X18BlackScreenPage: "[AI]", "[Spawner]", "[X18-CaveSpawn]".
        boolean isX18Message = message != null && (message.contains("[AI]") ||
                message.contains("[Spawner]") ||
                message.contains("[X18-CaveSpawn]") ||
                message.contains("[X18-CaveDay]") ||
                message.contains("[X18-GhostCave]"));

        if (!isX18Message && !isQuietMainLogMessage(level, message)) {
            System.out.println("[X-17 " + level.getName() + "] " + message);
        }
        if (x17LogWriter != null) {
            x17LogWriter.println("[" + level.getName() + "] " + message);
            // autoFlush=true on the PrintWriter handles flushing.
        }
    }

    /**
     * Logs an exception with its full stack trace through the mod's log channel.
     * Use this instead of e.printStackTrace() so traces land in the mod's log
     * file rather than stderr.
     */
    public void logException(Level level, String context, Throwable t) {
        if (t == null) {
            log(level, context + " (null throwable)");
            return;
        }
        log(level, context + ": " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
        for (StackTraceElement el : t.getStackTrace()) {
            log(level, "    at " + el);
        }
        Throwable cause = t.getCause();
        while (cause != null) {
            log(level, "  Caused by: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            for (StackTraceElement el : cause.getStackTrace()) {
                log(level, "    at " + el);
            }
            cause = cause.getCause();
        }
    }

    /**
     * Unwraps InvocationTargetException to expose the underlying cause.
     * Spawn paths call NPCPlugin.spawnEntity() via reflection; when the spawn
     * fails the real exception is wrapped and getMessage() returns null.
     * This helper returns the cause (or the original throwable if no cause).
     */
    public static Throwable unwrapReflective(Exception e) {
        if (e instanceof InvocationTargetException) {
            Throwable cause = e.getCause();
            return cause != null ? cause : e;
        }
        return e;
    }

    private boolean isQuietMainLogMessage(Level level, String message) {
        if (level.intValue() > Level.INFO.intValue() || message == null) {
            return false;
        }
        return message.contains("[Steal] No accessible chests with loot found.");
    }
}
