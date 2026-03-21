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
import dev.hytalemod.x17.scheduler.X17NightScheduler;
import dev.hytalemod.x17.system.X17AISystem;
import dev.hytalemod.x17.system.X17DamageSystem;
import dev.hytalemod.x17.system.X17EventSystem;
import dev.hytalemod.x17.system.X17SoundSystem;
import dev.hytalemod.x17.system.X17TorchExtinguishSystem;
import dev.hytalemod.x17.system.X17ItemStealSystem;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Level;

/**
 * X17Plugin - v0.2.5
 */
public class X17Plugin extends JavaPlugin {

    private static X17Plugin instance;
    private PrintWriter x17LogWriter;
    private ComponentType<EntityStore, X17AIComponent> aiComponentType;
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

        log(Level.INFO, "=== X-17 NIGHTMARE v0.2.5 ===");
        log(Level.INFO, "The darkness awakens...");

        aiComponentType = getEntityStoreRegistry().registerComponent(
                X17AIComponent.class, "x17:ai_controller", X17AIComponent.CODEC);
        X17AIComponent.init(aiComponentType);
        log(Level.INFO, "Registered: x17:ai_controller");

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
        
        aiSystem.setSoundSystem(soundSystem);
        aiSystem.setTorchSystem(torchSystem);
        aiSystem.setStealSystem(stealSystem);
        aiSystem.setScheduler(scheduler);
        soundSystem.setScheduler(scheduler);

        try {
            getEntityStoreRegistry().registerSystem(aiSystem);
            getEntityStoreRegistry().registerSystem(new X17DamageSystem(aiSystem));
            getEntityStoreRegistry().registerSystem(soundSystem);
            log(Level.INFO, "Registered: X17AISystem, X17DamageSystem, X17SoundSystem, X17TorchExtinguishSystem, X17ItemStealSystem");
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
        log(Level.INFO, "    N1:30%  N2:50%  N3:65%  N4:75%  N5+:80%");
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
        try {
            File logDir = new File("UserData/Logs/x17_logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            x17LogWriter = new PrintWriter(
                    new FileWriter(new File(logDir, "x17_nightmare.log"), true));
            x17LogWriter.println("=== X-17 Logger Started ===");
        } catch (IOException e) {
            System.err.println("Failed to initialize X17 logger: " + e.getMessage());
        }
    }

    public void log(Level level, String message) {
        System.out.println("[X-17 " + level.getName() + "] " + message);
        if (x17LogWriter != null) {
            x17LogWriter.println("[" + level.getName() + "] " + message);
            x17LogWriter.flush();
        }
    }
}