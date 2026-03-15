package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;
import dev.hytalemod.x17.component.X17AIComponent;
import dev.hytalemod.x17.component.X17AIComponent.X17State;

import java.util.Random;
import java.util.logging.Level;

/**
 * X17AISystem - v0.2.4
 *
 * DESIGN: PERSISTENT NIGHT PRESENCE
 * X17 is physically present for the ENTIRE spawn night. It does NOT disappear
 * between cycles — it REPOSITIONS silently (instant teleport to a new point,
 * brief freeze, then resumes watching from a new angle).
 *
 * TRUE_VANISH (full disappearance) only happens after:
 *   - Scare completed           → long cooldown
 *   - RAGE caught player        → RETREAT
 *   - repositionsRemaining = 0  → done for the night
 *
 * REPOSITIONS per night (5-8) is the sole limiter.
 *
 * KEY FIX vs v0.2.4:
 *   beginReposition() previously called commandBuffer.removeEntity() which DESTROYED
 *   the entity and relied on a fragile Java-reflection respawn. The entity was gone
 *   forever whenever that respawn failed. Now reposition is purely a silent teleport —
 *   the entity is NEVER removed mid-night.
 *
 * STATES
 *   DORMANT       - Not yet appeared this night. Single entry.
 *   STALK         - Stationary watch from observation point. Always faces player.
 *   REPOSITION    - Teleported silently to new point. Brief freeze then STALK.
 *   HUNT_APPROACH - Slow walk toward player (55% chance per cycle).
 *   AMBUSH_SCARE  - Freeze in player face + scare sound, then TRUE_VANISH.
 *   CHASE         - RAGE, only via damage. Commitment resets while closing in.
 *   TRUE_VANISH   - Full disappearance. Only scare / retreat / night-end.
 *   RETREAT       - Multi-night cooldown after catching player in RAGE.
 */
public class X17AISystem extends EntityTickingSystem<EntityStore> {

    // ── Night range ───────────────────────────────────────────────────────────
    private static final double NIGHT_START = 0.792;
    private static final double NIGHT_END   = 0.208;

    // ── STALK ─────────────────────────────────────────────────────────────────
    private static final int    APPEARANCE_HOLD_MIN       = 120;
    private static final int    APPEARANCE_HOLD_MAX       = 200;
    private static final int    OBSERVE_DURATION_MIN      = 320;
    private static final int    OBSERVE_DURATION_MAX      = 520;
    private static final int    INITIAL_OBSERVE_RANGE_MIN = 35;
    private static final int    INITIAL_OBSERVE_RANGE_MAX = 55;
    private static final int    RETURN_OBSERVE_RANGE_MIN  = 40;
    private static final int    RETURN_OBSERVE_RANGE_MAX  = 65;
    private static final double PLAYER_FOV_HALF           = 0.40;
    private static final double PLAYER_PITCH_HALF         = 0.55;
    private static final double LOOK_RANGE                = 70.0;
    private static final int    LOOK_EXPOSURE_LIMIT       = 130;

    // ── REPOSITION ────────────────────────────────────────────────────────────
    private static final int REPOSITION_FREEZE_MIN = 60;
    private static final int REPOSITION_FREEZE_MAX = 100;
    private static final int NIGHT_REPOSITIONS_MIN = 5;
    private static final int NIGHT_REPOSITIONS_MAX = 8;

    // ── HUNT_APPROACH ─────────────────────────────────────────────────────────
    private static final int    HUNT_RANGE_MIN      = 12;
    private static final int    HUNT_RANGE_MAX      = 18;
    private static final double HUNT_SPEED          = 0.20;
    private static final double AMBUSH_TRIGGER_DIST = 3.5;
    private static final int    HUNT_COMMIT_MIN     = 180;
    private static final int    HUNT_COMMIT_MAX     = 280;
    private static final double ATTACK_CHANCE       = 0.55;

    // ── AMBUSH_SCARE ──────────────────────────────────────────────────────────
    private static final int AMBUSH_FREEZE_TICKS   = 18;
    private static final int POST_SCARE_VANISH_MIN = 500;
    private static final int POST_SCARE_VANISH_MAX = 800;

    // ── CHASE / RAGE ──────────────────────────────────────────────────────────
    private static final double RAGE_SPEED        = 0.75;
    private static final double RAGE_CATCH_DIST   = 2.2;
    private static final int    RAGE_COMMIT_TICKS = 400;

