package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import dev.hytalemod.x17.X17Plugin;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * X17ItemStealSystem - v0.3.5
 *
 * Silently removes one priority item from a nearby chest. There are no drops and
 * no visual effects; the item simply disappears from the container.
 *
 * Current server API:
 * - WorldChunk.getBlockComponentHolder(x, y, z) returns a copy of the block holder.
 * - ItemContainerBlock stores the SimpleItemContainer for container blocks.
 * - SimpleItemContainer.internal_removeSlot(slot) deletes the full stack without producing output.
 * - Existing block entity refs are updated in place to avoid recreating container state.
 *
 * Returns true when an item was successfully stolen.
 */
public class X17ItemStealSystem {

    private static final int SCAN_RADIUS = 50;
    private static final int SCAN_Y_DOWN = 15;
    private static final int SCAN_Y_UP = 25;
    private static final double PLAYER_SAFE_DIST_SQ = 25.0; // 5 blocks squared.
    private Method removeSlotMethod;

    // Priority substrings matched against lowercased item IDs.
    private static final List<String> PRIORITY_SUBSTRINGS = Arrays.asList(
            "weapon_dagger", "weapon_axe", "weapon_sword", "weapon_longsword",
            "weapon_battleaxe", "weapon_mace", "weapon_spear", "weapon_hammer",
            "weapon_claws", "weapon_club", "weapon_kunai", "weapon_staff",
            "weapon_bow", "weapon_shortbow", "weapon_crossbow", "weapon_wand",
            "weapon_blowgun", "weapon_shield",
            "armor_", "armour_",
            "food_", "consumable_",
            "torch", "lantern", "candle");

    public boolean attemptTheft(World world, Vector3d playerPos) {
        if (world == null || playerPos == null) {
            return false;
        }

        int cx = (int) Math.floor(playerPos.x());
        int cy = (int) Math.floor(playerPos.y());
        int cz = (int) Math.floor(playerPos.z());

        for (int x = cx - SCAN_RADIUS; x <= cx + SCAN_RADIUS; x++) {
            for (int z = cz - SCAN_RADIUS; z <= cz + SCAN_RADIUS; z++) {
                WorldChunk chunk = getChunk(world, x, z);
                if (chunk == null) {
                    continue;
                }

                for (int y = cy - SCAN_Y_DOWN; y <= cy + SCAN_Y_UP; y++) {
                    try {
                        BlockType blockType = chunk.getBlockType(x, y, z);
                        if (blockType == null || !normalizeBlockId(blockType.getId()).contains("chest")) {
                            continue;
                        }

                        double dx = playerPos.x() - x;
                        double dy = playerPos.y() - y;
                        double dz = playerPos.z() - z;
                        if ((dx * dx + dy * dy + dz * dz) <= PLAYER_SAFE_DIST_SQ) {
                            continue;
                        }

                        ContainerTarget target = getContainer(chunk, x, y, z);
                        if (target == null || target.container.getCapacity() <= 0) {
                            continue;
                        }

                        short targetSlot = findTargetSlot(target.container);
                        if (targetSlot < 0) {
                            continue;
                        }

                        ItemStack removedStack = removeStackSilently(target.container, targetSlot);
                        if (removedStack == null || removedStack.isEmpty()) {
                            continue;
                        }
                        String targetId = removedStack.getItemId();

                        ItemStack slotAfterRemoval = target.container.getItemStack(targetSlot);
                        if (slotAfterRemoval != null && !slotAfterRemoval.isEmpty()) {
                            log(Level.WARNING, "[Steal] Failed to delete '" + targetId
                                    + "' from chest at (" + x + "," + y + "," + z + ").");
                            continue;
                        }

                        target.containerBlock.setItemContainer(target.container);
                        if (!commitContainer(chunk, target, x, y, z)) {
                            continue;
                        }

                        log(Level.INFO, "[Steal] Destroyed stolen item '" + targetId
                                + "' from chest at (" + x + "," + y + "," + z + ").");
                        return true;
                    } catch (Exception e) {
                        log(Level.WARNING, "[Steal] Exception while scanning chest at ("
                                + x + "," + y + "," + z + "): " + e.getMessage());
                    }
                }
            }
        }

        log(Level.INFO, "[Steal] No accessible chests with loot found.");
        return false;
    }

    private ContainerTarget getContainer(WorldChunk chunk, int x, int y, int z) {
        Ref<ChunkStore> ref = chunk.getBlockComponentEntity(x, y, z);
        Holder<ChunkStore> holder = chunk.getBlockComponentHolder(x, y, z);
        if (holder == null) {
            return null;
        }

        ItemContainerBlock containerBlock = holder.getComponent(ItemContainerBlock.getComponentType());
        if (containerBlock == null) {
            return null;
        }

        SimpleItemContainer container = containerBlock.getItemContainer();
        return container != null ? new ContainerTarget(ref, holder, containerBlock, container) : null;
    }

    private ItemStack removeStackSilently(SimpleItemContainer container, short slot) throws ReflectiveOperationException {
        if (removeSlotMethod == null) {
            removeSlotMethod = SimpleItemContainer.class.getDeclaredMethod("internal_removeSlot", short.class);
            removeSlotMethod.setAccessible(true);
        }
        return (ItemStack) removeSlotMethod.invoke(container, slot);
    }

    private boolean commitContainer(WorldChunk chunk, ContainerTarget target, int x, int y, int z) {
        if (target.ref != null) {
            chunk.getWorld().getChunkStore().getStore().replaceComponent(
                    target.ref, ItemContainerBlock.getComponentType(), target.containerBlock);
            return true;
        }

        target.holder.replaceComponent(ItemContainerBlock.getComponentType(), target.containerBlock);
        int blockIndex = ChunkUtil.indexBlockInColumn(x, y, z);
        chunk.getBlockComponentChunk().storeEntityHolder(blockIndex, target.holder);
        chunk.getBlockComponentChunk().markNeedsSaving();
        chunk.markNeedsSaving();
        return true;
    }

    private static final class ContainerTarget {
        private final Ref<ChunkStore> ref;
        private final Holder<ChunkStore> holder;
        private final ItemContainerBlock containerBlock;
        private final SimpleItemContainer container;

        private ContainerTarget(Ref<ChunkStore> ref, Holder<ChunkStore> holder, ItemContainerBlock containerBlock,
                SimpleItemContainer container) {
            this.ref = ref;
            this.holder = holder;
            this.containerBlock = containerBlock;
            this.container = container;
        }
    }

    private short findTargetSlot(SimpleItemContainer container) {
        short capacity = container.getCapacity();

        for (String priority : PRIORITY_SUBSTRINGS) {
            for (short slot = 0; slot < capacity; slot++) {
                ItemStack stack = container.getItemStack(slot);
                if (isMatchingStack(stack, priority)) {
                    return slot;
                }
            }
        }

        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (stack != null && !stack.isEmpty()) {
                return slot;
            }
        }

        return -1;
    }

    private boolean isMatchingStack(ItemStack stack, String priority) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String itemId = stack.getItemId();
        return itemId != null && itemId.toLowerCase(Locale.ROOT).contains(priority);
    }

    private WorldChunk getChunk(World world, int x, int z) {
        try {
            // Use loaded chunks so containers near the player/base are included.
            // getChunkIfNonTicking can miss actively ticking chunks.
            return world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeBlockId(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
        if (id.startsWith("*")) {
            id = id.substring(1);
        }
        return id.toLowerCase(Locale.ROOT);
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, "[X17-Steal] " + msg);
        }
    }
}
