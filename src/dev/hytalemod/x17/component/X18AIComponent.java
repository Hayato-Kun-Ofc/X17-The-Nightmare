package dev.hytalemod.x17.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * X18AIComponent — v0.3.5
 *
 * Runtime state for the X_18 cave stalker. All timing constants live here so
 * X18AISystem is free of magic numbers.
 *
 * TIMING DESIGN
 * ─────────────
 * After each appearance the entity hides underground and waits
 * POST_APPEARANCE_COOLDOWN ticks before trying to reposition again.
 * This creates the "suspense gap" the player feels between sightings.
 *
 * A separate SEARCH_RETRY_COOLDOWN is used when the position search
 * fails so the system retries quickly without blocking the longer gap.
 *
 * The grab/charge events happen via the deep cave dwell system (40s below
 * Y=60) and are locked out for the rest of the current night. The standard
 * CHARGING state can also trigger from STALK with a 25% chance (once per
 * night). attackUsedThisSession resets on each night transition.
 *
 * v0.3.5 CHANGES (bug fixes)
 * ──────────────────────────
 * - DEEP_CAVE_Y_THRESHOLD raised from 40 to 60 (reachable by normal caves)
 * - DEEP_CAVE_DWELL_REQUIRED reduced from 2000 to 800 ticks (40s vs 100s)
 * - DEEP_CAVE_GRAB_PCT raised from 30 to 50 (equal 50/50 split)
 * - Removed incorrect HIDDEN block that prevented stalk while dwell accumulates
 * - attackUsedThisSession now resets on night transition (once-per-night
 * charge)
 * - Normal STALK charge chance raised from 5% to 25%
 */
public class X18AIComponent implements Component<EntityStore> {

    // ── X18 State Machine ────────────────────────────────────────────────────
    public enum X18State {
        HIDDEN,
        STALKING,
        CHARGING,
        VANISHING,
        LURKING,
        DEEP_CAVE_CHARGING,
        DEEP_CAVE_GRABBING,
        DEEP_CAVE_BLACKOUT;

        public static X18State fromId(int id) {
            X18State[] vals = values();
            return (id >= 0 && id < vals.length) ? vals[id] : HIDDEN;
        }
    }

    // ── Grab sub-phases ──────────────────────────────────────────────────────
    // DEEP_CAVE_GRABBING uses a sub-phase counter to sequence:
    // 0 = approaching (ChargeAttack animation, high-speed move toward player)
    // 1 = contact made, playing Grab animation, player immobilized
    // 2 = transition to DEEP_CAVE_BLACKOUT
    //
    // DEEP_CAVE_BLACKOUT uses a sub-phase counter:
    // 0 = blackout shown, waiting for blackout duration
    // 1 = player teleported, blackout fading, about to release

    /** Duration of the blackout overlay before teleport (60 ticks = 3 s). */
    public static final int DEEP_CAVE_BLACKOUT_DURATION = 60;
    /** Duration after teleport before releasing player (40 ticks = 2 s). */
    public static final int DEEP_CAVE_BLACKOUT_FADE_DURATION = 40;
    /** Minimum teleport distance during grab event (blocks). */
    public static final int DEEP_CAVE_GRAB_TELEPORT_MIN_DIST = 30;
    /** Maximum teleport distance during grab event (blocks). */
    public static final int DEEP_CAVE_GRAB_TELEPORT_MAX_DIST = 50;

    // -------------------------------------------------------------------------
    // Timing constants (all in ticks; 20 ticks ≈ 1 second)
    // -------------------------------------------------------------------------

    /** How long X_18 is visible and staring — STALK (~16 s). */
    public static final int STALK_DURATION_TICKS = 320;

    /** How many ticks the charge lasts before auto-vanish (5 s). */
    public static final int CHARGE_DURATION_TICKS = 100;

    /**
     * Cooldown after EACH appearance before the next reposition attempt.
     * 1100 ticks = 55 s.
     */
    public static final int POST_APPEARANCE_COOLDOWN = 1100;

    /**
     * Shorter cooldown used after a charge attack so the X_18 returns with
     * a sense of aggression. 550 ticks = 27.5 s.
     */
    public static final int POST_CHARGE_COOLDOWN = 550;

    /**
     * Quick retry when the position search fails (no walkable floor found).
     * 120 ticks = 6 s.
     */
    public static final int SEARCH_RETRY_COOLDOWN = 120;

