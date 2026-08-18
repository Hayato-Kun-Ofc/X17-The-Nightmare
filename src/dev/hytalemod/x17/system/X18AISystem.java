package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;
import dev.hytalemod.x17.component.X18AIComponent;
import dev.hytalemod.x17.component.X18AIComponent.X18State;
import dev.hytalemod.x17.scheduler.X18CaveDayScheduler;
import dev.hytalemod.x17.ui.X18BlackScreenPage;
import org.joml.Vector3d;

import java.util.Random;
import java.util.logging.Level;

/*
 * X_18 - Cave Stalker AI - v0.3.8
 *
 * -- DESIGN REQUIREMENTS ------------------------------------------------------
 *
 *  1. POOLED ENTITY: the entity spawns once via X18CaveSpawnSystem and is
 *     NEVER despawned. "Appearing" means teleporting from underground to a
 *     valid cave position. "Vanishing" means teleporting back underground.
 *
 *  2. POSITIONING: appear behind/beside the player at 7-11 blocks, next to
 *     a solid stone/rock wall, outside their FOV. Three-tier fallback
 *     guarantees a position is always found. Lurk positions are at the same
 *     Y level (+/-3 blocks) with line-of-sight to the player.
 *
 *  3. TIMING (20 ticks = 1 second):
 *       * Stalk duration  : ~16 s (320 ticks) - player feels watched.
 *       * Lurk duration   : ~10 s (200 ticks) - distant, silent watcher.
 *       * Post-appearance gap : 120 s (2400 ticks) - suspense between sightings.
 *       * Post-charge gap : 90 s (1800 ticks) - X_18 returns aggressively.
 *       * Search retry    : 15 s (300 ticks) - retry if no floor found.
 *
 *  4. BEHAVIOUR:
 *       * While STALKING: face player directly, accumulate look-exposure
 *         (>=100 ticks / ~5 s of eye-contact). On exposure OR natural timer
 *         expiry -> 75% VANISH, 25% CHARGE (attack).
 *       * While LURKING: 10-18 blocks away at the same cave level, facing
 *         the player silently with line-of-sight. Vanishes on >=100 ticks
 *         (~5 s) of eye-contact OR timer expiry. No charge possible from LURK.
 *       * ~40% of appearances become LURK instead of STALK.
 *       * LURK only triggers if a same-level position with line-of-sight is
 *         found; falls back to normal STALK if not.
 *       * If player gets within 2.5 blocks -> vanish immediately (no attack).
 *       * CHARGE is used AT MOST ONCE per cave session. After a charge the
 *         flag resets only when the player leaves the cave and re-enters.
 *       * After a charge, ignoreStillnessOnce is set so X_18 is guaranteed
 *         to re-appear even if the player is running.
 *       * If player leaves cave during stalk/charge/lurk -> vanish immediately,
 *         reset session.
 *
 *  5. SINGLETON GUARD: only one X_18 AI runs per world. The guard is
 *     timestamp-based (not index-based) so it survives entity re-creation
 *     after server reload or player rejoin without locking forever.
 *
 *  6. CAVE DETECTION: uses hysteresis (enter at y<=85, exit at y>95) to
 *     prevent false session resets on ramps and terrain transitions.
 *
 * -----------------------------------------------------------------------------
 */
public class X18AISystem extends EntityTickingSystem<EntityStore> {

    // -- Geometry -------------------------------------------------------------
    private static final double MIN_SPAWN_DIST = 7.0;
    private static final double MAX_SPAWN_DIST = 11.0;
    private static final double POOL_HIDE_Y = 2.0;

    // v0.3.7d FIX: POOL_HIDE_Y is an ABSOLUTE Y. STALK searches scan
    // playerY +/-8 (findSpawnPosition), so in very low sections of a cave
    // X_18's actual Y when VANISHING begins can already be below 2.0. The
    // Despawn animation never moves the real transform (it's a pure visual
    // sink baked into the .blockyanim clip) - the transform only moves once,
    // at the END of the buffer, when hideUnderground()/shutdownForDay()/
    // suppressNonRealCaveDay() teleport straight to POOL_HIDE_Y. If current Y
    // was already < 2.0, that teleport goes UP relative to where the player
    // last saw it, producing a visible "pop back up, then vanish" glitch
    // instead of a clean sink. safeHideY() below clamps the destination to
    // always be at or below X_18's current Y (with margin), so the final
    // teleport can only ever go down or stay level - never up.
    private static final double HIDE_Y_MARGIN = 3.0;
    private static final double WORLD_FLOOR_SAFETY_Y = 0.5;

    // -- Night time range -----------------------------------------------------
    private static final double NIGHT_START = 0.792;
    private static final double NIGHT_END = 0.208;

    // -- Cave detection hysteresis (imported from component) ------------------
    private static final double CAVE_ENTER_Y = X18AIComponent.CAVE_ENTER_Y;
    private static final double CAVE_EXIT_Y = X18AIComponent.CAVE_EXIT_Y;

    // -- Lurk geometry (imported from component constants for readability) -----
    private static final double LURK_MIN_DIST = X18AIComponent.LURK_MIN_DIST;
    private static final double LURK_MAX_DIST = X18AIComponent.LURK_MAX_DIST;
    private static final int LURK_Y_TOLERANCE = X18AIComponent.LURK_Y_TOLERANCE;

    // -- FOV cone - "is the player looking at X_18?" -----------------------
    private static final double YAW_HALF = 0.42; // ~ 24 deg
    private static final double PITCH_HALF = 0.48; // ~ 28 deg

    /**
     * Continuous ticks of eye-contact needed to trigger vanish during STALK.
     * Imported from component: {@link X18AIComponent#STALK_EXPOSURE_TICKS}.
     */

    // -- Combat ---------------------------------------------------------------
    private static final float CHARGE_DAMAGE = 45.0f;
    private static final double DAMAGE_DIST = 1.8;
    private static final double CHARGE_SPEED = 0.60;
    private static final double PROXIMITY_VANISH_DIST = 2.5;

    /**
     * Render buffer (ticks) given to Despawn.blockyanim before X_18 is
     * actually teleported underground. Despawn.blockyanim has duration=60
     * (same as Alerted.blockyanim), and the native role AI's own Vanish
     * state waited 1.5s (30 ticks @ 20 tps) after playing a duration=60 clip
     * before removing the entity - so 30 ticks is the proven amount of time
     * this length of animation needs to actually be visible.
     * 
     */
    private static final int DESPAWN_ANIM_BUFFER_TICKS = 15;

    // -- Singleton guard -------------------------------------------------------
    // ConcurrentHashMap. The previous static fields were shared across all
    // worlds, so on a multi-world server world B's tick overwrote world A's
    // singleton lock. The map keyed by world name gives each world its own
    // lock entry. Value is the birth timestamp of the system instance that
    // owns the singleton (survives entity re-creation after server reload).
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> activeBirthByWorld = new java.util.concurrent.ConcurrentHashMap<>();

    private long myBirthTimestamp = -1L; // set on first valid tick

    // -- Per-instance state (not persisted, resets correctly on new system) --
    private final Random rng = new Random();
    private int lookExposure = 0; // for STALKING
    private int lookExposureLurk = 0; // for LURKING (separate counter)
    private boolean playerWasInCave = false;
    /**
     * Tracks whether the player has been confirmed inside a cave via hysteresis.
     * Once true, stays true until player goes above CAVE_EXIT_Y.
     * Once false, stays false until player goes below CAVE_ENTER_Y.
     */
    private boolean playerCaveConfirmed = false;
    // Stillness tracking - last known player position to compute movement delta
    private Vector3d lastPlayerPos = null;
    private int lastDayResetted = -1;
    private boolean wasNight = false;
    private X18CaveDayScheduler.CaveDayMode lastSuppressedCaveMode = null;

    // -- Black screen safety: stored refs to close the overlay without needing
    // the player to be detected as "in cave" by findNearestCavePlayer. ------
    private PlayerRef blackScreenPlayerRef = null;
    private Store<EntityStore> blackScreenStore = null;

    // =========================================================================
    // MAIN TICK
    // =========================================================================

