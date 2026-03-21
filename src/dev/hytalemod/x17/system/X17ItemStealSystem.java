package dev.hytalemod.x17.system;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import dev.hytalemod.x17.X17Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * X17ItemStealSystem - v0.2.5
 *
 * Silently removes one priority item from a nearby chest.
 * No drops, no visual feedback, no intermediary — the item simply vanishes.
 * The player will notice their loot is gone only when they open the chest.
 *
 * CONFIRMED API (server.zip)
 * ItemStack.getItemId() → String (public)
 * ItemStack.isEmpty() → boolean (public)
 * SimpleItemContainer.getCapacity() → short (public)
 * SimpleItemContainer.getItemStack(short) → ItemStack (public)
 * SimpleItemContainer.internal_removeSlot(short) → ItemStack (protected,
 * setAccessible)
 *
 * Returns true if a theft was successfully committed (used by AISystem to set
 * stealDoneThisNight).
 */
public class X17ItemStealSystem {

    private static final int SCAN_RADIUS = 50;
    private static final int SCAN_Y_DOWN = 15;
    private static final int SCAN_Y_UP = 25;
    private static final double PLAYER_SAFE_DIST_SQ = 25.0; // 5 blocks²

    // Priority substrings matched against lowercased itemId.
    // Hytale IDs are PascalCase: "Weapon_Daggers_Thorium", "Armor_Bronze_Chest",
    // etc.
    private static final List<String> PRIORITY_SUBSTRINGS = Arrays.asList(
            "weapon_dagger", "weapon_axe", "weapon_sword", "weapon_longsword",
            "weapon_battleaxe", "weapon_mace", "weapon_spear", "weapon_hammer",
            "weapon_claws", "weapon_club", "weapon_kunai", "weapon_staff",
            "weapon_bow", "weapon_shortbow", "weapon_crossbow", "weapon_wand",
            "weapon_blowgun", "weapon_shield",
            "armor_", "armour_",
            "food_", "consumable_",
            "torch", "lantern", "candle");

    private static final String CLS_ITEM_CONTAINER_STATE = "com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState";

    // Reflection cache
    private Class<?> itemContainerStateClass = null;
    private Method getStateMethod = null;
    private Field itemContainerField = null;
    private Method getCapacityMethod = null;
    private Method getItemStackMethod = null;
    private Method removeSlotMethod = null;
    private Method getItemIdMethod = null;
    private Method isEmptyMethod = null;
    private boolean reflectionReady = false;
    private boolean reflectionFailed = false;

    public X17ItemStealSystem() {
    }

    // =========================================================================
    // PUBLIC API — returns true if an item was stolen
    // =========================================================================