    /**
     * Initial delay after the entity first spawns into the world (pooled at
     * y = POOL_HIDE_Y). Gives the game a moment to settle before the first
     * appearance. 200 ticks = 10 s.
     */
    public static final int INITIAL_SPAWN_COOLDOWN = 200;

    /**
     * How long X_18 lurks in the cave before giving up (if never spotted).
     * 200 ticks = 10 s of silent watching before it vanishes on its own.
     */
    public static final int LURK_DURATION_TICKS = 200;

    /**
     * Maximum Y offset from the player for a valid lurk position.
     * v0.3.4: changed from 8 (deep below) to 3 (same level ± a few blocks)
     * so the lurker spawns in the same cave channels as the player.
     */
    public static final int LURK_Y_TOLERANCE = 3;

    /**
     * Horizontal distance band for lurk spawn — 10 to 18 blocks (cave corridors).
     */
    public static final double LURK_MIN_DIST = 10.0;
    public static final double LURK_MAX_DIST = 18.0;

    /**
     * Probability (out of 100) that a HIDDEN→visible transition becomes a
     * LURK instead of a normal STALK. 40% — STALK is now the dominant mode.
     */
    public static final int LURK_CHANCE_PCT = 40;

    /**
     * Ticks of eye-contact to trigger vanish during LURK — 5 s.
     * v0.3.4: increased to 100 so the player actually registers seeing it.
     */
    public static final int LURK_EXPOSURE_TICKS = 100;

    /**
     * Ticks of eye-contact to trigger vanish during STALK — 5 s.
     * v0.3.4: increased to 100 so the player gets a brief glimpse.
     */
    public static final int STALK_EXPOSURE_TICKS = 100;

    /**
     * Stillness detection: X_18 only appears when the player hasn't moved
     * more than STILLNESS_THRESHOLD blocks/tick for STILLNESS_TICKS_REQUIRED
     * consecutive ticks (5 s of standing mostly still).
     *
     * v0.3.4: relaxed thresholds so X_18 can re-appear even when the player
     * is slowly moving (e.g. looking around after a charge attack).
     */
    public static final double STILLNESS_THRESHOLD = 0.10;
    public static final int STILLNESS_TICKS_REQUIRED = 100;

    // ── Cave detection hysteresis ────────────────────────────────────────────
    public enum CaveState {
        OUTSIDE,
        INSIDE
    }

    // Player "enters" cave at y ≤ CAVE_ENTER_Y, "exits" at y > CAVE_EXIT_Y.
    // The 10-block gap prevents false session resets on ramps/transitions.
    public static final double CAVE_ENTER_Y = 90.0;
    public static final double CAVE_EXIT_Y = 99.0;

    // ── Deep Cave Event constants ────────────────────────────────────────────
    // Triggered when the player is below DEEP_CAVE_Y_THRESHOLD (absolute Y)
    // for DEEP_CAVE_DWELL_REQUIRED ticks.
    //
    // Two rolls:
    // 50% → guaranteed ChargeAttack (DEEP_CAVE_CHARGING)
    // 50% → instant approach + Grab animation (DEEP_CAVE_GRABBING)
    // After either event fires, the X_18 shuts down until the next night.

    /**
     * Absolute Y threshold — player must be below this Y to qualify.
     * v0.3.5: raised from 40 to 60 so normal cave exploration triggers
     * the event. Y=40 was unreachably deep for most cave systems.
     */
    public static final double DEEP_CAVE_Y_THRESHOLD = 60.0;

    /**
     * Ticks the player must dwell below DEEP_CAVE_Y_THRESHOLD.
     * v0.3.5: reduced from 2000 (100s) to 800 (40s). The original value
     * was so long that any brief movement above threshold would reset the
     * counter before the event ever fired.
     */
    public static final int DEEP_CAVE_DWELL_REQUIRED = 800;

    /**
     * Probability (0–100) that the triggered event is a Grab instead of Charge.
     * 50% Grab, 50% Charge.
     * v0.3.5: raised from 30 to 50 to give Grab equal weight.
     */
    public static final int DEEP_CAVE_GRAB_PCT = 50;

    /** Speed of the instant Grab approach (blocks/tick). */
    public static final double DEEP_CAVE_GRAB_SPEED = 1.2;
    /** Duration of the Grab hold animation (100 ticks = 5 s). */
    public static final int DEEP_CAVE_GRAB_HOLD_TICKS = 30;
    /** Duration of the deep-cave charge (same as normal charge). */
    public static final int DEEP_CAVE_CHARGE_DURATION = CHARGE_DURATION_TICKS;
    /**
     * Minimum cave-quality blocks required in scanner radius to confirm deep cave.
     */
    public static final int DEEP_CAVE_BLOCK_THRESHOLD = 12;