    // ── TRUE_VANISH end-of-night ──────────────────────────────────────────────
    private static final int END_NIGHT_VANISH_MIN = 300;
    private static final int END_NIGHT_VANISH_MAX = 500;
    private static final int WAITING_RANGE_MIN    = 16;
    private static final int WAITING_RANGE_MAX    = 24;

    // ── RETREAT ───────────────────────────────────────────────────────────────
    private static final int RETREAT_COOLDOWN_TICKS = 2400;

    // ── Singleton guard ───────────────────────────────────────────────────────
    private static volatile int  activeEntityIndex = -1;
    private static volatile long activeWorldHash   = 0L;

    // ── Per-night state ───────────────────────────────────────────────────────
    private int    repositionsRemaining = 0;
    private double lastDistToPlayer     = Double.MAX_VALUE;

    private final Random       rng         = new Random();
    private       X17SoundSystem soundSystem = null;

    public void setSoundSystem(X17SoundSystem soundSystem) {
        this.soundSystem = soundSystem;
    }

    /** Called by X17EventSystem at the start of every spawn night. */
    public void resetNightCycles() {
        repositionsRemaining = randomBetween(NIGHT_REPOSITIONS_MIN, NIGHT_REPOSITIONS_MAX);
        log(Level.INFO, "[AI] Night repositions set to " + repositionsRemaining);
    }

    // =========================================================================
    // MAIN TICK
    // =========================================================================