    public boolean attemptTheft(World world, Vector3d playerPos) {
        if (world == null || playerPos == null)
            return false;
        if (!ensureReflection())
            return false;

        int cx = (int) Math.floor(playerPos.getX());
        int cy = (int) Math.floor(playerPos.getY());
        int cz = (int) Math.floor(playerPos.getZ());

        for (int x = cx - SCAN_RADIUS; x <= cx + SCAN_RADIUS; x++) {
            for (int z = cz - SCAN_RADIUS; z <= cz + SCAN_RADIUS; z++) {
                for (int y = cy - SCAN_Y_DOWN; y <= cy + SCAN_Y_UP; y++) {
                    try {
                        // ── 1. Block type check ───────────────────────────────
                        BlockType bt = world.getBlockType(x, y, z);
                        if (bt == null)
                            continue;
                        if (!normalizeBlockId(bt.getId()).contains("chest"))
                            continue;

                        // ── 2. Distance gate ──────────────────────────────────
                        double dx = playerPos.getX() - x, dy = playerPos.getY() - y, dz = playerPos.getZ() - z;
                        if ((dx * dx + dy * dy + dz * dz) <= PLAYER_SAFE_DIST_SQ)
                            continue;

                        // ── 3. BlockAccessor ──────────────────────────────────
                        BlockAccessor accessor = getAccessor(world, x, z);
                        if (accessor == null)
                            continue;

                        // ── 4. ItemContainerState via getState(III) ───────────
                        if (getStateMethod == null) {
                            getStateMethod = accessor.getClass().getMethod(
                                    "getState", int.class, int.class, int.class);
                            getStateMethod.setAccessible(true);
                        }
                        Object state = getStateMethod.invoke(accessor, x, y, z);
                        if (state == null || !itemContainerStateClass.isInstance(state))
                            continue;

                        // ── 5. SimpleItemContainer ────────────────────────────
                        Object container = itemContainerField.get(state);
                        if (container == null)
                            continue;

                        short capacity = (Short) getCapacityMethod.invoke(container);
                        if (capacity <= 0)
                            continue;

                        // ── 6. Find best item (priority → fallback) ───────────
                        short targetSlot = -1;
                        String targetId = null;

                        outer: for (String sub : PRIORITY_SUBSTRINGS) {
                            for (short s = 0; s < capacity; s++) {
                                Object stack = getItemStackMethod.invoke(container, s);
                                if (stack == null || (Boolean) isEmptyMethod.invoke(stack))
                                    continue;
                                String id = (String) getItemIdMethod.invoke(stack);
                                if (id != null && id.toLowerCase().contains(sub)) {
                                    targetSlot = s;
                                    targetId = id;
                                    break outer;
                                }
                            }
                        }
                        // Fallback: first non-empty slot
                        if (targetSlot < 0) {
                            for (short s = 0; s < capacity; s++) {
                                Object stack = getItemStackMethod.invoke(container, s);
                                if (stack != null && !(Boolean) isEmptyMethod.invoke(stack)) {
                                    String id = (String) getItemIdMethod.invoke(stack);
                                    if (id != null && !id.isEmpty()) {
                                        targetSlot = s;
                                        targetId = id;
                                        break;
                                    }
                                }
                            }
                        }
                        if (targetSlot < 0)
                            continue; // chest is empty

                        // ── 7. Silently remove from chest — no drop, no visual ─
                        removeSlotMethod.invoke(container, targetSlot);

                        log(Level.INFO, "[Steal] Silently pilfered '" + targetId
                                + "' from chest at (" + x + "," + y + "," + z + ").");
                        return true; // theft committed

                    } catch (Exception e) {
                        log(Level.WARNING, "[Steal] Exception: " + e.getMessage());
                    }
                }
            }
        }
        log(Level.INFO, "[Steal] No accessible chests with loot found.");
        return false;
    }

    // =========================================================================
    // REFLECTION
    // =========================================================================

    private boolean ensureReflection() {
        if (reflectionReady)
            return true;
        if (reflectionFailed)
            return false;
        try {
            itemContainerStateClass = Class.forName(CLS_ITEM_CONTAINER_STATE);

            itemContainerField = itemContainerStateClass.getDeclaredField("itemContainer");
            itemContainerField.setAccessible(true);
            Class<?> simpleContainerClass = itemContainerField.getType();

            getCapacityMethod = simpleContainerClass.getMethod("getCapacity");
            getItemStackMethod = simpleContainerClass.getMethod("getItemStack", short.class);

            removeSlotMethod = simpleContainerClass.getDeclaredMethod(
                    "internal_removeSlot", short.class);
            removeSlotMethod.setAccessible(true);

            Class<?> itemStackClass = Class.forName(
                    "com.hypixel.hytale.server.core.inventory.ItemStack");
            getItemIdMethod = itemStackClass.getMethod("getItemId");
            isEmptyMethod = itemStackClass.getMethod("isEmpty");

            reflectionReady = true;
            log(Level.INFO, "[Steal] Reflection resolved — silent theft ready.");
            return true;
        } catch (Exception e) {
            reflectionFailed = true;
            log(Level.WARNING, "[Steal] Reflection failed: " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private BlockAccessor getAccessor(World world, int x, int z) {
        try {
            return world.getNonTickingChunk(ChunkUtil.indexChunkFromBlock(x, z));
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeBlockId(String raw) {
        if (raw == null)
            return "";
        String id = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
        if (id.startsWith("*"))
            id = id.substring(1);
        return id.toLowerCase();
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null)
            X17Plugin.getInstance().log(level, "[X17-Steal] " + msg);
    }
}