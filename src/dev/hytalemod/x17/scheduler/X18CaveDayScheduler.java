package dev.hytalemod.x17.scheduler;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * X18CaveDayScheduler - v0.3.8
 *
 * Small cave-day scheduler for X_18. It mirrors the X_17 night idea without
 * sharing state with X_17: every world day becomes one of three cave modes.
 *
 * REAL : X_18 may spawn and physically stalk the player in caves.
 * GHOST_CAVE : X_18 does not spawn; only rare cave sounds may happen.
 * SILENT_CAVE: X_18 does not spawn and no X_18 cave sounds happen.
 */
public class X18CaveDayScheduler extends TickingSystem<EntityStore> {

    private static final int REAL_CHANCE_PCT = 35;
    private static final int GHOST_CHANCE_PCT = 40;
    private static final int FORCE_REAL_AFTER_NO_REAL_DAYS = 3;

    private static final ConcurrentHashMap<String, CaveDayState> STATE_BY_WORLD = new ConcurrentHashMap<>();

    public enum CaveDayMode {
        REAL,
        GHOST_CAVE,
        SILENT_CAVE
    }

    @Override
    public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
        try {
            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null) {
                return;
            }
            getMode(es.getWorld(), store);
        } catch (Exception e) {
            log(Level.WARNING, "Tick exception: " + e.getMessage());
        }
    }

    public static CaveDayMode getMode(World world, Store<EntityStore> store) {
        String worldName = worldKey(world);
        CaveDayState state = STATE_BY_WORLD.computeIfAbsent(worldName, k -> new CaveDayState());

        int currentDay = resolveCurrentDay(store);
        if (currentDay < 0) {
            return state.mode;
        }

        synchronized (state) {
            if (state.dayKey != currentDay) {
                rollDay(state, worldName, currentDay);
            }
            return state.mode;
        }
    }

    public static boolean isRealCaveDay(World world, Store<EntityStore> store) {
        return getMode(world, store) == CaveDayMode.REAL;
    }

    public static boolean isGhostCaveDay(World world, Store<EntityStore> store) {
        return getMode(world, store) == CaveDayMode.GHOST_CAVE;
    }

    public static boolean isSilentCaveDay(World world, Store<EntityStore> store) {
        return getMode(world, store) == CaveDayMode.SILENT_CAVE;
    }

    private static void rollDay(CaveDayState state, String worldName, int currentDay) {
        state.dayKey = currentDay;

        if (state.daysWithoutReal >= FORCE_REAL_AFTER_NO_REAL_DAYS) {
            state.mode = CaveDayMode.REAL;
            state.daysWithoutReal = 0;
            log(Level.INFO, "[X18-CaveDay] " + worldName + " day " + currentDay
                    + " -> REAL (forced after " + FORCE_REAL_AFTER_NO_REAL_DAYS
                    + " non-real cave days).");
            return;
        }

        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < REAL_CHANCE_PCT) {
            state.mode = CaveDayMode.REAL;
            state.daysWithoutReal = 0;
        } else if (roll < REAL_CHANCE_PCT + GHOST_CHANCE_PCT) {
            state.mode = CaveDayMode.GHOST_CAVE;
            state.daysWithoutReal++;
        } else {
            state.mode = CaveDayMode.SILENT_CAVE;
            state.daysWithoutReal++;
        }

        log(Level.INFO, "[X18-CaveDay] " + worldName + " day " + currentDay
                + " -> " + state.mode + " (roll=" + roll
                + ", noRealStreak=" + state.daysWithoutReal + ").");
    }

    private static int resolveCurrentDay(Store<EntityStore> store) {
        try {
            WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
            if (time == null || time.getGameDateTime() == null) {
                return -1;
            }
            return time.getGameDateTime().getYear() * 365
                    + time.getGameDateTime().getDayOfYear();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String worldKey(World world) {
        if (world == null || world.getName() == null || world.getName().isEmpty()) {
            return "default";
        }
        return world.getName().toLowerCase();
    }

    private static void log(Level level, String message) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, message);
        }
    }

    private static final class CaveDayState {
        int dayKey = Integer.MIN_VALUE;
        int daysWithoutReal = 0;
        CaveDayMode mode = CaveDayMode.REAL;
    }
}