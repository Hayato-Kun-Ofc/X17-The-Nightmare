package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;
import dev.hytalemod.x17.scheduler.X17NightScheduler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

/**
 * X17SoundSystem - v0.2.7
 *
 * Sound pacing is synchronized with scheduler decisions:
 * spawn nights get deceptive local sounds, ghost nights get rarer remote
 * sounds,
 * silent nights remain truly quiet.
 */
public class X17SoundSystem extends TickingSystem<EntityStore> {

    private static final double SPAWN_KNOCK_CHANCE = 0.18;
    private static final double SPAWN_WHISPER_CHANCE = 0.22;
    private static final double GHOST_WHISPER_CHANCE = 0.18;

    private static final int SCAN_INTERVAL_SPAWN = 180;
    private static final int SCAN_INTERVAL_GHOST = 260;
    private static final int COOLDOWN_SPAWN_MIN = 700;
    private static final int COOLDOWN_SPAWN_MAX = 1100;
    private static final int COOLDOWN_GHOST_MIN = 950;
    private static final int COOLDOWN_GHOST_MAX = 1500;

    private static final String SND_DOOR_KNOCK = "SFX_X_17_DoorKnock";
    private static final String SND_WINDOW_KNOCK = "SFX_X_17_WindowKnock";
    private static final String SND_WHISPERS = "SFX_X_17_Stalk";

    private static final Set<String> VALID_DOORS = new HashSet<>(Arrays.asList(
            "furniture_ancient_door", "furniture_ancient_trapdoor",
            "furniture_crude_door", "furniture_crude_trapdoor",
            "furniture_desert_door", "furniture_desert_trapdoor",
            "furniture_feran_door", "furniture_feran_trapdoor",
            "furniture_frozen_castle_door", "furniture_frozen_castle_trapdoor",
            "furniture_royal_magic_medium_door", "furniture_grand_wizard_medium_door",
            "furniture_human_ruins_door", "furniture_human_ruins_door_large",
            "furniture_human_ruins_door_medium", "furniture_human_ruins_trapdoor",
            "furniture_jungle_door", "furniture_jungle_trapdoor",
            "furniture_kweebec_door", "furniture_kweebec_trapdoor",
            "furniture_lumberjack_door", "furniture_lumberjack_trapdoor",
            "furniture_scarak_hive_door_medium", "furniture_scarak_hive_door_large",
            "furniture_tavern_door", "furniture_tavern_trapdoor",
            "furniture_temple_dark_door", "furniture_temple_dark_trapdoor",
            "furniture_temple_dark_door_large", "furniture_temple_dark_door_medium",
            "furniture_temple_earth_door",
            "furniture_temple_emerald_door", "furniture_temple_emerald_trapdoor",
            "furniture_temple_emerald_door_medium",
            "furniture_temple_light_door", "furniture_temple_light_trapdoor",
            "furniture_temple_light_door_large", "furniture_temple_light_door_medilum",
            "furniture_temple_scarak_door_large", "furniture_temple_scarak_door_medium",
            "furniture_temple_wind_door", "furniture_temple_wind_trapdoor",
            "furniture_temple_wind_door_large", "furniture_temple_wind_door_medium",
            "furniture_village_door", "furniture_village_trapdoor",
            "furniture_dungeon_air_door_large"));

    private static final Set<String> VALID_WINDOWS = new HashSet<>(Arrays.asList(
            "furniture_ancient_window", "furniture_crude_window",
            "furniture_crude_windows_1x2_test", "furniture_crude_windows_2x2_test",
            "furniture_desert_window", "furniture_feran_window",
            "furniture_frozen_castle_window", "furniture_royal_magic_window",
            "furniture_human_ruins_window", "furniture_jungle_window",
            "furniture_kweebec_window", "furniture_lumberjack_window",
            "furniture_scarak_hive_window", "furniture_tavern_window",
            "furniture_temple_dark_window", "furniture_temple_emerald_window",
            "furniture_temple_light_window", "furniture_temple_scarak_window",
            "furniture_temple_wind_window", "furniture_village_window",
            "prototype_window_single", "prototype_window_middle",
            "prototype_window_edge_bottom", "prototype_window_edge_left",
            "prototype_window_edge_right", "prototype_window_edge_top",
            "prototype_window_long_horizontal", "prototype_window_long_horizontal_end_l",
            "prototype_window_long_horizontal_end_r",
            "prototype_window_long_vertical", "prototype_window_long_vertical_end_top",
            "prototype_window_vertical_end_bottom",
            "prototype_window_vertical_round_bottom", "prototype_window_vertical_round_top",
            "prototype_window_corner_bottom_left", "prototype_window_corner_bottom_right",
            "prototype_window_corner_top_left", "prototype_window_corner_top_right",
            "prototype_window_corner_rounded_bottom_left", "prototype_window_corner_rounded_bottom_right",
            "prototype_window_corner_rounded_top_left", "prototype_window_corner_rounded_top_right",
            "prototype_window_corner_sloped_bottom_left", "prototype_window_corner_sloped_bottom_right",
            "prototype_window_corner_sloped_top_left", "prototype_window_corner_sloped_top_right"));

