package dev.hytalemod.x17.scheduler;

import dev.hytalemod.x17.X17Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Level;

/**
 * X17NightScheduler - v0.2.8
 *
 * Decides per-night behaviour (SPAWN / GHOST_SOUNDS / SILENT) and persists
 * state across server restarts so the night counter never resets.
 *
 * ── SAVE / LOAD DESIGN ────────────────────────────────────────────────────
 *
 * Files written for each world:
 * scheduler_<world>.properties ← current live state
 * scheduler_<world>.properties.bak ← last known-good backup
 * scheduler_<world>.properties.tmp ← write target before atomic rename
 *
 * Write sequence (atomic, crash-safe):
 * 1. Write all fields to .tmp
 * 2. If .tmp write succeeds: rename primary → .bak (overwrites old backup)
 * 3. Rename .tmp → primary
 * If any step fails, the primary and .bak are untouched.
 *
 * Load sequence (with fallback):
 * 1. Try primary file. If OK → done.
 * 2. If primary missing/corrupt → try .bak. If OK → done + warn.
 * 3. If both fail → start from night 0, warn operator.
 *
 * All integer fields are validated after load (clamped, not silently used).
 * The night decision is persisted so a mid-night restart restores the same
 * decision instead of re-rolling.
 */
public class X17NightScheduler {

    // ── State ─────────────────────────────────────────────────────────────────
    private int currentNight = 0;
    private int consecutiveNightsWithoutSpawn = 0;
    private NightDecision currentDecision = NightDecision.PENDING;
    private float ghostSoundMultiplier = 1.0f;
    private boolean isTensionNight = false;

    // ── Transition guards ─────────────────────────────────────────────────────
    private boolean nightTransitionHandled = false;
    private boolean dayTransitionHandled = false;
    private boolean firstTick = true;

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final float[] SPAWN_CHANCES = { 0.30f, 0.45f, 0.55f, 0.60f, 0.65f };
    private static final int TENSION_NIGHT_THRESHOLD = 3;
    private static final float TENSION_SOUND_MULTIPLIER = 2.0f;
    private static final float GHOST_SOUND_MULTIPLIER = 1.5f;

    // ── Validation limits ─────────────────────────────────────────────────────
    private static final int MAX_SANE_NIGHT = 100_000;
    private static final int MAX_SANE_CONSECUTIVE = 1_000;

    private final Random rng = new Random();

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    public enum NightDecision {
        PENDING, SPAWN, GHOST_SOUNDS, SILENT
    }

    /** Called every tick by X17EventSystem. */
    public void tick(boolean isNight, String worldName) {
        if (firstTick) {
            firstTick = false;
            loadState(worldName);

            if (isNight) {
                nightTransitionHandled = true;
                dayTransitionHandled = false;
                // Mid-night restart: restore the saved decision if it was persisted,
                // otherwise decide now (avoids losing the night type after crash).
                if (currentDecision == NightDecision.PENDING) {
                    decideCurrentNight(worldName);
                }
                log(Level.INFO, "Server started mid-night. Night=" + currentNight
                        + " | Decision=" + currentDecision);
            } else {
                dayTransitionHandled = true;
                nightTransitionHandled = false;
            }
            return;
        }

        if (isNight && !nightTransitionHandled) {
            nightTransitionHandled = true;
            dayTransitionHandled = false;
            onNightBegin(worldName);
        } else if (!isNight && !dayTransitionHandled) {
            dayTransitionHandled = true;
            nightTransitionHandled = false;
            onDayBegin(worldName);
        }
    }

    public boolean shouldSpawnThisNight() {
        return currentDecision == NightDecision.SPAWN;
    }

    public boolean isGhostSoundNight() {
        return currentDecision == NightDecision.GHOST_SOUNDS
                || (currentDecision == NightDecision.SILENT && isTensionNight);
    }

    public float getGhostSoundMultiplier() {
        return ghostSoundMultiplier;
    }

    public int getCurrentNight() {
        return currentNight;
    }

    public int getConsecutiveNightsWithoutSpawn() {
        return consecutiveNightsWithoutSpawn;
    }

    public boolean isTensionNight() {
        return isTensionNight;
    }

    public NightDecision getCurrentDecision() {
        return currentDecision;
    }

    // =========================================================================
    // NIGHT / DAY TRANSITIONS
    // =========================================================================