    @Override
    public void tick(float deltaTime, int index, ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        try {
            X17AIComponent     ai        = chunk.getComponent(index, X17AIComponent.getComponentType());
            TransformComponent x17tf     = chunk.getComponent(index, TransformComponent.getComponentType());
            Ref<EntityStore>   entityRef = chunk.getReferenceTo(index);

            if (ai == null || x17tf == null || entityRef == null || !entityRef.isValid()) return;

            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null) return;

            World world = es.getWorld();
            if (!acquireSingleton(world, entityRef.getIndex(), ai)) return;

            tickPassiveTimers(ai);

            // ── Daytime ───────────────────────────────────────────────────────
            if (isDaytime(store)) {
                if (ai.getCurrentState() != X17State.DORMANT) forceDormant(ai, x17tf, 200);
                return;
            }

            // ── Ghost / silent night ──────────────────────────────────────────
            if (!ai.isSpawnAllowedThisNight()) {
                switch (ai.getCurrentState()) {
                    case DORMANT:     break;
                    case TRUE_VANISH: tickTrueVanish(ai); break;
                    default:          beginTrueVanish(ai, x17tf, 1, 200); break;
                }
                return;
            }

            // ── Waiting for spawn delay / post-scare cooldown ─────────────────
            if (ai.getSpawnCooldownTicks() > 0) {
                if (ai.getCurrentState() == X17State.TRUE_VANISH) tickTrueVanish(ai);
                return;
            }

            // ── No players online ─────────────────────────────────────────────
            TargetData target = selectNearestPlayer(world, store);
            if (target == null) {
                if (ai.getCurrentState() != X17State.DORMANT) forceDormant(ai, x17tf, 80);
                return;
            }

            rememberTarget(ai, target.transform);

            // ── State machine ─────────────────────────────────────────────────
            switch (ai.getCurrentState()) {
                case DORMANT:
                    enterStalk(ai, x17tf, world, target);
                    break;
                case STALK:
                    tickStalk(ai, x17tf, world, target);
                    break;
                case REPOSITION:
                    tickReposition(ai, x17tf, target);
                    break;
                case HUNT_APPROACH:
                    tickHuntApproach(ai, x17tf, target);
                    break;
                case AMBUSH_SCARE:
                    tickAmbushScare(ai, x17tf, target);
                    break;
                case CHASE:
                    tickRageChase(ai, x17tf, target);
                    break;
                case TRUE_VANISH:
                    // spawnCooldown cleared — enterStalk guards repositionsRemaining.
                    ai.setCurrentState(X17State.DORMANT);
                    enterStalk(ai, x17tf, world, target);
                    break;
                case RETREAT:
                    ai.setCurrentState(X17State.DORMANT);
                    break;
                default:
                    ai.setCurrentState(X17State.DORMANT);
                    break;
            }
        } catch (Exception e) {
            log(Level.SEVERE, "[AI] Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================================
    // STATE: DORMANT → STALK
    // =========================================================================

    private void enterStalk(X17AIComponent ai, TransformComponent x17tf,
            World world, TargetData target) {
        if (repositionsRemaining <= 0) return; // night quota exhausted

        boolean returning = ai.isSpawnCheckDone();
        teleportToObservationPoint(x17tf, world, target.transform, returning);
        ai.setCurrentState(X17State.STALK);
        ai.setSpawnCheckDone(true);
        ai.setAppearanceHoldTicks(randomBetween(APPEARANCE_HOLD_MIN, APPEARANCE_HOLD_MAX));
        ai.setActionCooldownTicks(randomBetween(OBSERVE_DURATION_MIN, OBSERVE_DURATION_MAX));
        ai.setLookExposureTicks(0);
        log(Level.INFO, "[AI] STALK " + (returning ? "reposition" : "initial")
                + " @ " + formatPos(x17tf.getPosition()) + " | repos left: " + repositionsRemaining);
        log(Level.INFO, "[AI] Timers: hold=" + ai.getAppearanceHoldTicks()
                + " ticks | observe=" + ai.getActionCooldownTicks()
                + " ticks | lookLimit=" + LOOK_EXPOSURE_LIMIT
                + " | range=" + (returning ? RETURN_OBSERVE_RANGE_MIN + "-" + RETURN_OBSERVE_RANGE_MAX
                                           : INITIAL_OBSERVE_RANGE_MIN + "-" + INITIAL_OBSERVE_RANGE_MAX));
    }

    // =========================================================================
    // STATE: STALK
    // =========================================================================

    private void tickStalk(X17AIComponent ai, TransformComponent x17tf,
            World world, TargetData target) {

        faceTarget(x17tf, target.transform.getPosition());

        // During the appearance-hold window X17 has just materialised.
        // Don't react to the player yet — wait for the hold to expire.
        if (ai.getAppearanceHoldTicks() > 0) return;

        // Look-exposure only accumulates AFTER the hold window closes.
        if (isPlayerWatchingX17(target.transform, x17tf)) {
            ai.incrementLookExposureTicks();
        } else {
            ai.setLookExposureTicks(Math.max(0, ai.getLookExposureTicks() - 2));
        }

        if (ai.getLookExposureTicks() >= LOOK_EXPOSURE_LIMIT) {
            log(Level.INFO, "[AI] Spotted — repositioning.");
            ai.setLookExposureTicks(0);
            beginReposition(ai, x17tf, world, target, true);
            return;
        }

        if (ai.getActionCooldownTicks() <= 0) {
            if (rng.nextDouble() < ATTACK_CHANCE) {
                beginHuntApproach(ai, x17tf, target.transform);
            } else {
                beginReposition(ai, x17tf, world, target, false);
            }
        }
    }

    // =========================================================================
    // STATE: REPOSITION — pure silent teleport, entity is NEVER removed
    // =========================================================================

    private void beginReposition(X17AIComponent ai, TransformComponent x17tf,
            World world, TargetData target, boolean wasSeen) {

        repositionsRemaining--;

        if (repositionsRemaining <= 0) {
            repositionsRemaining = 0;
            log(Level.INFO, "[AI] Repositions exhausted — ending night presence.");
            beginTrueVanish(ai, x17tf, 1, randomBetween(END_NIGHT_VANISH_MIN, END_NIGHT_VANISH_MAX));
            return;
        }

        // Silent teleport — entity stays alive, no Java respawn needed.
        teleportToObservationPoint(x17tf, world, target.transform, true);
        ai.setCurrentState(X17State.REPOSITION);
        ai.setAppearanceHoldTicks(randomBetween(REPOSITION_FREEZE_MIN, REPOSITION_FREEZE_MAX));
        ai.setActionCooldownTicks(randomBetween(OBSERVE_DURATION_MIN, OBSERVE_DURATION_MAX));
        ai.setLookExposureTicks(0);

        log(Level.INFO, "[AI] Reposition (seen=" + wasSeen + ") → "
                + formatPos(x17tf.getPosition()) + " | repos left: " + repositionsRemaining);
    }

    private void tickReposition(X17AIComponent ai, TransformComponent x17tf, TargetData target) {
        if (ai.getAppearanceHoldTicks() > 0) return;
        ai.setCurrentState(X17State.STALK);
        ai.setLookExposureTicks(0);
        faceTarget(x17tf, target.transform.getPosition());
        log(Level.INFO, "[AI] Resumed STALK @ " + formatPos(x17tf.getPosition())
                + " | repos left: " + repositionsRemaining);
    }

    // =========================================================================
    // STATE: HUNT_APPROACH
    // =========================================================================

    private void beginHuntApproach(X17AIComponent ai, TransformComponent x17tf,
            TransformComponent playerTf) {
        teleportToFlankingPosition(x17tf, playerTf);
        ai.setCurrentState(X17State.HUNT_APPROACH);
        ai.setHuntCommitmentTicks(randomBetween(HUNT_COMMIT_MIN, HUNT_COMMIT_MAX));
        ai.setLookExposureTicks(0);
        ai.setActionCooldownTicks(0);
        log(Level.INFO, "[AI] HUNT_APPROACH @ " + formatPos(x17tf.getPosition()));
    }

    private void tickHuntApproach(X17AIComponent ai, TransformComponent x17tf, TargetData target) {
        if (ai.getHuntCommitmentTicks() <= 0) {
            log(Level.INFO, "[AI] Hunt timed out — repositioning.");
            // Timeout: don't end the night, just silently reposition and keep stalking.
            beginReposition(ai, x17tf, null, target, false);
            return;
        }
        double dist = x17tf.getPosition().distanceTo(target.transform.getPosition());
        if (dist <= AMBUSH_TRIGGER_DIST) {
            enterAmbushScare(ai, x17tf, target.transform);
            return;
        }
        faceTarget(x17tf, target.transform.getPosition());
        moveTowards(x17tf, target.transform.getPosition(), HUNT_SPEED);
    }

    // =========================================================================
    // STATE: AMBUSH_SCARE
    // =========================================================================

    private void enterAmbushScare(X17AIComponent ai, TransformComponent x17tf,
            TransformComponent playerTf) {
        faceTarget(x17tf, playerTf.getPosition());
        ai.setCurrentState(X17State.AMBUSH_SCARE);
        ai.setAppearanceHoldTicks(AMBUSH_FREEZE_TICKS);
        ai.setActionCooldownTicks(AMBUSH_FREEZE_TICKS);
        ai.setHuntCommitmentTicks(0);
        if (soundSystem != null) soundSystem.notifyAmbushScare(x17tf.getPosition());
        log(Level.INFO, "[AI] *** AMBUSH SCARE ***");
    }

    private void tickAmbushScare(X17AIComponent ai, TransformComponent x17tf, TargetData target) {
        if (ai.getActionCooldownTicks() > 0) {
            faceTarget(x17tf, target.transform.getPosition());
            return;
        }
        log(Level.INFO, "[AI] Scare done → TRUE_VANISH.");
        beginTrueVanish(ai, x17tf, 1, randomBetween(POST_SCARE_VANISH_MIN, POST_SCARE_VANISH_MAX));
    }

    // =========================================================================
    // STATE: CHASE (RAGE) — sole entry via X17DamageSystem
    // =========================================================================

    public void onX17HitByPlayer(X17AIComponent ai, TransformComponent x17tf,
            @javax.annotation.Nullable TransformComponent playerTf) {
        if (ai.getCurrentState() == X17State.RETREAT) return;

        if (ai.getHitWindowTicks() <= 0) ai.resetCombatHitCount();
        ai.setHitWindowTicks(X17AIComponent.HIT_WINDOW_TICKS);
        ai.incrementCombatHitCount();

        if (ai.getCombatHitCount() >= X17AIComponent.ESCAPE_HIT_THRESHOLD) {
            ai.resetCombatHitCount();
            ai.setHitWindowTicks(0);
            ai.setFledFromCombat(true);
            log(Level.INFO, "[AI] Combat escape after hits.");
            beginTrueVanish(ai, x17tf, 1, X17AIComponent.COMBAT_ESCAPE_COOLDOWN);
            return;
        }

        if (ai.getCurrentState() != X17State.CHASE && soundSystem != null) {
            soundSystem.notifyRageActivated();
        }
        ai.setCurrentState(X17State.CHASE);
        ai.setHuntCommitmentTicks(RAGE_COMMIT_TICKS);
        ai.setLookExposureTicks(0);
        ai.setAppearanceHoldTicks(0);
        lastDistToPlayer = Double.MAX_VALUE;
        log(Level.INFO, "[AI] *** RAGE ***");
    }

    private void tickRageChase(X17AIComponent ai, TransformComponent x17tf, TargetData target) {
        double dist = x17tf.getPosition().distanceTo(target.transform.getPosition());
        if (dist <= RAGE_CATCH_DIST) {
            log(Level.INFO, "[AI] Caught player → RETREAT.");
            enterRetreat(ai, x17tf, target.transform.getPosition());
            return;
        }
        faceTarget(x17tf, target.transform.getPosition());
        moveTowards(x17tf, target.transform.getPosition(), RAGE_SPEED);
        double distAfter = x17tf.getPosition().distanceTo(target.transform.getPosition());
        if (distAfter < lastDistToPlayer - 0.05) ai.setHuntCommitmentTicks(RAGE_COMMIT_TICKS);
        if (ai.getHuntCommitmentTicks() <= 0) {
            log(Level.INFO, "[AI] Rage blocked → RETREAT.");
            enterRetreat(ai, x17tf, target.transform.getPosition());
        }
        lastDistToPlayer = distAfter;
    }

    // =========================================================================
    // STATE: TRUE_VANISH / RETREAT
    // =========================================================================

    private void beginTrueVanish(X17AIComponent ai, TransformComponent x17tf,
            int vanishTimerTicks, int spawnCooldownTicks) {
        ai.setCurrentState(X17State.TRUE_VANISH);
        ai.setVanishTimerTicks(vanishTimerTicks);
        ai.setSpawnCooldownTicks(spawnCooldownTicks);
        ai.setHuntCommitmentTicks(0);
        ai.setLookExposureTicks(0);
        ai.setAppearanceHoldTicks(0);
        ai.setActionCooldownTicks(0);
        teleportToWaitingPoint(x17tf,
                new Vector3d(ai.getLastKnownPlayerX(), ai.getLastKnownPlayerY(), ai.getLastKnownPlayerZ()));
        if (soundSystem != null) soundSystem.notifyRageDeactivated();
        log(Level.INFO, "[AI] TRUE_VANISH cooldown=" + spawnCooldownTicks + " ticks.");
    }

    private void tickTrueVanish(X17AIComponent ai) {
        if (ai.getVanishTimerTicks() <= 0) ai.setCurrentState(X17State.DORMANT);
    }

    private void enterRetreat(X17AIComponent ai, TransformComponent x17tf, Vector3d awayFrom) {
        if (soundSystem != null) soundSystem.notifyRageDeactivated();
        double angle = rng.nextDouble() * Math.PI * 2.0;
        x17tf.teleportPosition(new Vector3d(
                awayFrom.getX() + Math.cos(angle) * 60.0,
                awayFrom.getY(),
                awayFrom.getZ() + Math.sin(angle) * 60.0));
        ai.setCurrentState(X17State.RETREAT);
        ai.setSpawnCooldownTicks(RETREAT_COOLDOWN_TICKS);
        ai.setHuntCommitmentTicks(0);
        ai.setLookExposureTicks(0);
        ai.setAppearanceHoldTicks(0);
        ai.resetCombatHitCount();
        ai.setHitWindowTicks(0);
        log(Level.INFO, "[AI] RETREAT cooldown=" + RETREAT_COOLDOWN_TICKS + " ticks.");
    }

    private void forceDormant(X17AIComponent ai, TransformComponent x17tf, int cooldownTicks) {
        ai.setCurrentState(X17State.DORMANT);
        ai.setVanishTimerTicks(0);
        ai.setAppearanceHoldTicks(0);
        ai.setLookExposureTicks(0);
        ai.setHuntCommitmentTicks(0);
        ai.setSpawnCooldownTicks(Math.max(ai.getSpawnCooldownTicks(), cooldownTicks));
        if (soundSystem != null) soundSystem.notifyRageDeactivated();
    }

    // =========================================================================
    // PASSIVE TIMERS
    // =========================================================================

    private void tickPassiveTimers(X17AIComponent ai) {
        ai.decrementSpawnCooldown();
        ai.decrementVanishTimer();
        ai.decrementHitWindow();
        ai.decrementActionCooldown();
        ai.decrementRepositionCooldown();
        ai.decrementHuntCommitment();
        ai.decrementAppearanceHold();

        X17State state = ai.getCurrentState();
        if (state != X17State.DORMANT && state != X17State.TRUE_VANISH && state != X17State.RETREAT) {
            ai.decrementNightPresenceBudget();
        }
        if (state != X17State.STALK) {
            ai.setLookExposureTicks(0);
        }
    }

    // =========================================================================
    // SINGLETON GUARD
    // =========================================================================

    private boolean acquireSingleton(World world, int entityIndex, X17AIComponent ai) {
        long hash = (long) world.getName().hashCode();
        if (activeWorldHash != hash || activeEntityIndex < 0) {
            activeWorldHash   = hash;
            activeEntityIndex = entityIndex;
            return true;
        }
        if (activeEntityIndex == entityIndex) return true;
        ai.setCurrentState(X17State.TRUE_VANISH);
        ai.setVanishTimerTicks(1);
        return false;
    }

    // =========================================================================
    // TELEPORTATION
    // =========================================================================

    private void teleportToObservationPoint(TransformComponent x17tf, World world,
            TransformComponent playerTf, boolean returning) {
        Vector3d playerPos = playerTf.getPosition();
        double   playerYaw = playerTf.getRotation().getYaw();
        int      minRange  = returning ? RETURN_OBSERVE_RANGE_MIN : INITIAL_OBSERVE_RANGE_MIN;
        int      maxRange  = returning ? RETURN_OBSERVE_RANGE_MAX : INITIAL_OBSERVE_RANGE_MAX;
        double   preferred = returning ? 52.0 : 45.0;
        double   center    = playerYaw + Math.PI;
        double   spread    = returning ? 2.6 : 2.2;

        Vector3d best      = null;
        int      bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < 28; i++) {
            double angle    = center + randomRange(-spread / 2.0, spread / 2.0);
            double distance = randomBetween(minRange, maxRange);
            Vector3d c = new Vector3d(
                    playerPos.getX() + Math.sin(angle) * distance,
                    playerPos.getY(),
                    playerPos.getZ() + Math.cos(angle) * distance);
            int score = scoreObservationPoint(world, c)
                      + scoreViewConcealment(playerPos, playerYaw, c)
                      - (int) Math.round(Math.abs(distance - preferred) * 2.0);
            if (score > bestScore) { bestScore = score; best = c; }
        }

        if (best == null) best = new Vector3d(
                playerPos.getX() + minRange, playerPos.getY(), playerPos.getZ() + minRange);

        x17tf.teleportPosition(best);
        faceTarget(x17tf, playerPos);
    }

    private int scoreObservationPoint(World world, Vector3d c) {
        if (world == null) return 0;
        int cx = (int) Math.floor(c.getX()), cy = (int) Math.floor(c.getY()), cz = (int) Math.floor(c.getZ());
        int score = 0, trees = 0;
        for (int x = -2; x <= 2; x++) for (int y = -1; y <= 3; y++) for (int z = -2; z <= 2; z++) {
            try {
                BlockType bt = world.getBlockType(cx+x, cy+y, cz+z);
                if (bt == null) continue;
                String id = normalizeBlockId(bt.getId());
                if (id.contains("leaves")||id.contains("log")||id.contains("tree")
                        ||id.contains("bark")||id.contains("wood")) { score += 10; trees++; }
                else if (id.contains("grass")||id.contains("dirt")||id.contains("stone")) score++;
            } catch (Exception ignored) {}
        }
        if (trees == 0) score -= 45;
        else score += Math.min(12, trees * 2);
        return score;
    }

    private int scoreViewConcealment(Vector3d playerPos, double playerYaw, Vector3d candidate) {
        double yawDelta = Math.abs(normalizeAngle(
                Math.atan2(candidate.getX()-playerPos.getX(), candidate.getZ()-playerPos.getZ()) - playerYaw));
        // Strongly prefer positions behind the player (yawDelta close to PI = directly behind).
        if (yawDelta >= 2.45) return 50;   // behind — ideal
        if (yawDelta >= 1.85) return 28;   // side-rear
        if (yawDelta >= 1.25) return 8;    // side
        if (yawDelta >= 0.70) return -20;  // side-front
        return -45;                         // directly in front — never pick this
    }

    private void teleportToFlankingPosition(TransformComponent x17tf, TransformComponent playerTf) {
        Vector3d playerPos = playerTf.getPosition();
        double   angle     = playerTf.getRotation().getYaw() + randomRange(-0.8, 0.8);
        double   distance  = randomBetween(HUNT_RANGE_MIN, HUNT_RANGE_MAX);
        x17tf.teleportPosition(new Vector3d(
                playerPos.getX() + Math.sin(angle) * distance,
                playerPos.getY(),
                playerPos.getZ() + Math.cos(angle) * distance));
        faceTarget(x17tf, playerPos);
    }

    private void teleportToWaitingPoint(TransformComponent x17tf, Vector3d playerPos) {
        double angle = rng.nextDouble() * Math.PI * 2.0;
        x17tf.teleportPosition(new Vector3d(
                playerPos.getX() + Math.cos(angle) * randomBetween(WAITING_RANGE_MIN, WAITING_RANGE_MAX),
                playerPos.getY(),
                playerPos.getZ() + Math.sin(angle) * randomBetween(WAITING_RANGE_MIN, WAITING_RANGE_MAX)));
    }

    // =========================================================================
    // MOVEMENT + LOOK DETECTION + UTILITIES
    // =========================================================================

    private void moveTowards(TransformComponent x17tf, Vector3d target, double speed) {
        Vector3d pos = x17tf.getPosition();
        double dx = target.getX()-pos.getX(), dz = target.getZ()-pos.getZ();
        double dist = Math.sqrt(dx*dx + dz*dz);
        if (dist <= 0.01) return;
        x17tf.setPosition(new Vector3d(pos.getX()+(dx/dist)*speed, target.getY(), pos.getZ()+(dz/dist)*speed));
    }

    private void faceTarget(TransformComponent x17tf, Vector3d target) {
        Vector3d pos = x17tf.getPosition();
        x17tf.setRotation(new Vector3f(0f,
                (float) Math.atan2(target.getX()-pos.getX(), target.getZ()-pos.getZ()), 0f));
    }

    private boolean isPlayerWatchingX17(TransformComponent playerTf, TransformComponent x17tf) {
        Vector3d pPos = playerTf.getPosition(), xPos = x17tf.getPosition();
        double dx = xPos.getX()-pPos.getX(), dy = xPos.getY()-pPos.getY(), dz = xPos.getZ()-pPos.getZ();
        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dist < 0.001 || dist > LOOK_RANGE) return false;
        if (Math.abs(normalizeAngle(Math.atan2(dx,dz) - playerTf.getRotation().getYaw())) > PLAYER_FOV_HALF) return false;
        return Math.abs(normalizeAngle(Math.atan2(dy, Math.sqrt(dx*dx+dz*dz))
                - playerTf.getRotation().getX())) <= PLAYER_PITCH_HALF;
    }