    // -------------------------------------------------------------------------
    // Component type (injected at plugin init)
    // -------------------------------------------------------------------------

    private static ComponentType<EntityStore, X18AIComponent> COMPONENT_TYPE;

    public static void init(ComponentType<EntityStore, X18AIComponent> type) {
        COMPONENT_TYPE = type;
    }

    public static ComponentType<EntityStore, X18AIComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    // -------------------------------------------------------------------------
    // Codec — persists runtime state across ticks
    // -------------------------------------------------------------------------

    public static final BuilderCodec<X18AIComponent> CODEC = BuilderCodec
            .builder(X18AIComponent.class, X18AIComponent::new)
            .append(new KeyedCodec<>("State", Codec.INTEGER),
                    (c, v) -> c.currentState = X18State.fromId(v),
                    c -> c.currentState.ordinal())
            .add()
            .append(new KeyedCodec<>("SpawnCooldownTicks", Codec.INTEGER),
                    (c, v) -> c.spawnCooldownTicks = v,
                    c -> c.spawnCooldownTicks)
            .add()
            .append(new KeyedCodec<>("ActionTimerTicks", Codec.INTEGER),
                    (c, v) -> c.actionTimerTicks = v,
                    c -> c.actionTimerTicks)
            .add()
            .append(new KeyedCodec<>("DamageDone", Codec.BOOLEAN),
                    (c, v) -> c.damageDone = v,
                    c -> c.damageDone)
            .add()
            .append(new KeyedCodec<>("AttackUsedThisSession", Codec.BOOLEAN),
                    (c, v) -> c.attackUsedThisSession = v,
                    c -> c.attackUsedThisSession)
            .add()
            .append(new KeyedCodec<>("AppearanceCount", Codec.INTEGER),
                    (c, v) -> c.appearanceCount = v,
                    c -> c.appearanceCount)
            .add()
            .append(new KeyedCodec<>("LastKnownPlayerX", Codec.DOUBLE),
                    (c, v) -> c.lastKnownPlayerX = v,
                    c -> c.lastKnownPlayerX)
            .add()
            .append(new KeyedCodec<>("LastKnownPlayerY", Codec.DOUBLE),
                    (c, v) -> c.lastKnownPlayerY = v,
                    c -> c.lastKnownPlayerY)
            .add()
            .append(new KeyedCodec<>("LastKnownPlayerZ", Codec.DOUBLE),
                    (c, v) -> c.lastKnownPlayerZ = v,
                    c -> c.lastKnownPlayerZ)
            .add()
            .append(new KeyedCodec<>("IgnoreStillnessOnce", Codec.BOOLEAN),
                    (c, v) -> c.ignoreStillnessOnce = v,
                    c -> c.ignoreStillnessOnce)
            .add()
            .append(new KeyedCodec<>("DeepCaveEventFiredToday", Codec.BOOLEAN),
                    (c, v) -> c.deepCaveEventFiredToday = v,
                    c -> c.deepCaveEventFiredToday)
            .add()
            .append(new KeyedCodec<>("DeepCaveDwellTicks", Codec.INTEGER),
                    (c, v) -> c.deepCaveDwellTicks = v,
                    c -> c.deepCaveDwellTicks)
            .add()
            .build();

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private X18State currentState = X18State.HIDDEN;
    /** General-purpose countdown; used as post-appearance gap AND search retry. */
    private int spawnCooldownTicks = INITIAL_SPAWN_COOLDOWN;
    /** State-specific action timer (stalk duration, charge duration). */
    private int actionTimerTicks = 0;
    private boolean damageDone = false;
    /** Set to true after a charge; prevents a second attack in the same session. */
    private boolean attackUsedThisSession = false;
    /** Total number of full appearances so far (stalk completed or interrupted). */
    private int appearanceCount = 0;
    private double lastKnownPlayerX = 0.0;
    private double lastKnownPlayerY = 0.0;
    private double lastKnownPlayerZ = 0.0;
    /**
     * Consecutive ticks the player has been below STILLNESS_THRESHOLD speed.
     * Not persisted — resets to 0 on reload (safe: player must re-earn stillness).
     */
    private int stillnessTicks = 0;
    /**
     * When true, the next HIDDEN→visible transition skips the stillness check.
     * Set after a charge attack so the X_18 is guaranteed to re-appear even if
     * the player is running in panic. Consumed (set false) on use.
     */
    private boolean ignoreStillnessOnce = false;

