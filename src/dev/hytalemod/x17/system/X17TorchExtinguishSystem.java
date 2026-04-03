package dev.hytalemod.x17.system;

import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import dev.hytalemod.x17.X17Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;

/**
 * X17TorchExtinguishSystem - v0.2.9
 *
 * Extinguishes all torch-type blocks within a configurable AOE radius around a
 * given world position. Triggered by X17AISystem whenever X17 enters its first
 * STALK observation for a given night (the "stalk" moment the player should
 * feel X17's presence through the dying light).
 *
 * BLOCK API CHAIN (safe, no silent-fail):
 * ChunkUtil.indexChunkFromBlock(x, z)
 * → world.getNonTickingChunk(index) → BlockAccessor
 * → accessor.getBlockType(x, y, z) → BlockType (read)
 * → accessor.setBlockInteractionState(x, y, z, blockType, "Off", false)
 *
 * Calling world.getBlockType / world.setBlock directly compiles but silently
 * fails at runtime — always go through the chunk accessor.
 *
 * TORCH BLOCK IDs (Hytale naming):
 * All known torch variants that carry an interaction state "Off" / "On"
 * are stored in TORCH_BLOCK_IDS. IDs are matched against the normalised
 * suffix (after the last ':'), lower-cased, with any leading '*' stripped —
 * the same normalisation used across the rest of the mod.
 *
 * AOE:
 * Default radius is 20 blocks (manhattan-style cube sweep for performance).
 * The vertical scan is kept narrow (-2 … +4 relative to the centre Y) to
 * avoid wasting time on underground or sky blocks.
 */
public class X17TorchExtinguishSystem {

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Horizontal radius (blocks) around the target position to scan. */
    private static final int AOE_RADIUS = 35;

    /** Vertical scan range below the target Y. */
    private static final int SCAN_Y_DOWN = 15;

    /** Vertical scan range above the target Y. */
    private static final int SCAN_Y_UP = 25;

    /**
     * Interaction-state string that represents "extinguished" for torch-type
     * blocks in Hytale's block state system.
     */
    private static final String STATE_OFF = "Off";

    // ── Known torch block IDs ─────────────────────────────────────────────────

    /**
     * Normalised block ID suffixes for every torch / light source that
     * supports an "Off" interaction state. Extend this list as new torch
     * variants are discovered in the game data.
     *
     * All entries are lower-case; matching is done after the same
     * normalizeBlockId() transform used elsewhere in the mod.
     */
    private static final ArrayList<String> TORCH_BLOCK_IDS = new ArrayList<>(Arrays.asList(
            // ── Standard torches ──────────────────────────────────────────────
            "torch",
            "wall_torch",
            "torch_on_wall",
            // ── Coloured / variant torches ────────────────────────────────────
            "torch_blue",
            "torch_green",
            "torch_purple",
            "torch_red",
            "torch_white",
            "torch_yellow",
            "wall_torch_blue",
            "wall_torch_green",
            "wall_torch_purple",
            "wall_torch_red",
            "wall_torch_white",
            "wall_torch_yellow",
            // ── Campfires & lanterns (also carry On/Off state) ────────────────
            "campfire",
            "campfire_lit",
            "lantern",
            "lantern_hanging",
            "lantern_standing",
            // ── Sconces / wall sconces ────────────────────────────────────────
            "sconce",
            "wall_sconce",
            "sconce_lit",
            // ── Candles ───────────────────────────────────────────────────────
            "candle",
            "candle_lit",
            "candle_wall",
            // ── Generic fallback prefix (any block whose id contains "torch") ─
            // (handled separately in isTorchBlock)
            "__prefix_torch__" // sentinel — do not remove; drives prefix check
    ));

    // ── Singleton / constructor ───────────────────────────────────────────────