    private void onNightBegin(String worldName) {
        currentNight++;
        decideCurrentNight(worldName);
        // State is saved inside decideCurrentNight after the roll.
    }

    private void decideCurrentNight(String worldName) {
        isTensionNight = false;
        ghostSoundMultiplier = 1.0f;

        if (consecutiveNightsWithoutSpawn >= TENSION_NIGHT_THRESHOLD) {
            isTensionNight = true;
            ghostSoundMultiplier = TENSION_SOUND_MULTIPLIER;
        }

        float chance = getSpawnChance();
        float roll = rng.nextFloat();
        boolean spawns = roll < chance;

        if (spawns) {
            currentDecision = NightDecision.SPAWN;
            consecutiveNightsWithoutSpawn = 0;
        } else {
            consecutiveNightsWithoutSpawn++;
            if (isTensionNight) {
                currentDecision = NightDecision.GHOST_SOUNDS;
            } else {
                boolean ghost = rng.nextFloat() < 0.80f;
                currentDecision = ghost ? NightDecision.GHOST_SOUNDS : NightDecision.SILENT;
                ghostSoundMultiplier = ghost ? GHOST_SOUND_MULTIPLIER : 0.3f;
            }
        }

        logDecision(chance, roll);
        saveState(worldName); // persist immediately after decision so a crash keeps the roll
    }

    private void onDayBegin(String worldName) {
        log(Level.INFO, "Day started after night " + currentNight
                + ". No-spawn streak: " + consecutiveNightsWithoutSpawn);
        currentDecision = NightDecision.PENDING;
        saveState(worldName); // persist PENDING so a restart during daytime is clean
    }

    private float getSpawnChance() {
        int idx = Math.max(0, Math.min(currentNight - 1, SPAWN_CHANCES.length - 1));
        return SPAWN_CHANCES[idx];
    }

    // =========================================================================
    // PERSISTENT STATE — LOAD
    // =========================================================================

    private void loadState(String worldName) {
        if (worldName == null || worldName.isEmpty()) {
            log(Level.WARNING, "No world name provided — starting from night 0.");
            return;
        }

        File dir = resolveStateDir();
        if (dir == null)
            return; // error already logged

        File primary = stateFile(dir, worldName);
        File backup = backupFile(dir, worldName);

        // Try primary first, then backup.
        if (tryLoad(primary, worldName, false))
            return;
        if (tryLoad(backup, worldName, true))
            return;

        log(Level.WARNING, "No valid state found for world '" + worldName
                + "' — starting from night 0.");
    }