    /**
     * True after the deep-cave event (charge or grab) has fired this day.
     * Prevents the event from re-triggering until the next day cycle.
     * Reset by resetDailyEvent() which should be called on day transition.
     */
    private boolean deepCaveEventFiredToday = false;

    /**
     * Accumulated ticks the player has been in the deep-cave zone (depth 20–40)
     * with valid cave blocks nearby. Resets when the player leaves the zone.
     */
    private int deepCaveDwellTicks = 0;

    /**
     * Sub-phase counter for DEEP_CAVE_GRABBING and DEEP_CAVE_BLACKOUT states.
     * Not persisted — transient event state that resets on reload.
     */
    private int grabSubPhase = 0;

    /** Saved position where the grab event started (for teleport reference). */
    private double grabOriginX = 0.0;
    private double grabOriginY = 0.0;
    private double grabOriginZ = 0.0;

    /** Saved position where the X_18 stands during the grab hold. */
    private double grabStandX = 0.0;
    private double grabStandY = 0.0;
    private double grabStandZ = 0.0;

    /**
     * Ticks remaining before the black screen UI auto-closes.
     * Transient — not persisted. Set after grab event completes.
     */
    private int blackScreenCloseTicks = 0;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public X18AIComponent() {
    }

    private X18AIComponent(X18AIComponent o) {
        this.currentState = o.currentState;
        this.spawnCooldownTicks = o.spawnCooldownTicks;
        this.actionTimerTicks = o.actionTimerTicks;
        this.damageDone = o.damageDone;
        this.attackUsedThisSession = o.attackUsedThisSession;
        this.appearanceCount = o.appearanceCount;
        this.lastKnownPlayerX = o.lastKnownPlayerX;
        this.lastKnownPlayerY = o.lastKnownPlayerY;
        this.lastKnownPlayerZ = o.lastKnownPlayerZ;
        this.stillnessTicks = o.stillnessTicks;
        this.ignoreStillnessOnce = o.ignoreStillnessOnce;
        this.deepCaveEventFiredToday = o.deepCaveEventFiredToday;
        this.deepCaveDwellTicks = o.deepCaveDwellTicks;
        this.grabSubPhase = o.grabSubPhase;
        this.grabOriginX = o.grabOriginX;
        this.grabOriginY = o.grabOriginY;
        this.grabOriginZ = o.grabOriginZ;
        this.grabStandX = o.grabStandX;
        this.grabStandY = o.grabStandY;
        this.grabStandZ = o.grabStandZ;
        this.blackScreenCloseTicks = o.blackScreenCloseTicks;
    }

    // -------------------------------------------------------------------------
    // Tick helpers (called every tick by X18AISystem)
    // -------------------------------------------------------------------------

    public void decrementSpawnCooldown() {
        if (spawnCooldownTicks > 0)
            spawnCooldownTicks--;
    }

    public void decrementActionTimer() {
        if (actionTimerTicks > 0)
            actionTimerTicks--;
    }

    public int getBlackScreenCloseTicks() {
        return blackScreenCloseTicks;
    }

    public void setBlackScreenCloseTicks(int v) {
        blackScreenCloseTicks = v;
    }

    public void decrementBlackScreenClose() {
        if (blackScreenCloseTicks > 0)
            blackScreenCloseTicks--;
    }

    /**
     * Update stillness counter. Call every tick with the player's movement delta.
     */
    public void updateStillness(double moveDelta) {
        if (moveDelta < STILLNESS_THRESHOLD) {
            if (stillnessTicks < STILLNESS_TICKS_REQUIRED + 20)
                stillnessTicks++;
        } else {
            stillnessTicks = 0;
        }
    }

    /**
     * Returns true when the player has been still long enough to trigger a spawn.
     */
    public boolean isPlayerSufficientlyStill() {
        return stillnessTicks >= STILLNESS_TICKS_REQUIRED;
    }

    public int getStillnessTicks() {
        return stillnessTicks;
    }