    @Override
    public void tick(float deltaTime, int index, ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        try {
            X18AIComponent ai = chunk.getComponent(index, X18AIComponent.getComponentType());
            TransformComponent x18tf = chunk.getComponent(index, TransformComponent.getComponentType());
            Ref<EntityStore> x18Ref = chunk.getReferenceTo(index);

            if (ai == null || x18tf == null || x18Ref == null || !x18Ref.isValid())
                return;

            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null)
                return;
            World world = es.getWorld();

            if (!acquireSingleton(world))
                return;

            // Decrement timers every tick regardless of state.
            // NOTE: DEEP_CAVE_GRABBING and DEEP_CAVE_BLACKOUT manage actionTimerTicks
            // internally inside their dispatch methods. Decrementing here too would
            // cause an off-by-one that shortens each sub-phase by 1 tick and can
            // make the safety-timer fire before the normal fade path, leaving the
            // screen open if the normal path also fails. Skip for those two states.
            ai.decrementSpawnCooldown();
            X18State curStateForDecrement = ai.getCurrentState();
            if (curStateForDecrement != X18State.DEEP_CAVE_GRABBING
                    && curStateForDecrement != X18State.DEEP_CAVE_BLACKOUT) {
                ai.decrementActionTimer();
            }

            // -- Day/Night boundary check for daily event reset ---------------------
            try {
                WorldTimeResource tr = store.getResource(WorldTimeResource.getResourceType());
                if (tr != null) {
                    if (tr.getGameDateTime() != null) {
                        int currentDay = tr.getGameDateTime().getYear() * 365 + tr.getGameDateTime().getDayOfYear();
                        if (lastDayResetted != -1 && currentDay != lastDayResetted) {
                            ai.resetDailyEvent();
                            ai.setSpawnCooldownTicks(0);
                            log(Level.INFO, "[AI] Day transition detected (" + lastDayResetted + " -> " + currentDay
                                    + "). Resetting daily event.");
                        }
                        lastDayResetted = currentDay;
                    }

                    boolean isNight = tr.isDayTimeWithinRange(NIGHT_START, NIGHT_END);
                    if (isNight && !wasNight) {
                        ai.resetDailyEvent();
                        ai.setAttackUsedThisSession(false); // v0.3.5: allow charge once per night
                        ai.setSpawnCooldownTicks(0);
                        log(Level.INFO, "[AI] Night transition detected. Resetting daily event + attack flag.");
                    }
                    wasNight = isNight;
                }
            } catch (Exception e) {
                // Ignore time check failures
            }

            X18CaveDayScheduler.CaveDayMode caveDayMode = X18CaveDayScheduler.getMode(world, store);
            boolean blackoutActive = ai.getCurrentState() == X18State.DEEP_CAVE_GRABBING
                    || ai.getCurrentState() == X18State.DEEP_CAVE_BLACKOUT;
            if (caveDayMode != X18CaveDayScheduler.CaveDayMode.REAL && !blackoutActive) {
                suppressNonRealCaveDay(ai, x18tf, caveDayMode);
                return;
            }
            if (caveDayMode == X18CaveDayScheduler.CaveDayMode.REAL) {
                lastSuppressedCaveMode = null;
            }

            // Find nearest cave player this tick (uses hysteresis)
            TargetData target = findNearestCavePlayer(world, store, x18tf.getPosition());

            // -- Session tracking with hysteresis -----------------------------
            // playerCaveConfirmed uses a 10-block deadband (enter <=85, exit >95)
            // to prevent false session resets on ramps and terrain transitions.
            boolean playerInCave = (target != null);
            if (!playerInCave && playerWasInCave) {
                // Player left the cave - reset session so attack can happen again
                ai.resetSession();
                lastPlayerPos = null;
                playerCaveConfirmed = false;
                log(Level.INFO, "[AI] Player left cave (y > " + CAVE_EXIT_Y + "). Session reset.");
            }
            if (playerInCave && !playerWasInCave) {
                playerCaveConfirmed = true;
            }
            playerWasInCave = playerInCave;

            // -- Stillness tracking -------------------------------------------
            // Update per-tick regardless of state so the counter builds up
            // while X_18 is hidden and resets the moment the player moves.
            if (target != null) {
                Vector3d curPos = target.tf.getPosition();
                double moveDelta = (lastPlayerPos != null)
                        ? curPos.distance(lastPlayerPos)
                        : 1.0; // treat first tick as "moving"
                ai.updateStillness(moveDelta);
                lastPlayerPos = new Vector3d(curPos);
            } else {
                lastPlayerPos = null;
            }

            // -- Black Screen safety close countdown --------------------------
            // Runs before state dispatch so a crash in dispatch never blocks close.
            // Uses stored PlayerRef/Store from when the screen was shown.
            if (ai.getBlackScreenCloseTicks() > 0) {
                ai.decrementBlackScreenClose();
                if (ai.getBlackScreenCloseTicks() <= 0) {
                    try {
                        forceCloseBlackScreen(ai, world, target, store);
                        log(Level.INFO, "[AI] Safety auto-close: black screen closed.");
                    } catch (Exception safetyEx) {
                        log(Level.WARNING, "[AI] Safety close exception: " + safetyEx.getMessage());
                        // Clear refs anyway so we don't retry indefinitely
                        blackScreenPlayerRef = null;
                        blackScreenStore = null;
                    }
                }
            }

            // -- Deep Cave Dwell Tracking -------------------------------------
            // Accumulates dwell time when the player is below Y=60 (absolute).
            // When dwell reaches 800 ticks (~40 s), triggers deep cave event.
            // Dwell decays gradually (not hard-reset) when above threshold so
            // brief climbs don't wipe progress. After the event fires it is
            // disabled until the next night.
            if (target != null && !ai.isDeepCaveEventFiredToday()) {
                double playerY = target.tf.getPosition().y();
                if (playerY < X18AIComponent.DEEP_CAVE_Y_THRESHOLD) {
                    ai.incrementDeepCaveDwell();
                    if (ai.getDeepCaveDwellTicks() >= X18AIComponent.DEEP_CAVE_DWELL_REQUIRED) {
                        // Dwell requirement met - trigger deep cave event
                        triggerDeepCaveEvent(ai, x18tf, world, target);
                    }
                } else {
                    // Player above threshold - decay dwell counter gradually.
                    // Using decayDeepCaveDwell() instead of a hard reset so that
                    // brief terrain climbs (ramps, ledges) don't wipe accumulated
                    // dwell time. The counter loses 3 ticks per game-tick while
                    // above threshold, draining fully only after ~267 ticks (~13 s)
                    // of sustained time above Y=DEEP_CAVE_Y_THRESHOLD.
                    ai.decayDeepCaveDwell();
                }
            }

            // -- State dispatch -----------------------------------------------
            switch (ai.getCurrentState()) {
                case HIDDEN:
                    tickHidden(ai, x18tf, world, target);
                    break;
                case STALKING:
                    tickStalking(ai, x18tf, world, target);
                    break;
                case CHARGING:
                    tickCharging(ai, x18tf, x18Ref, world, target, commandBuffer, store);
                    break;
                case VANISHING:
                    tickVanishing(ai, x18tf, x18Ref, store);
                    break;
                case LURKING:
                    tickLurking(ai, x18tf, world, target);
                    break;
                case DEEP_CAVE_CHARGING:
                    tickDeepCaveCharging(ai, x18tf, x18Ref, world, target, commandBuffer, store);
                    break;
                case DEEP_CAVE_GRABBING:
                    tickDeepCaveGrabbing(ai, x18tf, x18Ref, world, target, store);
                    break;
                case DEEP_CAVE_BLACKOUT:
                    tickDeepCaveBlackout(ai, x18tf, x18Ref, world, target, store);
                    break;
                default:
                    scheduleVanish(ai);
                    break;
            }
        } catch (Exception e) {
            if (X17Plugin.getInstance() != null) {
                X17Plugin.getInstance().logException(Level.WARNING,
                        "[AI] Tick exception", e);
            }
        }
    }

    // =========================================================================
    // STATE: HIDDEN
    // Underground pool. Each tick (after cooldown) attempts to reposition.
    // Three-tier search guarantees a position is almost always found.
    // =========================================================================

    private void tickHidden(X18AIComponent ai, TransformComponent x18tf,
            World world, TargetData target) {

        // Safety - push underground if drifted above exit threshold
        Vector3d cur = x18tf.getPosition();
        if (cur.y() > CAVE_EXIT_Y) {
            x18tf.teleportPosition(new Vector3d(cur.x(), POOL_HIDE_Y, cur.z()));
            log(Level.WARNING, "[AI] Drifted above cave limit while HIDDEN - pushed underground.");
        }

        // Not ready yet, or no player in cave
        if (ai.getSpawnCooldownTicks() > 0 || target == null)
            return;

        // v0.3.5: Removed the incorrect "hold in HIDDEN" block that prevented
        // X_18 from appearing while the player was above DEEP_CAVE_Y_THRESHOLD.
        // The deep cave dwell tracker runs independently in the main tick and
        // calls triggerDeepCaveEvent() directly when the threshold is met,
        // regardless of X_18's current state. Blocking HIDDEN here prevented
        // normal STALK/LURK appearances without any benefit to the dwell logic.

        // -- Stillness gate ----------------------------------------------------
        // X_18 only emerges when the player has been standing still long enough.
        // This makes caves feel watched specifically when the player stops to
        // look around - movement breaks the spell.
        //
        // EXCEPTION: after a charge attack, ignoreStillnessOnce is set so the
        // X_18 is guaranteed to re-appear even if the player is running.
        if (ai.isIgnoreStillnessOnce()) {
            ai.setIgnoreStillnessOnce(false);
            log(Level.INFO, "[AI] Stillness bypassed (post-charge aggression).");
        } else if (!ai.isPlayerSufficientlyStill()) {
            return;
        }

        // Search for a valid spawn position
        Vector3d spawnPos = findSpawnPosition(world, target.tf);
        if (spawnPos == null) {
            // No walkable position found - short retry, don't eat the long gap
            ai.setSpawnCooldownTicks(X18AIComponent.SEARCH_RETRY_COOLDOWN);
            log(Level.INFO, "[AI] Position search failed. Retry in " + X18AIComponent.SEARCH_RETRY_COOLDOWN + "t.");
            return;
        }

        // Teleport and begin stalking OR lurking
        // -- LURK roll: ~40% chance if a same-level position with LOS exists --
        boolean tryLurk = rng.nextInt(100) < X18AIComponent.LURK_CHANCE_PCT;
        if (tryLurk) {
            Vector3d lurkPos = findLurkPosition(world, target.tf);
            if (lurkPos != null) {
                x18tf.teleportPosition(lurkPos);
                faceToward(x18tf, target.tf.getPosition());
                ai.setLastKnownPlayerPos(
                        target.tf.getPosition().x(),
                        target.tf.getPosition().y(),
                        target.tf.getPosition().z());
                ai.setDamageDone(false);
                ai.setActionTimerTicks(X18AIComponent.LURK_DURATION_TICKS);
                lookExposureLurk = 0;
                ai.setCurrentState(X18AIComponent.X18State.LURKING);
                ai.incrementAppearanceCount();
                log(Level.INFO, "[AI] LURKING @ " + fmt(lurkPos)
                        + "  appearance#" + ai.getAppearanceCount()
                        + "  dist=" + fmt1(lurkPos.distance(target.tf.getPosition())) + "b");
                return;
            }
            // No valid lurk position - fall through to normal STALK
            log(Level.INFO, "[AI] Lurk roll hit but no LOS position - falling back to STALK.");
        }

        // Normal stalk path
        x18tf.teleportPosition(spawnPos);
        faceToward(x18tf, target.tf.getPosition());
        ai.setLastKnownPlayerPos(
                target.tf.getPosition().x(),
                target.tf.getPosition().y(),
                target.tf.getPosition().z());
        ai.setDamageDone(false);
        ai.setActionTimerTicks(X18AIComponent.STALK_DURATION_TICKS);
        lookExposure = 0;
        ai.setCurrentState(X18State.STALKING);
        ai.incrementAppearanceCount();
        log(Level.INFO, "[AI] STALKING @ " + fmt(spawnPos)
                + "  appearance#" + ai.getAppearanceCount()
                + "  attackUsed=" + ai.isAttackUsedThisSession());
    }

    // =========================================================================
    // STATE: STALKING
    // Visible, staring. Tracks look-exposure.
    // * lookExposure >= STALK_EXPOSURE_TICKS OR stalk timer expired
    // -> 75% vanish, 25% charge (unless attack already used this session)
    // * Player within PROXIMITY_VANISH_DIST -> vanish immediately
    // * Player left cave -> vanish, reset session
    // =========================================================================

    private void tickStalking(X18AIComponent ai, TransformComponent x18tf,
            World world, TargetData target) {

        // Player left cave
        if (target == null) {
            log(Level.INFO, "[AI] Player left cave during stalk - vanishing.");
            scheduleVanish(ai);
            return;
        }

        // Keep facing player and update last-known position
        faceToward(x18tf, target.tf.getPosition());
        ai.setLastKnownPlayerPos(
                target.tf.getPosition().x(),
                target.tf.getPosition().y(),
                target.tf.getPosition().z());

        double dist = x18tf.getPosition().distance(target.tf.getPosition());

        // Proximity vanish - player walked too close
        if (dist < PROXIMITY_VANISH_DIST) {
            log(Level.INFO, "[AI] Proximity vanish (dist=" + fmt1(dist) + ").");
            scheduleVanish(ai);
            return;
        }

        // Accumulate / decay look-exposure
        if (isPlayerWatching(target.tf, x18tf.getPosition())) {
            lookExposure++;
        } else {
            if (lookExposure > 0)
                lookExposure--;
        }

        boolean spotted = lookExposure >= X18AIComponent.STALK_EXPOSURE_TICKS;
        boolean timerExpired = ai.getActionTimerTicks() <= 0;

        if (!spotted && !timerExpired)
            return; // still stalking

        // -- 75 / 25 roll -----------------------------------------------------
        // v0.3.5: raised from 5% to 25% charge chance.
        // Original 5% was too rare - with ~3-4 stalk events per night the
        // player almost never saw a charge. 25% means ~1 charge per 4 events,
        // which reliably guarantees at least one per night.
        String trigger = spotted ? "spotted" : "timer";
        lookExposure = 0;

        // Attack is only available if not used this session AND the roll hits
        boolean canAttack = !ai.isAttackUsedThisSession();
        boolean doCharge = canAttack && (rng.nextInt(100) < 25); // 25% charge

        if (doCharge) {
            log(Level.INFO, "[AI] 25% CHARGE triggered (" + trigger + ").");
            ai.setActionTimerTicks(X18AIComponent.CHARGE_DURATION_TICKS);
            ai.setCurrentState(X18State.CHARGING);
        } else {
            log(Level.INFO, "[AI] 75% vanish (" + trigger + ").");
            scheduleVanish(ai);
        }
    }

    // =========================================================================
    // STATE: CHARGING
    // Sprints at the player. Applies damage on contact, then vanishes.
    // Attack flag is set so it cannot happen again this session.
    // =========================================================================

    private void tickCharging(X18AIComponent ai, TransformComponent x18tf,
            Ref<EntityStore> x18Ref, World world, TargetData target,
            CommandBuffer<EntityStore> commandBuffer, Store<EntityStore> store) {

        if (target == null) {
            log(Level.INFO, "[AI] Charge aborted - player left cave.");
            scheduleVanishPostCharge(ai);
            return;
        }

        // Play Charged_Attack animation every tick (idempotent - skips if already set)
        playStatusAnimation(store, x18Ref, "ChargeAttack");

        Vector3d playerPos = target.tf.getPosition();
        faceToward(x18tf, playerPos);
        moveToward(x18tf, playerPos, CHARGE_SPEED);
        ai.setLastKnownPlayerPos(playerPos.x(), playerPos.y(), playerPos.z());

        double dist = x18tf.getPosition().distance(playerPos);

        // Deal damage once on contact
        if (!ai.isDamageDone() && dist <= DAMAGE_DIST) {
            applyDamage(commandBuffer, x18Ref, target.ref);
            ai.setDamageDone(true);
            ai.setAttackUsedThisSession(true);
            log(Level.INFO, "[AI] Charge damage applied. Attack locked for this session.");
            scheduleVanishPostCharge(ai);
            return;
        }

        // Charge timer expired without contact
        if (ai.getActionTimerTicks() <= 0) {
            ai.setAttackUsedThisSession(true); // still counts as "used"
            log(Level.INFO, "[AI] Charge timer expired (missed). Attack locked.");
            scheduleVanishPostCharge(ai);
        }
    }

    // =========================================================================
    // STATE: VANISHING
    // Plays the Despawn animation on the first tick, then hides underground
    // after a 2-tick buffer so the client can render the animation start frame.
    // =========================================================================

    private void tickVanishing(X18AIComponent ai, TransformComponent x18tf,
            Ref<EntityStore> x18Ref, Store<EntityStore> store) {
        if (!ai.isDespawnAnimStarted()) {
            // First tick of VANISHING: play Despawn animation and start buffer.
            // v7 FIX: this used to check/set damageDone, which had already
            // been forced to true by tickCharging() on a successful hit (damage
            // applied -> setDamageDone(true) -> scheduleVanishPostCharge()).
            // That made this branch skip on the very first tick after a
            // successful charge, so the Despawn animation never played and the
            // entity jumped straight to the buffer-expired branch below in the
            // same tick. despawnAnimStarted is a dedicated flag so it can never
            // be pre-set by unrelated combat logic.
            playStatusAnimation(store, x18Ref, "Despawn");
            ai.setDespawnAnimStarted(true);
            // v0.3.7c FIX: was 2 ticks (0.1s) - nowhere near enough for the
            // animation to actually play. Despawn.blockyanim has duration=60
            // with its "sink into the ground" keyframe at time=45, and its
            // hold-frame is only reached at the very end. The native role AI
            // (Template_X_18.json's old Vanish state) played the same-duration
            // Alerted.blockyanim and waited a full 1.5s (30 ticks) before
            // actually removing the entity - that's the proven, designer-picked
            // amount of time a duration=60 clip needs to read. 2 ticks was
            // teleporting X_18 underground before the animation had even left
            // its first frame, so it looked like it never played at all.
            ai.setActionTimerTicks(DESPAWN_ANIM_BUFFER_TICKS);
            return;
        }
        // Buffer expired - actually hide underground
        if (ai.getActionTimerTicks() <= 0) {
            ai.setDespawnAnimStarted(false); // reset flag for next cycle
            if (ai.isPendingDeepCaveShutdown()) {
                // This vanish cycle was started on behalf of a deep-cave
                // charge/grab event - finish with the long day-disable
                // shutdown instead of the normal short-cooldown hide.
                ai.setPendingDeepCaveShutdown(false);
                shutdownForDay(ai, x18tf);
            } else {
                hideUnderground(ai, x18tf);
            }
        }
        // else: waiting for buffer to tick down (handled by central decrement)
    }

    // =========================================================================
    // STATE: LURKING
    //
    // X_18 is far away (13-18 blocks) and deep below the player, completely
    // still, staring up. It vanishes the moment the player locks eyes with it
    // (>=2 ticks) or after the lurk timer expires. No charge is possible.
    //
    // Design intent: the player sees something in the dark far below them,
    // then it's gone. Pure dread, no aggression.
    // =========================================================================

    private void tickLurking(X18AIComponent ai, TransformComponent x18tf,
            World world, TargetData target) {

        // Player left cave
        if (target == null) {
            log(Level.INFO, "[AI] Player left cave during lurk - vanishing.");
            scheduleVanish(ai);
            return;
        }

        // Keep facing player silently (no movement)
        faceToward(x18tf, target.tf.getPosition());
        ai.setLastKnownPlayerPos(
                target.tf.getPosition().x(),
                target.tf.getPosition().y(),
                target.tf.getPosition().z());

        double dist = x18tf.getPosition().distance(target.tf.getPosition());

        // Proximity vanish - player somehow walked very close
        if (dist < PROXIMITY_VANISH_DIST) {
            log(Level.INFO, "[AI] Lurk proximity vanish (dist=" + fmt1(dist) + ").");
            scheduleVanish(ai);
            return;
        }

        // Eye-contact detection - accumulate exposure (100 ticks = 5 s)
        if (isPlayerWatching(target.tf, x18tf.getPosition())) {
            lookExposureLurk++;
        } else {
            if (lookExposureLurk > 0)
                lookExposureLurk--;
        }

        if (lookExposureLurk >= X18AIComponent.LURK_EXPOSURE_TICKS) {
            log(Level.INFO, "[AI] Lurk spotted - vanishing.");
            lookExposureLurk = 0;
            scheduleVanish(ai);
            return;
        }

        // Timer expired - vanish silently, unseen
        if (ai.getActionTimerTicks() <= 0) {
            log(Level.INFO, "[AI] Lurk timer expired - vanishing unseen.");
            lookExposureLurk = 0;
            scheduleVanish(ai);
        }
    }

    // =========================================================================
    // DEEP CAVE EVENT - triggered after 120s (2400 ticks) below Y=60
    //
    // 50/50 split:
    // 50% -> DEEP_CAVE_CHARGING: guaranteed charge attack
    // 50% -> DEEP_CAVE_GRABBING: instant approach + grab animation
    //
    // After either event: X_18 shuts down until next night.
    // =========================================================================

    /**
     * Called when the deep cave dwell timer reaches the threshold.
     * Rolls 50/50 probability and transitions to the appropriate state.
     * The X_18 is teleported to a valid position near the player.
     */
    private void triggerDeepCaveEvent(X18AIComponent ai, TransformComponent x18tf,
            World world, TargetData target) {
        // Event triggers - find a spawn position near the player
        Vector3d spawnPos = findSpawnPosition(world, target.tf);
        if (spawnPos == null) {
            // Fallback: try lurk position
            spawnPos = findLurkPosition(world, target.tf);
        }
        if (spawnPos == null) {
            // No valid position - retry next tick
            log(Level.WARNING, "[AI] Deep cave event: no valid position. Retrying.");
            return;
        }

        // Teleport to position and face the player
        x18tf.teleportPosition(spawnPos);
        faceToward(x18tf, target.tf.getPosition());
        ai.setLastKnownPlayerPos(
                target.tf.getPosition().x(),
                target.tf.getPosition().y(),
                target.tf.getPosition().z());
        ai.setDamageDone(false);

        // 50/50 roll: Grab vs Charge
        int grabRoll = rng.nextInt(100);
        if (grabRoll < X18AIComponent.DEEP_CAVE_GRAB_PCT) {
            // GRAB event - instant approach + grab hold + blackout + teleport
            ai.setGrabSubPhase(0);
            ai.setActionTimerTicks(X18AIComponent.DEEP_CAVE_GRAB_HOLD_TICKS);
            ai.setCurrentState(X18State.DEEP_CAVE_GRABBING);
            log(Level.INFO, "[AI] DEEP CAVE GRAB triggered @ " + fmt(spawnPos)
                    + " (grabRoll=" + grabRoll + "%, threshold=" + X18AIComponent.DEEP_CAVE_GRAB_PCT + "%)");
        } else {
            // CHARGE event - guaranteed charge attack
            ai.setActionTimerTicks(X18AIComponent.DEEP_CAVE_CHARGE_DURATION);
            ai.setCurrentState(X18State.DEEP_CAVE_CHARGING);
            log(Level.INFO, "[AI] DEEP CAVE CHARGE triggered @ " + fmt(spawnPos)
                    + " (grabRoll=" + grabRoll + "%, threshold=" + X18AIComponent.DEEP_CAVE_GRAB_PCT + "%)");
        }

        ai.setDeepCaveEventFiredToday(true);
        ai.resetDeepCaveDwell();
    }

    /**
     * DEEP_CAVE_CHARGING: guaranteed charge attack at the player.
     * Functions like normal CHARGING but always commits - no abort on timer.
     * After damage or timer, shuts down X_18 for the rest of the day.
     */
    private void tickDeepCaveCharging(X18AIComponent ai, TransformComponent x18tf,
            Ref<EntityStore> x18Ref, World world, TargetData target,
            CommandBuffer<EntityStore> commandBuffer, Store<EntityStore> store) {
        if (target == null) {
            log(Level.INFO, "[AI] Deep cave charge: player left cave.");
            beginDeepCaveDespawn(ai);
            return;
        }

        // Play Charged_Attack animation every tick (idempotent - skips if already set)
        playStatusAnimation(store, x18Ref, "ChargeAttack");

        Vector3d playerPos = target.tf.getPosition();
        faceToward(x18tf, playerPos);
        moveToward(x18tf, playerPos, CHARGE_SPEED);
        ai.setLastKnownPlayerPos(playerPos.x(), playerPos.y(), playerPos.z());

        double dist = x18tf.getPosition().distance(playerPos);

        // Deal damage on contact
        if (!ai.isDamageDone() && dist <= DAMAGE_DIST) {
            applyDamage(commandBuffer, x18Ref, target.ref);
            ai.setDamageDone(true);
            log(Level.INFO, "[AI] Deep cave charge: damage applied. Playing Despawn, then shutting down for day.");
            // v0.3.7 FIX: used to call shutdownForDay() directly here, which
            // skips the Despawn animation entirely. Route through VANISHING
            // first so the animation actually plays.
            beginDeepCaveDespawn(ai);
            return;
        }

        // Timer expired - even if missed, shut down
        if (ai.getActionTimerTicks() <= 0) {
            log(Level.INFO, "[AI] Deep cave charge: timer expired (missed). Playing Despawn, then shutting down.");
            beginDeepCaveDespawn(ai);
        }
    }

    /**
     * DEEP_CAVE_GRABBING - full grab sequence with player immobilization.
     *
     * Sub-phase 0 (APPROACH):
     * X_18 charges toward the player at DEEP_CAVE_GRAB_SPEED using the
     * Charged_Attack animation. On contact (dist <= DAMAGE_DIST), saves the
     * grab origin position and transitions to sub-phase 1.
     *
     * Sub-phase 1 (GRAB HOLD):
     * X_18 plays the Grab animation. The player is immobilized: each tick
     * we force-teleport the player's position back to the grab origin and
     * lock their rotation to face the X_18. After DEEP_CAVE_GRAB_HOLD_TICKS,
     * transitions to DEEP_CAVE_BLACKOUT.
     */
    private void tickDeepCaveGrabbing(X18AIComponent ai, TransformComponent x18tf,
            Ref<EntityStore> x18Ref, World world, TargetData target,
            Store<EntityStore> store) {
        if (target == null) {
            log(Level.INFO, "[AI] Deep cave grab: player left cave.");
            beginDeepCaveDespawn(ai);
            return;
        }

        Vector3d playerPos = target.tf.getPosition();
        ai.setLastKnownPlayerPos(playerPos.x(), playerPos.y(), playerPos.z());

        int subPhase = ai.getGrabSubPhase();

        if (subPhase == 0) {
            // -- APPROACH: rush toward player with Charged_Attack animation -----
            faceToward(x18tf, playerPos);
            double dist = x18tf.getPosition().distance(playerPos);

            // Set Charged_Attack animation on the X_18 entity
            playStatusAnimation(store, x18Ref, "ChargeAttack");

            if (dist > DAMAGE_DIST) {
                moveToward(x18tf, playerPos, X18AIComponent.DEEP_CAVE_GRAB_SPEED);
            } else {
                // -- CONTACT: save grab origin, switch to Grab animation ------
                ai.setGrabOrigin(playerPos.x(), playerPos.y(), playerPos.z());

                // Calculate grab stand position 1.2 blocks in front of the player
                float playerYaw = target.tf.getRotation().yaw();
                double dx = Math.sin(playerYaw) * 1.2;
                double dz = Math.cos(playerYaw) * 1.2;
                double sx = playerPos.x() + dx;
                double sy = playerPos.y();
                double sz = playerPos.z() + dz;
                ai.setGrabStand(sx, sy, sz);

                ai.setDamageDone(true); // reuse flag for "contact made"
                ai.setActionTimerTicks(X18AIComponent.DEEP_CAVE_GRAB_HOLD_TICKS);
                ai.setGrabSubPhase(1);

                // Teleport X_18 to the stand position
                x18tf.teleportPosition(new Vector3d(sx, sy, sz));
                faceToward(x18tf, playerPos);

                // Switch to Grab animation
                playStatusAnimation(store, x18Ref, "Grab");

                log(Level.INFO, "[AI] Deep cave grab: CONTACT @ " + fmt(playerPos)
                        + ". Stand pos: " + fmt(new Vector3d(sx, sy, sz))
                        + ". Holding for " + X18AIComponent.DEEP_CAVE_GRAB_HOLD_TICKS + "t.");
            }
        } else {
            ai.decrementActionTimer();

            // -- GRAB HOLD: immobilize player, lock vision to X_18 ------------
            Vector3d grabOrigin = new Vector3d(
                    ai.getGrabOriginX(), ai.getGrabOriginY(), ai.getGrabOriginZ());
            Vector3d grabStand = new Vector3d(
                    ai.getGrabStandX(), ai.getGrabStandY(), ai.getGrabStandZ());

            // Force player position back to grab origin every tick (immobilize)
            target.tf.teleportPosition(grabOrigin);

            // Lock player rotation to face the X_18 (at grabStand)
            double dx = grabStand.x() - grabOrigin.x();
            double dz = grabStand.z() - grabOrigin.z();
            float playerYaw = (float) Math.atan2(dx, dz);
            // Calculate pitch to look at X_18's head level (approx. +1.6 height)
            double dy = grabStand.y() - grabOrigin.y() + 1.6;
            double horizDist = Math.sqrt(dx * dx + dz * dz);
            float playerPitch = (float) Math.atan2(dy, horizDist);
            target.tf.teleportRotation(new Rotation3f(playerPitch, playerYaw, 0f));

            // Force X_18 position to grabStand, facing the player (grabOrigin)
            x18tf.teleportPosition(grabStand);
            faceToward(x18tf, grabOrigin);
            playStatusAnimation(store, x18Ref, "Grab");

            if (ai.getActionTimerTicks() <= 0) {
                // Hold complete - transition to blackout
                log(Level.INFO, "[AI] Deep cave grab: hold complete. Starting blackout.");
                ai.setGrabSubPhase(0);
                ai.setActionTimerTicks(X18AIComponent.DEEP_CAVE_BLACKOUT_DURATION);
                ai.setCurrentState(X18State.DEEP_CAVE_BLACKOUT);

                // Show black screen overlay - try playerRef lookup, then brute-force all
                // players
                PlayerRef playerRef = findPlayerRef(world, target.ref);
                if (playerRef == null && world != null && world.getPlayerRefs() != null) {
                    // Fallback: use any available player in the world
                    for (PlayerRef pr : world.getPlayerRefs()) {
                        if (pr != null) {
                            playerRef = pr;
                            break;
                        }
                    }
                }
                // Safety timer is ALWAYS armed, even when playerRef is null.
                // This guarantees the blackout state advances to FADE and the
                // player regains vision within the allotted time regardless of
                // whether the UI overlay was successfully opened.
                ai.setBlackScreenCloseTicks(
                        X18AIComponent.DEEP_CAVE_BLACKOUT_DURATION
                                + X18AIComponent.DEEP_CAVE_BLACKOUT_FADE_DURATION + 20);
                x18tf.teleportPosition(new Vector3d(grabStand.x(), safeHideY(x18tf), grabStand.z()));
                if (playerRef != null) {
                    X18BlackScreenPage.showTo(playerRef, store);
                    blackScreenPlayerRef = playerRef;
                    blackScreenStore = store;
                    log(Level.INFO, "[AI] Black screen shown. Safety timer set to "
                            + ai.getBlackScreenCloseTicks() + "t.");
                } else {
                    log(Level.WARNING, "[AI] Could not find playerRef to show black screen! "
                            + "Safety timer still armed (" + ai.getBlackScreenCloseTicks() + "t) "
                            + "- state will advance normally.");
                }
            }
        }
    }

    /**
     * DEEP_CAVE_BLACKOUT - blackout overlay + teleport + release.
     *
     * Sub-phase 0 (BLACKOUT):
     * Opaque black screen shown. Player is still immobilized at the grab
     * origin. When the blackout timer expires, teleport the player to a
     * safe location >=30 blocks away within the cave system, then advance
     * to sub-phase 1.
     *
     * Sub-phase 1 (FADE/RELEASE):
     * Player is at the new location. Blackout fades. When the fade timer
     * expires, close the UI overlay and shut X_18 down for the day.
     */
    private void tickDeepCaveBlackout(X18AIComponent ai, TransformComponent x18tf,
            Ref<EntityStore> x18Ref, World world, TargetData target,
            Store<EntityStore> store) {

        ai.decrementActionTimer();

        int subPhase = ai.getGrabSubPhase();

        if (subPhase == 0) {
            // -- BLACKOUT PHASE: player frozen, screen black ------------------

            // If player left cave mid-blackout, force-close the screen immediately
            // and advance to fade phase. Without the explicit close here the screen
            // would stay open because the normal fade path needs target != null.
            if (target == null) {
                log(Level.WARNING,
                        "[AI] Blackout phase: player left cave - force-closing screen and advancing to fade.");
                forceCloseBlackScreen(ai, world, null, store);
                ai.setGrabSubPhase(1);
                ai.setActionTimerTicks(X18AIComponent.DEEP_CAVE_BLACKOUT_FADE_DURATION);
                return;
            }

            // Keep player pinned at grab origin
            Vector3d grabOrigin = new Vector3d(
                    ai.getGrabOriginX(), ai.getGrabOriginY(), ai.getGrabOriginZ());
            target.tf.teleportPosition(grabOrigin);

            if (ai.getActionTimerTicks() <= 0) {
                // Blackout timer expired - teleport player
                Vector3d teleportDest = findDeepCaveTeleportPosition(world, grabOrigin);
                if (teleportDest != null) {
                    target.tf.teleportPosition(teleportDest);
                    log(Level.INFO, "[AI] Blackout teleport: player moved to " + fmt(teleportDest)
                            + " (dist=" + fmt1(grabOrigin.distance(teleportDest)) + "b).");
                } else {
                    // Fallback: surface-level position near origin
                    Vector3d fallback = new Vector3d(
                            grabOrigin.x() + 30.0, CAVE_ENTER_Y, grabOrigin.z() + 30.0);
                    target.tf.teleportPosition(fallback);
                    log(Level.WARNING, "[AI] Blackout teleport: no cave pos found, surface fallback.");
                }

                // Advance to fade phase
                ai.setGrabSubPhase(1);
                ai.setActionTimerTicks(X18AIComponent.DEEP_CAVE_BLACKOUT_FADE_DURATION);
                log(Level.INFO, "[AI] Blackout phase complete. Fading...");
            }

        } else {
            // -- FADE PHASE: vision gradually returning -----------------------
            if (ai.getActionTimerTicks() <= 0) {
                // Close black screen - force all strategies
                forceCloseBlackScreen(ai, world, target, store);
                log(Level.INFO, "[AI] Blackout fade complete. Shutting down X_18 for day.");
                ai.setGrabSubPhase(0);
                // NOTE: intentionally NOT routed through beginDeepCaveDespawn()/
                // Despawn animation here. By this point the player was already
                // teleported DEEP_CAVE_GRAB_TELEPORT_MIN_DIST-MAX_DIST (30-50
                // blocks) away from the X_18 as part of the blackout sequence,
                // so there is nobody nearby left to see the animation anyway.
                shutdownForDay(ai, x18tf);
            }
        }
    }

    /**
     * Finds a safe teleport destination for the player after the grab event.
     * Searches for a walkable cave position at least
     * DEEP_CAVE_GRAB_TELEPORT_MIN_DIST
     * blocks from the origin, up to DEEP_CAVE_GRAB_TELEPORT_MAX_DIST.
     *
     * Strategy: tries 120 random directions at distances 30-50 blocks from origin.
     * Each candidate is floor-scanned (+/-10 vertical) to find a walkable position
     * that is still underground (below CAVE_EXIT_Y) and has a cave chamber.
     */
    private Vector3d findDeepCaveTeleportPosition(World world, Vector3d origin) {
        Vector3d bestPos = null;
        double bestDist = 0.0;

        for (int i = 0; i < 120; i++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double dist = randomRange(
                    X18AIComponent.DEEP_CAVE_GRAB_TELEPORT_MIN_DIST,
                    X18AIComponent.DEEP_CAVE_GRAB_TELEPORT_MAX_DIST);

            double cx = origin.x() + Math.sin(angle) * dist;
            double cz = origin.z() + Math.cos(angle) * dist;

            // Scan vertical +/-10 blocks around origin Y to find a walkable floor
            Vector3d found = findFloorAt(world, cx, origin.y(), cz, 10);
            if (found == null)
                continue;

            // Must be underground
            if (found.y() > CAVE_EXIT_Y)
                continue;
            if (found.y() < 1.0)
                continue;

            // Must have a cave chamber (not a tiny crevice)
            if (!hasCaveChamber(world, found))
                continue;

            // Verify minimum distance from origin
            double actualDist = found.distance(origin);
            if (actualDist < X18AIComponent.DEEP_CAVE_GRAB_TELEPORT_MIN_DIST)
                continue;

            // Prefer positions that are farther away
            if (actualDist > bestDist) {
                bestDist = actualDist;
                bestPos = found;
            }
        }

        if (bestPos != null) {
            log(Level.INFO, "[AI] Teleport position found @ " + fmt(bestPos)
                    + " dist=" + fmt1(bestDist) + "b from grab origin.");
        }
        return bestPos;
    }

    /**
     * Resolves a PlayerRef from an entity Ref by iterating world player refs.
     * Used to interact with the Player UI system (open/close custom pages).
     */
    private PlayerRef findPlayerRef(World world, Ref<EntityStore> entityRef) {
        if (world == null || world.getPlayerRefs() == null)
            return null;
        for (PlayerRef pr : world.getPlayerRefs()) {
            if (pr != null && pr.getReference() != null
                    && pr.getReference().equals(entityRef)) {
                return pr;
            }
        }
        return null;
    }

    /**
     * Guaranteed black screen close. Tries multiple strategies in order:
     * 1. Use stored blackScreenPlayerRef (set when the screen was shown)
     * 2. Use target-based lookup (if target is available this tick)
     * 3. Brute-force: close for ALL players in the world
     * 4. Global active-black-screen registry fallback
     * Clears the safety timer and stored refs after closing.
     */
    private void forceCloseBlackScreen(X18AIComponent ai, World world,
            TargetData target, Store<EntityStore> store) {
        boolean closed = false;

        // Strategy 1: use the exact player/store pair captured when opened.
        if (blackScreenPlayerRef != null && blackScreenStore != null) {
            closed = X18BlackScreenPage.closeFor(blackScreenPlayerRef, blackScreenStore);
        }

        // Strategy 2: use the current target if available.
        if (!closed && target != null) {
            PlayerRef playerRef = findPlayerRef(world, target.ref);
            if (playerRef != null) {
                Store<EntityStore> closeStore = (blackScreenStore != null) ? blackScreenStore : store;
                closed = X18BlackScreenPage.closeFor(playerRef, closeStore);
            }
        }

        // Strategy 3: brute-force close for all players in the world.
        if (!closed && world != null && world.getPlayerRefs() != null) {
            Store<EntityStore> closeStore = (blackScreenStore != null) ? blackScreenStore : store;
            for (PlayerRef pr : world.getPlayerRefs()) {
                if (pr != null && X18BlackScreenPage.closeFor(pr, closeStore)) {
                    closed = true;
                }
            }
        }

        // Strategy 4: close anything tracked by the global safety registry.
        X18BlackScreenPage.forceCloseAll(store);

        // Always clear state regardless. The page itself is dismissible, and the
        // independent safety system will keep trying if this tick raced the UI.
        ai.setBlackScreenCloseTicks(0);
        blackScreenPlayerRef = null;
        blackScreenStore = null;

        if (!closed) {
            log(Level.WARNING, "[AI] forceCloseBlackScreen: direct close failed; global safety fallback invoked.");
        }
    }

    /** Keeps the pooled X_18 harmless on GHOST_CAVE and SILENT_CAVE days. */
    private void suppressNonRealCaveDay(X18AIComponent ai, TransformComponent x18tf,
            X18CaveDayScheduler.CaveDayMode mode) {
        lookExposure = 0;
        lookExposureLurk = 0;
        playerWasInCave = false;
        playerCaveConfirmed = false;
        lastPlayerPos = null;
        ai.resetDeepCaveDwell();
        ai.setCurrentState(X18State.HIDDEN);
        ai.setActionTimerTicks(0);
        ai.setDamageDone(false);
        ai.setIgnoreStillnessOnce(false);
        ai.setSpawnCooldownTicks(X18AIComponent.POST_APPEARANCE_COOLDOWN);

        double lx = ai.getLastKnownPlayerX();
        double lz = ai.getLastKnownPlayerZ();
        if (lx == 0.0 && lz == 0.0) {
            Vector3d cur = x18tf.getPosition();
            lx = cur.x();
            lz = cur.z();
        }
        x18tf.teleportPosition(new Vector3d(lx, safeHideY(x18tf), lz));

        if (lastSuppressedCaveMode != mode) {
            lastSuppressedCaveMode = mode;
            log(Level.INFO, "[AI] Cave day mode " + mode
                    + " active. Real X_18 appearances suppressed.");
        }
    }

    /**
     * Shuts down the X_18 for the rest of the day after a deep cave event.
     * Hides underground and sets an extremely long cooldown that effectively
     * prevents any further appearances until resetDailyEvent() is called.
     */
    private void shutdownForDay(X18AIComponent ai, TransformComponent x18tf) {
        lookExposure = 0;
        lookExposureLurk = 0;

        // Always force-close the black screen - even if the timer already expired,
        // the screen may still be open if a previous close attempt silently failed.
        if (blackScreenPlayerRef != null && blackScreenStore != null) {
            try {
                X18BlackScreenPage.closeFor(blackScreenPlayerRef, blackScreenStore);
                log(Level.INFO, "[AI] shutdownForDay: closed black screen.");
            } catch (Exception ignored) {
            }
        }
        ai.setBlackScreenCloseTicks(0);
        blackScreenPlayerRef = null;
        blackScreenStore = null;

        double lx = ai.getLastKnownPlayerX();
        double lz = ai.getLastKnownPlayerZ();
        if (lx == 0.0 && lz == 0.0) {
            Vector3d cur = x18tf.getPosition();
            lx = cur.x();
            lz = cur.z();
        }

        x18tf.teleportPosition(new Vector3d(lx, safeHideY(x18tf), lz));
        ai.setCurrentState(X18State.HIDDEN);
        ai.setActionTimerTicks(0);
        ai.setDamageDone(false);
        // Very long cooldown - effectively disabled until next day
        // 72000 ticks = 1 hour. resetDailyEvent() clears this on day transition.
        ai.setSpawnCooldownTicks(72000);
        ai.setDeepCaveEventFiredToday(true);
        log(Level.INFO, "[AI] X_18 shut down for the rest of the day (deep cave event complete).");
    }

    /**
     * Scans a 5-block radius around the player for deep cave indicator blocks:
     * - Rock* (Rock_Stone, Rock_Cobble, Rock_Volcanic, Rock_Basalt, etc.)
     * - Ore* (Ore_Gold, Ore_Iron, etc.)
     * - Lava, Lava_Source
     *
     * Uses the official Hytale block type IDs from the game's asset files.
     * Returns true if at least DEEP_CAVE_BLOCK_THRESHOLD qualifying blocks
     * are found, confirming the player is in a real deep cave, not an open pit.
     */
    private boolean isDeepCaveEnvironment(World world, TransformComponent playerTf) {
        int px = (int) Math.floor(playerTf.getPosition().x());
        int py = (int) Math.floor(playerTf.getPosition().y());
        int pz = (int) Math.floor(playerTf.getPosition().z());

        int caveBlockCount = 0;
        int scanRadius = 5;

        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    String id = normalizeId(safeBlock(world, px + dx, py + dy, pz + dz));
                    if (isDeepCaveBlock(id)) {
                        caveBlockCount++;
                        if (caveBlockCount >= X18AIComponent.DEEP_CAVE_BLOCK_THRESHOLD) {
                            return true; // early exit - confirmed
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns true if the block ID matches deep cave indicator types:
     * Rock* (stone, cobble, volcanic, basalt), Ore*, Lava/Lava_Source.
     * These match the official block set definitions from the game assets.
     */
    private boolean isDeepCaveBlock(String id) {
        if (id.isEmpty())
            return false;
        // Rock* - covers Rock_Stone, Rock_Stone_Cobble, Rock_Volcanic, Rock_Basalt,
        // Rock_Quartzite, etc. (from Stone.json / Volcanic.json / Rock.json)
        if (id.startsWith("rock"))
            return true;
        // Ore* - covers Ore_Gold, Ore_Iron, etc. (from Ores.json)
        if (id.startsWith("ore"))
            return true;
        // Lava / Lava_Source (from Fluids/Lava.json, Fluids/Lava_Source.json)
        if (id.equals("lava") || id.equals("lava_source"))
            return true;
        // Additional cave indicators
        if (id.contains("basalt") || id.contains("volcanic"))
            return true;
        return false;
    }

    // =========================================================================
    // HELPERS - state transitions
    // =========================================================================

    /** Transition to VANISHING state (actual hide happens next tick). */
    private void scheduleVanish(X18AIComponent ai) {
        lookExposure = 0;
        lookExposureLurk = 0;
        ai.setDespawnAnimStarted(false);
        ai.setCurrentState(X18State.VANISHING);
        ai.setActionTimerTicks(0);
    }

    /**
     * Like scheduleVanish but sets the post-charge flags so the X_18 returns
     * aggressively: shorter cooldown + stillness bypass.
     */
    private void scheduleVanishPostCharge(X18AIComponent ai) {
        lookExposure = 0;
        lookExposureLurk = 0;
        ai.setDespawnAnimStarted(false);
        ai.setCurrentState(X18State.VANISHING);
        ai.setActionTimerTicks(0);
        // Mark for aggressive re-appearance
        ai.setIgnoreStillnessOnce(true);
    }

    /**
     * v0.3.7 FIX: transition into VANISHING (playing the Despawn animation and
     * its render buffer, same as the normal stalk/charge path) before finally
     * shutting X_18 down for the rest of the day.
     *
     * Previously the deep-cave charge/grab timeout and contact paths called
     * shutdownForDay() directly, which teleports the entity underground on the
     * spot with no animation at all - the Despawn animation was never invoked
     * anywhere in that path. Routing through VANISHING first means
     * tickVanishing() plays "Despawn" and waits its usual 2-tick buffer, then
     * (because pendingDeepCaveShutdown is set) finishes with shutdownForDay()
     * instead of the short-cooldown hideUnderground().
     */
    private void beginDeepCaveDespawn(X18AIComponent ai) {
        lookExposure = 0;
        lookExposureLurk = 0;
        ai.setDespawnAnimStarted(false);
        ai.setPendingDeepCaveShutdown(true);
        ai.setCurrentState(X18State.VANISHING);
        ai.setActionTimerTicks(0);
    }

    /**
     * Returns the Y to teleport X_18 to when hiding. Normally this is just
     * POOL_HIDE_Y, but if X_18's current Y is already close to or below that
     * (possible in deep, low cave sections - see v0.3.7d FIX comment near
     * POOL_HIDE_Y), it clamps to current Y - HIDE_Y_MARGIN instead so the
     * teleport always moves down, never up. WORLD_FLOOR_SAFETY_Y prevents
     * going below the world floor in extreme cases.
     */
    private double safeHideY(TransformComponent x18tf) {
        double currentY = x18tf.getPosition().y();
        double hideY = Math.min(POOL_HIDE_Y, currentY - HIDE_Y_MARGIN);
        return Math.max(hideY, WORLD_FLOOR_SAFETY_Y);
    }

    /** Teleport underground and start the post-appearance cooldown. */
    private void hideUnderground(X18AIComponent ai, TransformComponent x18tf) {
        double lx = ai.getLastKnownPlayerX();
        double lz = ai.getLastKnownPlayerZ();

        // Fallback if lastKnown was never set (shouldn't happen after first stalk)
        if (lx == 0.0 && lz == 0.0) {
            Vector3d cur = x18tf.getPosition();
            lx = cur.x();
            lz = cur.z();
        }

        x18tf.teleportPosition(new Vector3d(lx, safeHideY(x18tf), lz));
        ai.setCurrentState(X18State.HIDDEN);
        ai.setActionTimerTicks(0);
        ai.setDamageDone(false);

        // Post-charge: use shorter cooldown so X_18 returns faster
        int cooldown = ai.isIgnoreStillnessOnce()
                ? X18AIComponent.POST_CHARGE_COOLDOWN
                : X18AIComponent.POST_APPEARANCE_COOLDOWN;
        ai.setSpawnCooldownTicks(cooldown);
        log(Level.INFO, "[AI] Hidden. Next appearance in "
                + cooldown + "t (" + (cooldown / 20) + "s)"
                + (ai.isIgnoreStillnessOnce() ? " [post-charge aggressive]" : "") + ".");
    }

    // =========================================================================
    // POSITION SEARCH - STALK (close, 7-11 blocks, behind player)
    //
    // Para cada candidato horizontal faz scan vertical +/-8 blocos em volta
    // do Y do player - cobre a maioria das morfologias de caverna.
    // Tres tiers garantem que sempre encontra posicao em caverna valida.
    //
    // v0.3.5: Added hasCaveChamber() check to avoid spawns in tiny crevices
    // or inside walls. Also validates the position is actually in a cave-like
    // environment (at least 3x3x2 passable space).
    // =========================================================================

    private Vector3d findSpawnPosition(World world, TransformComponent playerTf) {
        Vector3d playerPos = playerTf.getPosition();
        double playerYaw = playerTf.getRotation().yaw();
        double midDist = (MIN_SPAWN_DIST + MAX_SPAWN_DIST) / 2.0;

        Vector3d t1Best = null;
        int t1Score = Integer.MIN_VALUE;
        Vector3d t2Best = null;
        int t2Score = Integer.MIN_VALUE;
        Vector3d t3Best = null;
        int t3Score = Integer.MIN_VALUE;

        for (int i = 0; i < 72; i++) {
            double angle;
            if (i < 48) {
                angle = playerYaw + Math.PI + randomRange(-2.0, 2.0); // bias behind
            } else {
                angle = rng.nextDouble() * Math.PI * 2.0; // 360 deg fallback
            }
            double dist = randomRange(MIN_SPAWN_DIST, MAX_SPAWN_DIST);

            double cx = playerPos.x() + Math.sin(angle) * dist;
            double cz = playerPos.z() + Math.cos(angle) * dist;

            // Scan vertical +/-8 blocks around the player's Y
            Vector3d c = findFloorAt(world, cx, playerPos.y(), cz, 8);
            if (c == null)
                continue;

            // Must have enough room to stand (cave chamber check)
            if (!hasCaveChamber(world, c))
                continue;

            boolean outOfFov = !isPlayerWatching(playerTf, c);
            int cave = scoreCaveWall(world, c);
            int fovBonus = fovScore(playerTf, c);

            if (outOfFov && cave > 0) {
                int s = cave + fovBonus - (int) (Math.abs(dist - midDist) * 3);
                if (s > t1Score) {
                    t1Score = s;
                    t1Best = c;
                }
            }
            if (outOfFov) {
                int s = fovBonus - (int) (Math.abs(dist - midDist) * 2);
                if (s > t2Score) {
                    t2Score = s;
                    t2Best = c;
                }
            }
            {
                int s = 100 - (int) (Math.abs(dist - midDist) * 4);
                if (s > t3Score) {
                    t3Score = s;
                    t3Best = c;
                }
            }
        }

        if (t1Best != null) {
            log(Level.INFO, "[AI] STALK tier1 @ " + fmt(t1Best));
            return t1Best;
        }
        if (t2Best != null) {
            log(Level.INFO, "[AI] STALK tier2 @ " + fmt(t2Best));
            return t2Best;
        }
        if (t3Best != null) {
            log(Level.INFO, "[AI] STALK tier3 @ " + fmt(t3Best));
            return t3Best;
        }

        log(Level.WARNING, "[AI] STALK search null - no walkable floor.");
        return null;
    }

    // =========================================================================
    // POSITION SEARCH - LURK (same-level cave, 10-18 blocks away)
    //
    // v0.3.5 REWRITE: no longer scans deep below the player. Instead finds
    // positions at the SAME Y level (+/-LURK_Y_TOLERANCE blocks) with a clear
    // line-of-sight to the player through passable blocks.
    //
    // This ensures the lurker is visible in the same cave corridor/chamber
    // as the player, creating genuine dread instead of being invisible
    // underground.
    //
    // Prefers:
    // * Positions with line-of-sight to the player (REQUIRED)
    // * Cave wall adjacency (atmospheric darkness)
    // * Positions in the player's rough forward arc (they look that way)
    // * Greater horizontal distance (more eerie)
    //
    // Returns null if no valid same-level LOS position can be found.
    // =========================================================================

    private Vector3d findLurkPosition(World world, TransformComponent playerTf) {
        Vector3d playerPos = playerTf.getPosition();
        double playerYaw = playerTf.getRotation().yaw();

        Vector3d bestPos = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < 80; i++) {
            // First 40: bias loosely toward the player's forward arc so they
            // naturally see it if they look ahead. Last 40: full 360 deg.
            double angle;
            if (i < 40) {
                angle = playerYaw + randomRange(-1.4, 1.4);
            } else {
                angle = rng.nextDouble() * Math.PI * 2.0;
            }

            double horizDist = randomRange(LURK_MIN_DIST, LURK_MAX_DIST);
            double cx = playerPos.x() + Math.sin(angle) * horizDist;
            double cz = playerPos.z() + Math.cos(angle) * horizDist;

            // Scan +/-LURK_Y_TOLERANCE blocks around the player's Y level
            Vector3d found = findFloorAt(world, cx, playerPos.y(), cz, LURK_Y_TOLERANCE);
            if (found == null)
                continue;

            // Must have enough room (cave chamber)
            if (!hasCaveChamber(world, found))
                continue;

            // CRITICAL: must have line-of-sight to the player
            if (!hasLineOfSight(world, found, playerPos))
                continue;

            // Score: distance + atmosphere + forward arc
            int caveScore = scoreCaveWall(world, found);
            double score = horizDist * 2.0 + caveScore;

            // Bonus: loosely in player's forward arc
            double yawOff = Math.abs(normalizeAngle(
                    Math.atan2(cx - playerPos.x(), cz - playerPos.z()) - playerYaw));
            if (yawOff < 1.2)
                score += 15.0;

            if (score > bestScore) {
                bestScore = score;
                bestPos = found;
            }
        }

        if (bestPos != null) {
            log(Level.INFO, "[AI] Lurk position @ " + fmt(bestPos)
                    + "  dist=" + fmt1(playerPos.distance(bestPos)) + "b from player");
        }
        return bestPos;
    }

    /**
     * Finds a walkable floor at (cx, ?, cz) by scanning +/-yRange blocks around
     * baseY. Tries exact baseY first, then alternates down/up.
     * Returns null if no walkable block is found in range.
     */
    private Vector3d findFloorAt(World world, double cx, double baseY, double cz, int yRange) {
        // Try exact Y first
        Vector3d exact = new Vector3d(cx, baseY, cz);
        if (isWalkable(world, exact))
            return exact;

        // Alternate: scan down first (cave floors are usually below)
        for (int delta = 1; delta <= yRange; delta++) {
            Vector3d down = new Vector3d(cx, baseY - delta, cz);
            if (isWalkable(world, down) && down.y() >= 1.0)
                return down;

            Vector3d up = new Vector3d(cx, baseY + delta, cz);
            if (isWalkable(world, up) && up.y() < CAVE_EXIT_Y)
                return up;
        }
        return null;
    }

    /**
     * Checks the 4 cardinal neighbours at the same Y and Y-1.
     * Returns a positive value if at least one cave-wall block is adjacent.
     */
    private int scoreCaveWall(World world, Vector3d pos) {
        int cx = (int) Math.floor(pos.x());
        int cy = (int) Math.floor(pos.y());
        int cz = (int) Math.floor(pos.z());

        int score = 0;
        int[][] neighbours = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (int[] n : neighbours) {
            // Check the block at shoulder height and knee height
            for (int dy = 0; dy <= 1; dy++) {
                BlockType bt = safeBlock(world, cx + n[0], cy + dy, cz + n[1]);
                String id = normalizeId(bt);
                if (isCaveWall(id))
                    score += 6;
            }
        }
        // Also check ceiling (feels more cave-like if there's rock above)
        BlockType ceil = safeBlock(world, cx, cy + 2, cz);
        if (isCaveWall(normalizeId(ceil)))
            score += 4;

        return score;
    }

    private boolean isCaveWall(String id) {
        return id.contains("stone") || id.contains("ore")
                || id.contains("rock") || id.contains("slate")
                || id.contains("cave") || id.contains("cobble")
                || id.contains("gravel") || id.contains("tuff")
                || id.contains("basalt") || id.contains("andesite")
                || id.contains("diorite");
    }

    /** Score: how far outside the player's FOV is the candidate? */
    private int fovScore(TransformComponent playerTf, Vector3d c) {
        Vector3d p = playerTf.getPosition();
        double yaw = Math.abs(normalizeAngle(
                Math.atan2(c.x() - p.x(), c.z() - p.z()) - playerTf.getRotation().yaw()));
        if (yaw > 2.60)
            return 50;
        if (yaw > 1.80)
            return 30;
        if (yaw > YAW_HALF)
            return 10;
        return -80; // inside FOV - heavy penalty
    }

    // =========================================================================
    // PLAYER DETECTION
    // =========================================================================

    /**
     * Returns true when the player's look direction falls inside a tight
     * yaw+pitch cone centred on the X_18 position.
     */
    private boolean isPlayerWatching(TransformComponent playerTf, Vector3d targetPos) {
        Vector3d p = playerTf.getPosition();
        double dx = targetPos.x() - p.x();
        double dy = targetPos.y() - p.y();
        double dz = targetPos.z() - p.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // X17AISystem.isPlayerWatchingX17 and X17ShadowsSystem.isPlayerWatchingShadow.
        if (dist < 0.001) {
            return false;
        }

        double yawDelta = Math.abs(normalizeAngle(
                Math.atan2(dx, dz) - playerTf.getRotation().yaw()));
        if (yawDelta > YAW_HALF)
            return false;

        double pitchDelta = Math.abs(normalizeAngle(
                Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))
                        - playerTf.getRotation().pitch()));
        return pitchDelta <= PITCH_HALF;
    }

    /**
     * Player is considered "in cave" using hysteresis:
     * - Enters cave when y <= CAVE_ENTER_Y (85)
     * - Exits cave when y > CAVE_EXIT_Y (95)
     * - Between 85 and 95: maintains previous state (no flicker)
     *
     * Also requires standing room (two passable blocks at feet and head).
     */
    private boolean isCavePlayer(World world, TransformComponent tf) {
        double y = tf.getPosition().y();

        // Hysteresis: once confirmed in cave, stay in cave until clearly above
        if (playerCaveConfirmed) {
            if (y > CAVE_EXIT_Y)
                return false; // clearly exited
        } else {
            if (y > CAVE_ENTER_Y)
                return false; // not deep enough to enter
        }

        int bx = (int) Math.floor(tf.getPosition().x());
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(tf.getPosition().z());
        return isPassable(world, bx, by, bz) && isPassable(world, bx, by + 1, bz);
    }

    /**
     * Finds the nearest cave-dwelling player relative to a reference position.
     * Returns null if none.
     *
     * origin) as the distance proxy, which broke the "nearest" semantics on
     * multi-player servers - the X-18 could lock onto a player who is far away
     * in absolute coordinates. Now takes a reference position (typically the
     * X-18 entity's current position) and computes the squared distance from it.
     */
    private TargetData findNearestCavePlayer(World world, Store<EntityStore> store,
            Vector3d referencePos) {
        if (world == null || world.getPlayerRefs() == null) {
            return null;
        }
        TargetData best = null;
        double minDSq = Double.MAX_VALUE;
        for (PlayerRef pr : world.getPlayerRefs()) {
            if (pr == null || pr.getReference() == null) {
                continue;
            }
            if (store.getComponent(pr.getReference(), Player.getComponentType()) == null) {
                continue;
            }
            TransformComponent tf = store.getComponent(pr.getReference(),
                    TransformComponent.getComponentType());
            if (tf == null || !isCavePlayer(world, tf)) {
                continue;
            }
            Vector3d ppos = tf.getPosition();
            // Squared distance from the reference position (avoids sqrt).
            double dx = ppos.x() - referencePos.x();
            double dy = ppos.y() - referencePos.y();
            double dz = ppos.z() - referencePos.z();
            double dSq = dx * dx + dy * dy + dz * dz;
            if (dSq < minDSq) {
                minDSq = dSq;
                best = new TargetData(pr.getReference(), tf);
            }
        }
        return best;
    }

    // =========================================================================
    // SINGLETON GUARD
    //
    // Uses a per-instance birth timestamp instead of an entity index.
    // The first instance to tick in a given world registers itself.
    // After a server reload or player rejoin the old system instance is gone,
    // the new one gets a fresh timestamp and registers without any blocked state.
    // =========================================================================

    private boolean acquireSingleton(World world) {
        if (myBirthTimestamp < 0) {
            myBirthTimestamp = System.nanoTime();
        }

        String name = world.getName();
        if (name == null) {
            // No world name - fall back to identityHashCode so we still have
            // a per-world key (very unlikely path).
            name = "world_" + System.identityHashCode(world);
        }

        Long current = activeBirthByWorld.putIfAbsent(name, myBirthTimestamp);
        if (current == null) {
            // We were the first to register for this world.
            return true;
        }
        // Same world - are we the registered instance?
        return current == myBirthTimestamp;
    }

    // =========================================================================
    // COMBAT
    // =========================================================================

    private void playStatusAnimation(Store<EntityStore> store, Ref<EntityStore> entityRef, String animation) {
        try {
            ActiveAnimationComponent animComp = store.getComponent(
                    entityRef, ActiveAnimationComponent.getComponentType());
            if (animComp == null) {
                animComp = store.ensureAndGetComponent(entityRef, ActiveAnimationComponent.getComponentType());
            }
            if (animComp == null) {
                return;
            }

            String[] active = animComp.getActiveAnimations();
            int statusIndex = AnimationSlot.Status.getValue();
            if (active != null && statusIndex >= 0 && statusIndex < active.length
                    && animation.equals(active[statusIndex])) {
                return;
            }

            animComp.setPlayingAnimation(AnimationSlot.Status, animation);

            // HACK/FIX: The Hytale Server API has a bug in
            // ActiveAnimationComponent.setPlayingAnimation()
            // where it does not set 'isNetworkOutdated = true'. Consequently, the network
            // system
            // never detects that the animation list changed. We force it via reflection.
            try {
                java.lang.reflect.Field field = ActiveAnimationComponent.class.getDeclaredField("isNetworkOutdated");
                field.setAccessible(true);
                field.set(animComp, true);
            } catch (Exception ex) {
                log(Level.WARNING, "[AI] Failed to force isNetworkOutdated via reflection: " + ex.getMessage());
            }
        } catch (Exception e) {
            log(Level.WARNING, "[AI] Animation '" + animation + "' failed: " + e.getMessage());
        }
    }

    private void applyDamage(CommandBuffer<EntityStore> cb,
            Ref<EntityStore> src, Ref<EntityStore> tgt) {
        try {
            Damage d = new Damage(new Damage.EntitySource(src), DamageCause.PHYSICAL, CHARGE_DAMAGE);
            cb.invoke(tgt, d);
        } catch (Exception e) {
            log(Level.WARNING, "[AI] Damage apply failed: " + e.getMessage());
        }
    }

    // =========================================================================
    // MOVEMENT / FACING
    // =========================================================================

    private void moveToward(TransformComponent tf, Vector3d target, double speed) {
        Vector3d pos = tf.getPosition();
        double dx = target.x() - pos.x();
        double dz = target.z() - pos.z();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= 0.01)
            return;
        tf.setPosition(new Vector3d(
                pos.x() + (dx / d) * speed,
                target.y(),
                pos.z() + (dz / d) * speed));
    }

    private void faceToward(TransformComponent tf, Vector3d target) {
        // X17AISystem.faceTarget (which used 0.0 offset). Both now share
        // FacingUtil.rotationToFace as the single source of truth.
        Vector3d pos = tf.getPosition();
        tf.setRotation(
                dev.hytalemod.x17.FacingUtil.rotationToFace(pos, target, Math.PI));
    }

    // =========================================================================
    // BLOCK HELPERS
    // =========================================================================

    /**
     * Walkable = passable at y (feet), passable at y+1 (head), solid at y-1
     * (floor).
     * This is the only hard requirement for X_18 to stand somewhere.
     */
    private boolean isWalkable(World world, Vector3d pos) {
        int x = (int) Math.floor(pos.x());
        int y = (int) Math.floor(pos.y());
        int z = (int) Math.floor(pos.z());
        return isPassable(world, x, y, z)
                && isPassable(world, x, y + 1, z)
                && !isPassable(world, x, y - 1, z); // floor must be solid
    }

    /**
     * Checks if a position has a proper cave chamber: at least a 3x3x2 area
     * of passable blocks centred on the position. Prevents X_18 from spawning
     * inside tiny crevices, single-block gaps, or partially inside walls.
     */
    private boolean hasCaveChamber(World world, Vector3d pos) {
        int cx = (int) Math.floor(pos.x());
        int cy = (int) Math.floor(pos.y());
        int cz = (int) Math.floor(pos.z());
        // Check a 3x2x3 volume (x-1..x+1, y..y+1, z-1..z+1)
        int passableCount = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (isPassable(world, cx + dx, cy + dy, cz + dz)) {
                        passableCount++;
                    }
                }
            }
        }
        // Need at least 14 of 18 blocks passable (allows some wall adjacency)
        return passableCount >= 14;
    }

    /**
     * Line-of-sight check: walks from source to target in 1-block steps and
     * verifies every intermediate block is passable. Uses a simple 3D DDA-like
     * approach stepping at 0.8-block intervals along the line.
     *
     * Checks at both feet-level (y) and head-level (y+1) to ensure the entity
     * is actually visible from the player's eye height.
     */
    private boolean hasLineOfSight(World world, Vector3d from, Vector3d to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0)
            return true;

        double step = 0.8;
        int steps = (int) Math.ceil(dist / step);
        if (steps > 60)
            steps = 60; // cap to prevent excessive iteration

        double sx = dx / steps;
        double sy = dy / steps;
        double sz = dz / steps;

        for (int i = 1; i < steps; i++) {
            double px = from.x() + sx * i;
            double py = from.y() + sy * i;
            double pz = from.z() + sz * i;
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);
            // Check both feet and head level
            if (!isPassable(world, bx, by, bz) && !isPassable(world, bx, by + 1, bz)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPassable(World world, int x, int y, int z) {
        return isPassableId(normalizeId(safeBlock(world, x, y, z)));
    }

    private boolean isPassableId(String id) {
        return id.isEmpty()
                || id.contains("air")
                || id.contains("empty")
                || id.contains("mist");
    }

    private BlockType safeBlock(World world, int x, int y, int z) {
        try {
            return world.getBlockType(x, y, z);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeId(BlockType bt) {
        if (bt == null)
            return "";
        String raw = bt.getId();
        if (raw == null)
            return "";
        String id = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
        if (id.startsWith("*"))
            id = id.substring(1);
        return id.toLowerCase();
    }

    // =========================================================================
    // MATH HELPERS
    // =========================================================================

    private double normalizeAngle(double a) {
        while (a > Math.PI)
            a -= Math.PI * 2;
        while (a < -Math.PI)
            a += Math.PI * 2;
        return a;
    }

    private double randomRange(double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }

    private String fmt(Vector3d p) {
        return String.format("(%.1f,%.1f,%.1f)", p.x(), p.y(), p.z());
    }

    private String fmt1(double v) {
        return String.format("%.1f", v);
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null)
            X17Plugin.getInstance().log(level, "[X18-AI] " + msg);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return X18AIComponent.getComponentType();
    }

    // =========================================================================
    // INNER
    // =========================================================================

    private static final class TargetData {
        final Ref<EntityStore> ref;
        final TransformComponent tf;

        TargetData(Ref<EntityStore> ref, TransformComponent tf) {
            this.ref = ref;
            this.tf = tf;
        }
    }
}