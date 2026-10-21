package dev.hytalemod.x17.util;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * Compatibility bridge for the section-based block API introduced by the
 * current Hytale server runtime.
 */
public final class BlockAccess {
    private BlockAccess() {
    }

    public static BlockType getBlockType(World world, int x, int y, int z) {
        if (world == null) {
            return null;
        }
        try {
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> sectionRef = chunks.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            BlockSection section = chunks.getStore().getComponent(sectionRef, BlockSection.getComponentType());
            if (section == null) {
                return null;
            }
            int blockId = section.get(
                    ChunkUtil.localCoordinate(x),
                    ChunkUtil.localCoordinate(y),
                    ChunkUtil.localCoordinate(z));
            return BlockType.getAssetMap().getAsset(blockId);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean setBlockInteractionState(
            World world, int x, int y, int z, BlockType blockType, String state, boolean notify) {
        if (world == null || blockType == null) {
            return false;
        }
        try {
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> sectionRef = chunks.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return false;
            }
            BlockOperations.setBlockInteractionState(chunks, sectionRef, x, y, z, blockType, state, notify);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}