    private X17NightScheduler scheduler;
    private int scanTimer = 0;
    private int cooldownLeft = 0;
    private final Random rng = new Random();

    // State flags set by X17AISystem
    private volatile boolean rageActive = false;
    private volatile boolean scareSoundPending = false;
    private volatile Vector3d scareSoundPosition = null;

    private static final String SND_AMBUSH_SCARE = "SFX_X_17_Scare";

    public void setScheduler(X17NightScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Called by X17AISystem when ambush scare triggers. Plays scare sting next
     * tick.
     */
    public void notifyAmbushScare(Vector3d x17Position) {
        scareSoundPending = true;
        scareSoundPosition = x17Position;
    }

    /** Suppresses ambient sounds while X17 is in RAGE. */
    public void notifyRageActivated() {
        rageActive = true;
        cooldownLeft = 0;
    }

    /** Re-enables ambient sounds after RAGE ends. */
    public void notifyRageDeactivated() {
        rageActive = false;
    }

    @Override
    public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
        try {
            // Scare sting has highest priority — fire immediately when pending.
            if (scareSoundPending && scareSoundPosition != null) {
                scareSoundPending = false;
                Vector3d pos = scareSoundPosition;
                scareSoundPosition = null;
                triggerSound(SND_AMBUSH_SCARE, pos, store);
                cooldownLeft = 60;
                return;
            }

            // During RAGE, suppress all ambient stalker sounds.
            if (rageActive)
                return;

            if (cooldownLeft > 0) {
                cooldownLeft--;
                return;
            }

            if (!isNight(store)) {
                return;
            }

            boolean spawnNight = scheduler != null && scheduler.shouldSpawnThisNight();
            boolean ghostNight = scheduler != null && scheduler.isGhostSoundNight();

            if (!spawnNight && !ghostNight) {
                return;
            }

            int scanNeeded = spawnNight ? SCAN_INTERVAL_SPAWN : SCAN_INTERVAL_GHOST;
            if (++scanTimer < scanNeeded) {
                return;
            }
            scanTimer = 0;

            EntityStore entityStore = (EntityStore) store.getExternalData();
            if (entityStore == null || entityStore.getWorld() == null) {
                return;
            }

            World world = entityStore.getWorld();
            Player player = findAnyPlayer(world);
            if (player == null) {
                return;
            }

            TransformComponent pt = store.getComponent(player.getReference(), TransformComponent.getComponentType());
            if (pt == null) {
                return;
            }

            if (spawnNight) {
                tickSpawnNightSounds(pt.getPosition(), store, world);
            } else {
                tickGhostSounds(pt.getPosition(), store);
            }
        } catch (Exception e) {
            log(Level.SEVERE, "Critical error in X17SoundSystem: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void tickSpawnNightSounds(Vector3d playerPos, Store<EntityStore> store, World world) {
        StructureContext context = scanStructureContext(playerPos, world);
        Vector3d deceptivePos = buildDeceptionOffset(playerPos, 10.0, 18.0);
        double roll = rng.nextDouble();

        if (context.doors > 0 && roll < SPAWN_KNOCK_CHANCE) {
            log(Level.INFO, "[SND] Door knock near player.");
            triggerSound(SND_DOOR_KNOCK, playerPos, store);
            cooldownLeft = randomBetween(COOLDOWN_SPAWN_MIN, COOLDOWN_SPAWN_MAX);
            return;
        }

        if (context.windows > 0 && roll < SPAWN_KNOCK_CHANCE + 0.12) {
            log(Level.INFO, "[SND] Window knock near player.");
            triggerSound(SND_WINDOW_KNOCK, playerPos, store);
            cooldownLeft = randomBetween(COOLDOWN_SPAWN_MIN, COOLDOWN_SPAWN_MAX);
            return;
        }

        if (roll < SPAWN_KNOCK_CHANCE + SPAWN_WHISPER_CHANCE) {
            log(Level.INFO, "[SND] Deceptive whisper.");
            triggerSound(SND_WHISPERS, deceptivePos, store);
            cooldownLeft = randomBetween(COOLDOWN_SPAWN_MIN, COOLDOWN_SPAWN_MAX);
            return;
        }

        if (roll < SPAWN_KNOCK_CHANCE + (SPAWN_WHISPER_CHANCE * 2.0)) {
            log(Level.INFO, "[SND] Deceptive whisper (extended).");
            triggerSound(SND_WHISPERS, deceptivePos, store);
            cooldownLeft = randomBetween(COOLDOWN_SPAWN_MIN, COOLDOWN_SPAWN_MAX);
        }
    }

    private void tickGhostSounds(Vector3d playerPos, Store<EntityStore> store) {
        float mult = scheduler != null ? scheduler.getGhostSoundMultiplier() : 1.0f;
        Vector3d deceptivePos = buildDeceptionOffset(playerPos, 18.0, 30.0);

        double whisperChance = Math.min(0.45, GHOST_WHISPER_CHANCE * mult);
        double roll = rng.nextDouble();

        if (roll < whisperChance) {
            log(Level.INFO, "[SND-GHOST] Remote whisper x" + mult);
            triggerSound(SND_WHISPERS, deceptivePos, store);
            cooldownLeft = randomBetween(COOLDOWN_GHOST_MIN, COOLDOWN_GHOST_MAX);
            return;
        }

        if (roll < whisperChance + whisperChance) {
            log(Level.INFO, "[SND-GHOST] Remote whisper (extended) x" + mult);
            triggerSound(SND_WHISPERS, deceptivePos, store);
            cooldownLeft = randomBetween(COOLDOWN_GHOST_MIN, COOLDOWN_GHOST_MAX);
            return;
        }
    }

    private StructureContext scanStructureContext(Vector3d pos, World world) {
        int px = (int) Math.floor(pos.getX());
        int py = (int) Math.floor(pos.getY());
        int pz = (int) Math.floor(pos.getZ());

        int doors = 0;
        int windows = 0;

        for (int x = -6; x <= 6; x++) {
            for (int y = -2; y <= 4; y++) {
                for (int z = -6; z <= 6; z++) {
                    BlockType blockType = world.getBlockType(px + x, py + y, pz + z);
                    if (blockType == null) {
                        continue;
                    }

                    String id = normalizeId(blockType.getId());
                    if (matchesAnyPrefix(id, VALID_DOORS)) {
                        doors++;
                    } else if (matchesAnyPrefix(id, VALID_WINDOWS)) {
                        windows++;
                    }
                }
            }
        }

        return new StructureContext(doors, windows);
    }

    private boolean matchesAnyPrefix(String id, Set<String> candidates) {
        for (String prefix : candidates) {
            if (id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Vector3d buildDeceptionOffset(Vector3d origin, double minDistance, double maxDistance) {
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double distance = minDistance + (rng.nextDouble() * (maxDistance - minDistance));
        return new Vector3d(
                origin.getX() + Math.cos(angle) * distance,
                origin.getY(),
                origin.getZ() + Math.sin(angle) * distance);
    }

    private void triggerSound(String soundId, Vector3d pos, Store<EntityStore> store) {
        try {
            int idx = SoundEvent.getAssetMap().getIndex(soundId);
            if (idx < 0) {
                log(Level.WARNING, "[SND] Not found in asset map: " + soundId);
                return;
            }

            SoundUtil.playSoundEvent3d(idx, SoundCategory.SFX,
                    pos.getX(), pos.getY(), pos.getZ(), store);
            log(Level.INFO, "[SND] " + soundId + " @ "
                    + (int) pos.getX() + "," + (int) pos.getY() + "," + (int) pos.getZ());
        } catch (Exception e) {
            log(Level.SEVERE, "[SND] Failed to play " + soundId + ": " + e.getMessage());
        }
    }

    private String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }

        String id = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
        if (id.startsWith("*")) {
            id = id.substring(1);
        }
        return id.toLowerCase();
    }

    private boolean isNight(Store<EntityStore> store) {
        try {
            return store.getResource(WorldTimeResource.getResourceType())
                    .isDayTimeWithinRange(0.75, 0.25);
        } catch (Exception e) {
            return true;
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

    private int randomBetween(int min, int max) {
        return min + rng.nextInt((max - min) + 1);
    }

    private void log(Level level, String message) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, message);
        }
    }

    private static final class StructureContext {
        private final int doors;
        private final int windows;

        private StructureContext(int doors, int windows) {
            this.doors = doors;
            this.windows = windows;
        }
    }
}