    public X17TorchExtinguishSystem() {
        // No per-instance state needed; all work is done in extinguishTorchesAround.
    }

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    /**
     * Scans a cube of radius {@link #AOE_RADIUS} around {@code centre} and
     * sets every recognised torch block to its "Off" interaction state.
     *
     * <p>
     * Called by {@link X17AISystem} when X17 first enters STALK for the
     * current night, or on a targeted scare event.
     * </p>
     *
     * @param world  the world in which to operate — must not be {@code null}
     * @param centre world-space position to scan around (typically the player
     *               position at the moment X17 appears)
     */
    public void extinguishTorchesAround(World world, Vector3d centre) {
        if (world == null || centre == null) {
            log(Level.WARNING, "[Torch] extinguishTorchesAround called with null arg — skipped.");
            return;
        }

        int cx = (int) Math.floor(centre.getX());
        int cy = (int) Math.floor(centre.getY());
        int cz = (int) Math.floor(centre.getZ());

        int extinguished = 0;

        for (int x = -AOE_RADIUS; x <= AOE_RADIUS; x++) {
            for (int z = -AOE_RADIUS; z <= AOE_RADIUS; z++) {
                for (int y = -SCAN_Y_DOWN; y <= SCAN_Y_UP; y++) {
                    int bx = cx + x;
                    int by = cy + y;
                    int bz = cz + z;

                    try {
                        BlockType bt = world.getBlockType(bx, by, bz);
                        if (bt == null)
                            continue;

                        String normId = normalizeBlockId(bt.getId());
                        if (!isTorchBlock(normId))
                            continue;

                        // Toggle the block to its "Off" interaction state.
                        world.setBlockInteractionState(new com.hypixel.hytale.math.vector.Vector3i(bx, by, bz), bt,
                                STATE_OFF);
                        extinguished++;

                    } catch (Exception e) {
                        log(Level.INFO, "[Torch] setBlockInteractionState failed at ("
                                + bx + "," + by + "," + bz + "): " + e.getMessage());
                    }
                }
            }
        }

        log(Level.INFO, "[Torch] Extinguished " + extinguished
                + " torch(es) around " + formatPos(centre)
                + " (r=" + AOE_RADIUS + ").");
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    // getAccessor removed.

    /**
     * Returns {@code true} if the normalised block ID string represents a
     * torch or torch-like light source that carries an On/Off interaction
     * state.
     *
     * <p>
     * Matching strategy (in order):
     * <ol>
     * <li>Exact match against {@link #TORCH_BLOCK_IDS} (excluding the
     * sentinel entry).</li>
     * <li>Prefix/contains check: any id that <em>contains</em> the substring
     * {@code "torch"} is treated as a torch variant to future-proof
     * against new Hytale content updates.</li>
     * </ol>
     * </p>
     */
    private boolean isTorchBlock(String normId) {
        if (normId == null || normId.isEmpty())
            return false;
        // Contains-"torch" fast path covers all current and future torch
        // variants without maintaining an exhaustive list.
        if (normId.contains("torch"))
            return true;
        // Check the rest of the explicit list (campfires, lanterns, sconces…).
        for (String id : TORCH_BLOCK_IDS) {
            if (id.startsWith("__"))
                continue; // skip sentinels
            if (normId.equals(id))
                return true;
        }
        return false;
    }

    /**
     * Strips the namespace prefix and leading wildcard from a raw block ID,
     * then lower-cases the result. Matches the same transform used throughout
     * the mod (e.g. in {@code X17AISystem.normalizeBlockId}).
     *
     * <p>
     * Examples:
     * <ul>
     * <li>{@code "zone:torch"} → {@code "torch"}</li>
     * <li>{@code "*torch_blue"} → {@code "torch_blue"}</li>
     * <li>{@code "Empty"} → {@code "empty"}</li>
     * </ul>
     * </p>
     */
    private String normalizeBlockId(String raw) {
        if (raw == null)
            return "";
        String id = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
        if (id.startsWith("*"))
            id = id.substring(1);
        return id.toLowerCase();
    }

    private String formatPos(Vector3d pos) {
        return String.format("(%.1f, %.1f, %.1f)", pos.getX(), pos.getY(), pos.getZ());
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, "[X17-Torch] " + msg);
        }
    }
}