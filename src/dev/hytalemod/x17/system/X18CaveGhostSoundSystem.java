package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;
import dev.hytalemod.x17.component.X18AIComponent;
import dev.hytalemod.x17.scheduler.X18CaveDayScheduler;
import org.joml.Vector3d;

import java.util.Random;
import java.util.logging.Level;

/**
 * Rare sound-only cave haunt used when X18CaveDayScheduler rolls GHOST_CAVE.
 * It never spawns X_18 and never attacks the player.
 */
public class X18CaveGhostSoundSystem extends TickingSystem<EntityStore> {

    private static final String SND_STALK = "SFX_X_18_Stalk";
    private static final String SND_ALERTED = "SFX_X_18_Alerted";

    private static final int COOLDOWN_MIN = 1800; // 90 seconds
    private static final int COOLDOWN_MAX = 3600; // 3 minutes
    private static final double SOUND_CHANCE = 0.45;
    private static final double MIN_OFFSET = 12.0;
    private static final double MAX_OFFSET = 26.0;

    private final Random rng = new Random();
    private int cooldownTicks = COOLDOWN_MIN;

    @Override
    public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
        try {
            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null) {
                return;
            }

            World world = es.getWorld();
            if (!X18CaveDayScheduler.isGhostCaveDay(world, store)) {
                cooldownTicks = COOLDOWN_MIN;
                return;
            }

            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }

            TransformComponent playerTf = findCavePlayer(world, store);
            if (playerTf == null) {
                cooldownTicks = 200;
                return;
            }

            if (rng.nextDouble() <= SOUND_CHANCE) {
                Vector3d pos = buildCaveOffset(playerTf.getPosition());
                String sound = rng.nextInt(100) < 80 ? SND_STALK : SND_ALERTED;
                triggerSound(sound, pos, store);
            }

            cooldownTicks = randomBetween(COOLDOWN_MIN, COOLDOWN_MAX);
        } catch (Exception e) {
            log(Level.WARNING, "[X18-GhostCave] Exception: " + e.getMessage());
            cooldownTicks = COOLDOWN_MIN;
        }
    }

    private TransformComponent findCavePlayer(World world, Store<EntityStore> store) {
        if (world == null || world.getPlayerRefs() == null) {
            return null;
        }
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            if (playerRef == null || playerRef.getReference() == null) {
                continue;
            }
            if (store.getComponent(playerRef.getReference(), Player.getComponentType()) == null) {
                continue;
            }
            TransformComponent tf = store.getComponent(
                    playerRef.getReference(), TransformComponent.getComponentType());
            if (tf != null && tf.getPosition().y() <= X18AIComponent.CAVE_ENTER_Y) {
                return tf;
            }
        }
        return null;
    }

    private Vector3d buildCaveOffset(Vector3d playerPos) {
        double angle = rng.nextDouble() * Math.PI * 2.0;
        double dist = MIN_OFFSET + rng.nextDouble() * (MAX_OFFSET - MIN_OFFSET);
        double yOffset = -3.0 + rng.nextDouble() * 7.0;
        return new Vector3d(
                playerPos.x() + Math.sin(angle) * dist,
                Math.max(1.0, playerPos.y() + yOffset),
                playerPos.z() + Math.cos(angle) * dist);
    }

    private void triggerSound(String soundId, Vector3d pos, Store<EntityStore> store) {
        try {
            int idx = SoundEvent.getAssetMap().getIndex(soundId);
            if (idx < 0) {
                log(Level.WARNING, "[X18-GhostCave] Sound not found: " + soundId);
                return;
            }
            SoundUtil.playSoundEvent3d(idx, SoundCategory.SFX,
                    pos.x(), pos.y(), pos.z(), store);
            log(Level.INFO, "[X18-GhostCave] " + soundId + " @ "
                    + (int) pos.x() + "," + (int) pos.y() + "," + (int) pos.z());
        } catch (Exception e) {
            log(Level.WARNING, "[X18-GhostCave] Failed to play " + soundId
                    + ": " + e.getMessage());
        }
    }

    private int randomBetween(int min, int max) {
        return min + rng.nextInt((max - min) + 1);
    }

    private void log(Level level, String message) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, message);
        }
    }
}