    @SuppressWarnings("deprecation")
    private TargetData selectNearestPlayer(World world, Store<EntityStore> store) {
        if (world.getPlayers() == null) return null;
        for (Player p : world.getPlayers()) {
            if (p == null || p.getReference() == null) continue;
            TransformComponent tf = store.getComponent(p.getReference(), TransformComponent.getComponentType());
            if (tf != null) return new TargetData(p, tf, 0.0);
        }
        return null;
    }

    private void rememberTarget(X17AIComponent ai, TransformComponent playerTf) {
        Vector3d pos = playerTf.getPosition();
        ai.setLastKnownPlayerPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean isDaytime(Store<EntityStore> store) {
        try { return !store.getResource(WorldTimeResource.getResourceType())
                .isDayTimeWithinRange(NIGHT_START, NIGHT_END); }
        catch (Exception e) { return false; }
    }

    private double normalizeAngle(double a) {
        while (a >  Math.PI) a -= Math.PI * 2;
        while (a < -Math.PI) a += Math.PI * 2;
        return a;
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return min;
        return min + rng.nextInt((max - min) + 1);
    }

    private double randomRange(double min, double max) { return min + rng.nextDouble() * (max - min); }

    private String normalizeBlockId(String raw) {
        if (raw == null) return "";
        String id = raw.contains(":") ? raw.substring(raw.lastIndexOf(':')+1) : raw;
        if (id.startsWith("*")) id = id.substring(1);
        return id.toLowerCase();
    }

    private String formatPos(Vector3d pos) {
        return String.format("(%.1f,%.1f,%.1f)", pos.getX(), pos.getY(), pos.getZ());
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null) X17Plugin.getInstance().log(level, "[X17-AI] " + msg);
    }

    @Override
    public Query<EntityStore> getQuery() { return X17AIComponent.getComponentType(); }

    private static final class TargetData {
        final Player player; final TransformComponent transform; final double distance;
        TargetData(Player p, TransformComponent tf, double d) { player=p; transform=tf; distance=d; }
    }
}