    public void resetStillness() {
        stillnessTicks = 0;
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public X18State getCurrentState() {
        return currentState;
    }

    public void setCurrentState(X18State s) {
        currentState = s;
    }

    public int getSpawnCooldownTicks() {
        return spawnCooldownTicks;
    }

    public void setSpawnCooldownTicks(int v) {
        spawnCooldownTicks = Math.max(0, v);
    }

    public int getActionTimerTicks() {
        return actionTimerTicks;
    }

    public void setActionTimerTicks(int v) {
        actionTimerTicks = Math.max(0, v);
    }

    public boolean isDamageDone() {
        return damageDone;
    }

    public void setDamageDone(boolean v) {
        damageDone = v;
    }

    public boolean isAttackUsedThisSession() {
        return attackUsedThisSession;
    }

    public void setAttackUsedThisSession(boolean v) {
        attackUsedThisSession = v;
    }

    public int getAppearanceCount() {
        return appearanceCount;
    }

    public void incrementAppearanceCount() {
        appearanceCount++;
    }

    public double getLastKnownPlayerX() {
        return lastKnownPlayerX;
    }

    public double getLastKnownPlayerY() {
        return lastKnownPlayerY;
    }

    public double getLastKnownPlayerZ() {
        return lastKnownPlayerZ;
    }

    public void setLastKnownPlayerPos(double x, double y, double z) {
        lastKnownPlayerX = x;
        lastKnownPlayerY = y;
        lastKnownPlayerZ = z;
    }

    /**
     * Resets ALL session-scoped state when the player leaves and re-enters the
     * cave.
     * v0.3.4: now resets appearanceCount, stillnessTicks, and ignoreStillnessOnce
     * so a fresh cave entry gets the full experience from scratch.
     */
    public void resetSession() {
        attackUsedThisSession = false;
        appearanceCount = 0;
        stillnessTicks = 0;
        ignoreStillnessOnce = false;
        deepCaveDwellTicks = 0;
        deepCaveEventFiredToday = false;
    }

    // ── ignoreStillnessOnce ───────────────────────────────────────────────────
    public boolean isIgnoreStillnessOnce() {
        return ignoreStillnessOnce;
    }

    public void setIgnoreStillnessOnce(boolean v) {
        ignoreStillnessOnce = v;
    }

    // ── Deep Cave Event ──────────────────────────────────────────────────────

    public boolean isDeepCaveEventFiredToday() {
        return deepCaveEventFiredToday;
    }

    public void setDeepCaveEventFiredToday(boolean v) {
        deepCaveEventFiredToday = v;
    }

    public int getDeepCaveDwellTicks() {
        return deepCaveDwellTicks;
    }

    public void incrementDeepCaveDwell() {
        deepCaveDwellTicks++;
    }

    public void resetDeepCaveDwell() {
        deepCaveDwellTicks = 0;
    }

    /**
     * Gradually decays the deep-cave dwell counter when the player is above
     * DEEP_CAVE_Y_THRESHOLD. Loses 3 ticks per game-tick, so a brief climb
     * (ramp, terrain bump) does not wipe the accumulated dwell.
     * The player must stay above threshold for ~267 ticks (13 s) to fully
     * drain a maxed counter.
     */
    public void decayDeepCaveDwell() {
        if (deepCaveDwellTicks > 0)
            deepCaveDwellTicks = Math.max(0, deepCaveDwellTicks - 3);
    }

    /**
     * Called on day transition to reset the daily deep-cave event flag.
     * This allows the charge/grab event to trigger again the next day.
     */
    public void resetDailyEvent() {
        deepCaveEventFiredToday = false;
        deepCaveDwellTicks = 0;
    }

    // ── Grab Sub-Phase ──────────────────────────────────────────────────────

    public int getGrabSubPhase() {
        return grabSubPhase;
    }

    public void setGrabSubPhase(int v) {
        grabSubPhase = v;
    }

    public double getGrabOriginX() {
        return grabOriginX;
    }

    public double getGrabOriginY() {
        return grabOriginY;
    }

    public double getGrabOriginZ() {
        return grabOriginZ;
    }

    public void setGrabOrigin(double x, double y, double z) {
        grabOriginX = x;
        grabOriginY = y;
        grabOriginZ = z;
    }

    public double getGrabStandX() {
        return grabStandX;
    }

    public double getGrabStandY() {
        return grabStandY;
    }

    public double getGrabStandZ() {
        return grabStandZ;
    }

    public void setGrabStand(double x, double y, double z) {
        grabStandX = x;
        grabStandY = y;
        grabStandZ = z;
    }

    // ── Compatibility stub ────────────────────────────────────────────────────
    // X18CaveSpawnSystem calls setHitsTaken(0) on the initial attach.
    // The field is no longer meaningful (hits-before-vanish logic was removed),
    // but the stub keeps the spawn system compiling without modification.
    public void setHitsTaken(int v) {
        /* no-op — field removed in v0.3.4 */ }

    @Override
    public Component<EntityStore> clone() {
        return new X18AIComponent(this);
    }
}