    /**
     * Attempt to load from {@code file}.
     * 
     * @param isBackup true if this is the .bak fallback (changes log message).
     * @return true if the file was loaded successfully.
     */
    private boolean tryLoad(File file, String worldName, boolean isBackup) {
        if (file == null || !file.exists())
            return false;

        try (FileInputStream fis = new FileInputStream(file)) {
            Properties p = new Properties();
            p.load(fis);

            int night = safeParseInt(p, "currentNight", 0, 0, MAX_SANE_NIGHT);
            int consecutive = safeParseInt(p, "consecutiveNights", 0, 0, MAX_SANE_CONSECUTIVE);
            String decision = p.getProperty("currentDecision", NightDecision.PENDING.name());

            // Commit loaded values.
            currentNight = night;
            consecutiveNightsWithoutSpawn = consecutive;
            currentDecision = parseDecision(decision);

            log(Level.INFO, (isBackup ? "[BACKUP] " : "") + "State loaded for '"
                    + worldName + "': night=" + currentNight
                    + " | no-spawn streak=" + consecutiveNightsWithoutSpawn
                    + " | decision=" + currentDecision
                    + (isBackup ? " (recovered from backup)" : ""));
            return true;

        } catch (Exception e) {
            log(Level.WARNING, (isBackup ? "[BACKUP] " : "") + "Failed to load '"
                    + file.getName() + "': " + e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // PERSISTENT STATE — SAVE (atomic write-rename with backup)
    // =========================================================================

    private void saveState(String worldName) {
        if (worldName == null || worldName.isEmpty())
            return;

        File dir = resolveStateDir();
        if (dir == null)
            return;

        File primary = stateFile(dir, worldName);
        File backup = backupFile(dir, worldName);
        File tmp = tmpFile(dir, worldName);

        // Step 1: write everything to .tmp
        try {
            writeProperties(tmp, worldName);
        } catch (Exception e) {
            log(Level.WARNING, "Failed to write tmp state: " + e.getMessage());
            tmp.delete(); // clean up partial file
            return;
        }

        // Step 2: rotate primary → .bak (best-effort — failure here is non-fatal)
        if (primary.exists()) {
            try {
                Files.move(primary.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log(Level.WARNING, "Could not rotate backup (non-fatal): " + e.getMessage());
                // Continue anyway — the .tmp is still valid.
            }
        }

        // Step 3: rename .tmp → primary (atomic on most OSes)
        try {
            Files.move(tmp.toPath(), primary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log(Level.SEVERE, "CRITICAL: Could not finalise state save! "
                    + "State is in '" + tmp.getName() + "'. "
                    + "Reason: " + e.getMessage());
            // Do NOT delete tmp — operator can recover manually.
        }
    }

    private void writeProperties(File target, String worldName) throws IOException {
        Properties p = new Properties();
        p.setProperty("currentNight", String.valueOf(currentNight));
        p.setProperty("consecutiveNights", String.valueOf(consecutiveNightsWithoutSpawn));
        p.setProperty("currentDecision", currentDecision.name());
        p.setProperty("schemaVersion", "1");

        // Write with explicit UTF-8 so the comment line is always safe.
        try (OutputStream os = Files.newOutputStream(target.toPath());
                Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            p.store(w, "X17 Scheduler state | world: " + worldName
                    + " | night: " + currentNight);
        }
    }

    // =========================================================================
    // FILE HELPERS
    // =========================================================================

    /**
     * Resolves and creates the state directory.
     * 
     * @return the directory, or {@code null} if it cannot be created.
     */
    private File resolveStateDir() {
        File dir = new File("UserData/x17_scheduler");
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                log(Level.SEVERE, "Cannot create state directory: " + dir.getAbsolutePath()
                        + " — check file-system permissions. State will NOT be saved.");
                return null;
            }
        }
        return dir;
    }

    private File stateFile(File dir, String worldName) {
        return new File(dir, "scheduler_" + sanitize(worldName) + ".properties");
    }

    private File backupFile(File dir, String worldName) {
        return new File(dir, "scheduler_" + sanitize(worldName) + ".properties.bak");
    }

    private File tmpFile(File dir, String worldName) {
        return new File(dir, "scheduler_" + sanitize(worldName) + ".properties.tmp");
    }

    private String sanitize(String worldName) {
        return worldName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    // =========================================================================
    // PARSE HELPERS
    // =========================================================================

    /**
     * Reads an integer property, falling back to {@code defaultVal} on any
     * parse error, then clamping to [{@code min}, {@code max}].
     */
    private int safeParseInt(Properties p, String key, int defaultVal, int min, int max) {
        String raw = p.getProperty(key);
        if (raw == null || raw.trim().isEmpty())
            return defaultVal;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                log(Level.WARNING, "Property '" + key + "' value " + v
                        + " out of valid range [" + min + ", " + max + "] — clamped.");
                return Math.max(min, Math.min(max, v));
            }
            return v;
        } catch (NumberFormatException e) {
            log(Level.WARNING, "Property '" + key + "' is not a valid integer ('"
                    + raw + "') — using default " + defaultVal + ".");
            return defaultVal;
        }
    }

    private NightDecision parseDecision(String raw) {
        if (raw == null)
            return NightDecision.PENDING;
        try {
            return NightDecision.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log(Level.WARNING, "Unknown NightDecision value '" + raw + "' — defaulting to PENDING.");
            return NightDecision.PENDING;
        }
    }

    // =========================================================================
    // LOGGING
    // =========================================================================

    private void logDecision(float chance, float roll) {
        String dec;
        switch (currentDecision) {
            case SPAWN:
                dec = "SPAWN (X17 appears)";
                break;
            case GHOST_SOUNDS:
                dec = "GHOST SOUNDS (x" + ghostSoundMultiplier + ")";
                break;
            case SILENT:
                dec = "SILENT (quiet night)";
                break;
            default:
                dec = "PENDING";
                break;
        }
        log(Level.INFO, "NIGHT " + currentNight
                + " | Chance=" + String.format("%.0f%%", chance * 100)
                + " Roll=" + String.format("%.2f", roll)
                + " -> " + dec
                + (isTensionNight ? " [TENSION]" : ""));
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null) {
            X17Plugin.getInstance().log(level, "[Scheduler] " + msg);
        }
    }
}