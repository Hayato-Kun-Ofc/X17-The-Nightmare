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

import dev.hytalemod.x17.scheduler.X17NightScheduler;
import java.util.Random;
import java.util.logging.Level;

/**
 * X17AISystem - v0.2.5
 *
 * DESIGN PHILOSOPHY
 * X17 is not a pathfinding NPC. It is a directed horror experience.
 * Every decision is weighted toward psychological impact, not raw efficiency.
 * It should feel like it is *watching*, not chasing.
 *
 * KEY DESIGN RULES
 * 1. X17 never walks toward the player during STALK. Locomotion = teleport.
 * 2. Unpredictability via weighted randomness — same situation never plays out
 * twice.
 * 3. HUNT_APPROACH is a slow, creeping advance. Not a charge.
 * 4. CHASE (RAGE) triggers ONLY via player-initiated damage, never
 * spontaneously.
 * 5. Night personality (CAUTIOUS / BOLD / ERRATIC) is rolled once per night and
 * biases every decision throughout that night.
 *
 * STATE MACHINE
 * DORMANT — Waiting. Entered at night-start or after cooldowns.
 * STALK — Stationary observation. Always faces player. Core horror state.
 * REPOSITION — Silent teleport to new vantage. Brief freeze, then STALK.
 * HUNT_APPROACH — Slow creep toward player. Escalates to AMBUSH_SCARE.
 * AMBUSH_SCARE — Freeze in player's face. Then TRUE_VANISH.
 * CHASE — RAGE mode. Fast pursuit. Entry ONLY via X17DamageSystem.
 * TRUE_VANISH — Full disappearance. Cooldown before return.
 * RETREAT — Multi-night cooldown after catching player in RAGE.
 *
 * NIGHT PERSONALITIES
 * CAUTIOUS — Longer stalk durations, prefers repositioning over hunting,
 * high look-exposure tolerance (flees earlier when spotted).
 * BOLD — Shorter observe windows, hunts frequently, tolerates being seen.
 * ERRATIC — Randomised durations, wildly unpredictable action choices.
 */
public class X17AISystem extends EntityTickingSystem<EntityStore> {

    // ── Night time range ──────────────────────────────────────────────────────
    private static final double NIGHT_START = 0.792;
    private static final double NIGHT_END = 0.208;

    // ── Night personality ─────────────────────────────────────────────────────
    private enum NightPersonality {
        CAUTIOUS, BOLD, ERRATIC
    }

    // ── STALK base timers (ticks) ─────────────────────────────────────────────
    private static final int APPEARANCE_HOLD_MIN = 80;
    private static final int APPEARANCE_HOLD_MAX = 160;
    private static final int OBSERVE_MIN_BASE = 260;
    private static final int OBSERVE_MAX_BASE = 480;

    // ── Observation range (blocks) ────────────────────────────────────────────
    private static final int INITIAL_OBSERVE_RANGE_MIN = 38;
    private static final int INITIAL_OBSERVE_RANGE_MAX = 58;
    private static final int RETURN_OBSERVE_RANGE_MIN = 42;
    private static final int RETURN_OBSERVE_RANGE_MAX = 68;

    // ── Look exposure ─────────────────────────────────────────────────────────
    private static final double PLAYER_FOV_HALF = 0.38;
    private static final double PLAYER_PITCH_HALF = 0.52;
    private static final double LOOK_RANGE = 72.0;
    private static final int LOOK_EXPOSURE_LIMIT_BASE = 100;

    // ── REPOSITION ────────────────────────────────────────────────────────────
    private static final int REPOSITION_FREEZE_MIN = 50;
    private static final int REPOSITION_FREEZE_MAX = 90;
    private static final int NIGHT_REPOSITIONS_MIN = 5;
    private static final int NIGHT_REPOSITIONS_MAX = 9;

    // ── HUNT_APPROACH ─────────────────────────────────────────────────────────
    private static final int HUNT_RANGE_MIN = 14;
    private static final int HUNT_RANGE_MAX = 20;
    private static final double HUNT_SPEED_CREEP = 0.12; // blocks/tick: slow, atmospheric
    private static final double HUNT_SPEED_CLOSE = 0.18; // faster when within close range
    private static final double HUNT_CLOSE_THRESHOLD = 12.0;
    private static final double AMBUSH_TRIGGER_DIST = 3.2;
    private static final int HUNT_COMMIT_MIN = 160;
    private static final int HUNT_COMMIT_MAX = 260;

    // ── Decision weights ──────────────────────────────────────────────────────
    private static final double BASE_HUNT_CHANCE = 0.45;
    private static final double BASE_ABORT_HUNT_CHANCE = 0.15;

    // ── AMBUSH_SCARE ──────────────────────────────────────────────────────────
    private static final int AMBUSH_FREEZE_TICKS = 22;
    private static final int POST_SCARE_VANISH_MIN = 480;
    private static final int POST_SCARE_VANISH_MAX = 780;

    // ── CHASE / RAGE ──────────────────────────────────────────────────────────
    private static final double RAGE_SPEED = 0.75;
    private static final double RAGE_CATCH_DIST = 2.2;
    private static final int RAGE_COMMIT_TICKS = 400;

    // ── TRUE_VANISH / end-of-night ────────────────────────────────────────────
    private static final int END_NIGHT_VANISH_MIN = 300;
    private static final int END_NIGHT_VANISH_MAX = 500;
    private static final int WAITING_RANGE_MIN = 16;
    private static final int WAITING_RANGE_MAX = 24;

    // ── RETREAT ───────────────────────────────────────────────────────────────
    private static final int RETREAT_COOLDOWN_TICKS = 2400;

    // ── Torch extinguish ──────────────────────────────────────────────────────
    /** Chance to extinguish torches on ghost/silent nights (X17 not present). */
    private static final double TORCH_CHANCE_NORMAL = 0.20;
    /** Chance to extinguish torches on spawn nights (X17 is present). */
    private static final double TORCH_CHANCE_SPAWN = 0.50;

    // ── Item stealing ────────────────────────────────────────────────────────
    /** Chance to attempt item theft on ghost/silent nights. */
    private static final double STEAL_CHANCE_NORMAL = 0.08;
    /** Chance to attempt item theft on spawn nights. */
    private static final double STEAL_CHANCE_GHOST = 0.15;

    // ── Singleton guard ───────────────────────────────────────────────────────
    private static volatile int activeEntityIndex = -1;
    private static volatile long activeWorldHash = 0L;

    // ── Per-night runtime state ───────────────────────────────────────────────
    private int repositionsRemaining = 0;
    private double lastDistToPlayer = Double.MAX_VALUE;
    private NightPersonality personality = NightPersonality.BOLD;
    private boolean torchEnabledThisNight = false;
    private boolean torchesExtinguishedThisNight = false;
    private int torchTickCounter = 0;

    private boolean stealEnabledThisNight = false;
    private boolean stealDoneThisNight = false;
    private int stealTickCounter = 0;

    private X17NightScheduler scheduler = null;

    // Personality-derived values
    private int p_observeMin = OBSERVE_MIN_BASE;
    private int p_observeMax = OBSERVE_MAX_BASE;
    private int p_lookLimit = LOOK_EXPOSURE_LIMIT_BASE;
    private double p_huntChance = BASE_HUNT_CHANCE;
    private double p_abortHuntChance = BASE_ABORT_HUNT_CHANCE;

    private final Random rng = new Random();
    private X17SoundSystem soundSystem = null;
    private X17TorchExtinguishSystem torchSystem = null;
    private X17ItemStealSystem stealSystem = null;

    // =========================================================================
    // WIRING
    // =========================================================================

    public void setSoundSystem(X17SoundSystem soundSystem) {
        this.soundSystem = soundSystem;
    }

    public void setTorchSystem(X17TorchExtinguishSystem torchSystem) {
        this.torchSystem = torchSystem;
    }

    public void setStealSystem(X17ItemStealSystem stealSystem) {
        this.stealSystem = stealSystem;
    }

    public void setScheduler(X17NightScheduler scheduler) {
        this.scheduler = scheduler;
    }

    // =========================================================================
    // NIGHT RESET — called by X17EventSystem at the start of every spawn night
    // =========================================================================

    public void resetNightCycles() {
        repositionsRemaining = randomBetween(NIGHT_REPOSITIONS_MIN, NIGHT_REPOSITIONS_MAX);
        torchesExtinguishedThisNight = false; // guard reset; actual roll happens in resetTorchNight()
        rollNightPersonality();
        log(Level.INFO, "[AI] Night reset | repos=" + repositionsRemaining
                + " | personality=" + personality);
    }

    /**
     * Rolls a personality for the night and derives all tuning values from it.
     * This is the single source of night-to-night variation.
     */
    private void rollNightPersonality() {
        double roll = rng.nextDouble();
        if (roll < 0.35)
            personality = NightPersonality.CAUTIOUS;
        else if (roll < 0.70)
            personality = NightPersonality.BOLD;
        else
            personality = NightPersonality.ERRATIC;

        switch (personality) {
            case CAUTIOUS:
                p_observeMin = 380;
                p_observeMax = 580;
                p_lookLimit = 70; // flees quickly when spotted
                p_huntChance = 0.25;
                p_abortHuntChance = 0.30;
                break;
            case BOLD:
                p_observeMin = 200;
                p_observeMax = 360;
                p_lookLimit = 160; // stands ground even when stared at
                p_huntChance = 0.65;
                p_abortHuntChance = 0.05;
                break;
            case ERRATIC:
                // Every value is independently randomised — no pattern to learn
                p_observeMin = randomBetween(120, 420);
                p_observeMax = p_observeMin + randomBetween(80, 300);
                p_lookLimit = randomBetween(50, 200);
                p_huntChance = 0.15 + rng.nextDouble() * 0.70;
                p_abortHuntChance = rng.nextDouble() * 0.50;
                break;
        }
    }

    /**
     * Called by X17EventSystem every night (SPAWN and non-spawn alike).
     * Rolls the per-night torch decision based on the scheduler decision:
     * SPAWN night -> 50%: X17 is present, lights going out heightens dread.
     * other nights -> 20%: rare ambient effect on ghost/silent nights.
     *
     * Separated from resetNightCycles() because that method is only called
     * on spawn nights — this one must run EVERY night.
     */
    public void resetTorchNight() {
        torchesExtinguishedThisNight = false;
        torchTickCounter = 0;
        boolean isSpawnNight = (scheduler != null) && scheduler.shouldSpawnThisNight();
        double torchChance = isSpawnNight ? TORCH_CHANCE_SPAWN : TORCH_CHANCE_NORMAL;
        torchEnabledThisNight = rng.nextDouble() < torchChance;
        log(Level.INFO, "[Torch] Night rolled: "
                + (isSpawnNight ? "SPAWN" : "non-spawn")
                + " | chance=" + String.format("%.0f%%", torchChance * 100)
                + " | enabled=" + torchEnabledThisNight);

        // Also reset item stealing for the night
        resetStealNight(isSpawnNight);
    }

    private void resetStealNight(boolean isSpawnNight) {
        stealDoneThisNight = false;
        stealTickCounter = 0;
        double stealChance = isSpawnNight ? STEAL_CHANCE_GHOST : STEAL_CHANCE_NORMAL;
        stealEnabledThisNight = rng.nextDouble() < stealChance;
        log(Level.INFO, "[Steal] Night rolled: "
                + (isSpawnNight ? "SPAWN" : "non-spawn")
                + " | chance=" + String.format("%.0f%%", stealChance * 100)
                + " | enabled=" + stealEnabledThisNight);
    }

    // Attempt item theft if conditions are met
    public void updateWorldStealLogic(World world, Store<EntityStore> store) {
        if (!stealEnabledThisNight || stealDoneThisNight || stealSystem == null)
            return;
        stealTickCounter++;
        if (stealTickCounter >= 400) {
            stealTickCounter = 0;
            TargetData target = selectNearestPlayer(world, store);
            if (target != null) {
                boolean stolen = stealSystem.attemptTheft(world, target.transform.getPosition());
                if (stolen) {
                    stealDoneThisNight = true; // one theft per night, never repeat
                    log(Level.INFO, "[AI] Theft complete — done for the night.");
                }
            }
        }
    }

    /**
     * Executes the torch extinguish effect if enabled for tonight.
     * Called every world tick by X17EventSystem.
     */
    public void updateWorldTorchLogic(World world, Store<EntityStore> store) {
        if (torchesExtinguishedThisNight)
            return;

        torchTickCounter++;

        // Extinguish 100 ticks (5s) after night starts, OR if X17 spawns earlier.
        // This ensures ghost nights still get the effect.
        if (torchTickCounter >= 100) {
            torchesExtinguishedThisNight = true;
            if (torchEnabledThisNight && torchSystem != null) {
                TargetData target = selectNearestPlayer(world, store);
                if (target != null && target.transform != null) {
                    log(Level.INFO, "[Torch] Ambient lights out! (Ghost/Initial)");
                    torchSystem.extinguishTorchesAround(world, target.transform.getPosition());
                }
            }
        }
    }

    // =========================================================================
    // MAIN TICK
    // =========================================================================

    @Override
    public void tick(float deltaTime, int index, ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
        try {
            X17AIComponent ai = chunk.getComponent(index, X17AIComponent.getComponentType());
            TransformComponent x17tf = chunk.getComponent(index, TransformComponent.getComponentType());
            Ref<EntityStore> entityRef = chunk.getReferenceTo(index);

            if (ai == null || x17tf == null || entityRef == null || !entityRef.isValid())
                return;

            EntityStore es = (EntityStore) store.getExternalData();
            if (es == null || es.getWorld() == null)
                return;

            World world = es.getWorld();
            if (!acquireSingleton(world, entityRef.getIndex(), ai))
                return;

            tickPassiveTimers(ai);

            // ── Daytime ───────────────────────────────────────────────────────
            if (isDaytime(store)) {
                if (ai.getCurrentState() != X17State.DORMANT)
                    forceDormant(ai, x17tf, 200);
                return;
            }

            // ── Ghost / silent night ──────────────────────────────────────────
            if (!ai.isSpawnAllowedThisNight()) {
                switch (ai.getCurrentState()) {
                    case DORMANT:
                        break;
                    case TRUE_VANISH:
                        tickTrueVanish(ai);
                        break;
                    default:
                        beginTrueVanish(ai, x17tf, 1, 200);
                        break;
                }
                return;
            }

            // ── Waiting for spawn delay / post-scare cooldown ─────────────────
            if (ai.getSpawnCooldownTicks() > 0) {
                if (ai.getCurrentState() == X17State.TRUE_VANISH)
                    tickTrueVanish(ai);
                return;
            }

            // ── No players online ─────────────────────────────────────────────
            TargetData target = selectNearestPlayer(world, store);
            if (target == null) {
                if (ai.getCurrentState() != X17State.DORMANT)
                    forceDormant(ai, x17tf, 80);
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
            log(Level.SEVERE, "[AI] Exception in tick: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // =========================================================================
    // STATE: DORMANT → STALK
    // =========================================================================

    private void enterStalk(X17AIComponent ai, TransformComponent x17tf,
            World world, TargetData target) {

        if (repositionsRemaining <= 0)
            return;

        boolean returning = ai.isSpawnCheckDone();
        teleportToObservationPoint(x17tf, world, target.transform, returning);

        ai.setCurrentState(X17State.STALK);
        ai.setSpawnCheckDone(true);
        ai.setAppearanceHoldTicks(randomBetween(APPEARANCE_HOLD_MIN, APPEARANCE_HOLD_MAX));
        ai.setActionCooldownTicks(randomBetween(p_observeMin, p_observeMax));
        ai.setLookExposureTicks(0);

        log(Level.INFO, "[AI] STALK " + (returning ? "return" : "initial")
                + " [" + personality + "] @ " + formatPos(x17tf.getPosition())
                + " | repos=" + repositionsRemaining
                + " | observe=" + ai.getActionCooldownTicks() + "t"
                + " | lookLimit=" + p_lookLimit);

        // Force extinguish if it hasn't happened yet (X17 spawned before 5s)
        if (!torchesExtinguishedThisNight && torchEnabledThisNight && torchSystem != null) {
            torchesExtinguishedThisNight = true;
            log(Level.INFO, "[Torch] Sudden lights out! (X17 Arrival)");
            torchSystem.extinguishTorchesAround(world, x17tf.getPosition());
        }
    }

    // =========================================================================
    // STATE: STALK
    // =========================================================================

    /**
     * Core horror state. X17 stands still, faces the player, and watches.
     * X17 NEVER moves toward the player here — all locomotion is teleport.
     *
     * Three exits:
     * 1. Player stares at X17 long enough (look exposure) → REPOSITION
     * 2. Observe timer expires → pickStalkAction
     * 3. ERRATIC personality spontaneous reposition
     */
    private void tickStalk(X17AIComponent ai, TransformComponent x17tf,
            World world, TargetData target) {

        faceTarget(x17tf, target.transform.getPosition());

        if (ai.getAppearanceHoldTicks() > 0)
            return;

        // Look-exposure: accumulates while player looks at X17, decays otherwise.
        // Decay is faster for non-BOLD nights (X17 is more skittish).
        if (isPlayerWatchingX17(target.transform, x17tf)) {
            ai.incrementLookExposureTicks();
        } else {
            int decay = (personality == NightPersonality.BOLD) ? 1 : 2;
            ai.setLookExposureTicks(Math.max(0, ai.getLookExposureTicks() - decay));
        }

        if (ai.getLookExposureTicks() >= p_lookLimit) {
            log(Level.INFO, "[AI] Spotted — retreating gaze. [" + personality + "]");
            ai.setLookExposureTicks(0);
            beginReposition(ai, x17tf, world, target, true);
            return;
        }

        // Erratic nights: rare spontaneous reposition for no apparent reason.
        if (personality == NightPersonality.ERRATIC && rng.nextDouble() < 0.0008) {
            log(Level.INFO, "[AI] Erratic impulse — repositioning.");
            beginReposition(ai, x17tf, world, target, false);
            return;
        }

        if (ai.getActionCooldownTicks() <= 0) {
            pickStalkAction(ai, x17tf, world, target);
        }
    }

    /**
     * Decision point at the end of an observe window.
     * Outcomes (in order of roll):
     * 6% — phantom vanish (X17 was never really here)
     * 12% — snap-reposition (costs extra reposition, very short next observe)
     * p_huntChance — HUNT_APPROACH
     * remainder — normal REPOSITION
     */
    private void pickStalkAction(X17AIComponent ai, TransformComponent x17tf,
            World world, TargetData target) {

        double roll = rng.nextDouble();

        // Tiny chance: X17 just vanishes with a short cooldown.
        if (roll < 0.06) {
            log(Level.INFO, "[AI] Chose: phantom vanish. [" + personality + "]");
            beginTrueVanish(ai, x17tf, 1, randomBetween(180, 360));
            return;
        }

        if (roll < 0.06 + p_huntChance) {
            log(Level.INFO, "[AI] Chose: HUNT_APPROACH. [" + personality + "]");
            beginHuntApproach(ai, x17tf, target.transform);
            return;
        }

        // Snap-reposition: uses an extra slot, gives a very short observe window.
        // Creates the illusion that X17 is circling the player rapidly.
        if (rng.nextDouble() < 0.12) {
            repositionsRemaining--;
            if (repositionsRemaining <= 0) {
                repositionsRemaining = 0;
                beginTrueVanish(ai, x17tf, 1, randomBetween(END_NIGHT_VANISH_MIN, END_NIGHT_VANISH_MAX));
                return;
            }
            teleportToObservationPoint(x17tf, world, target.transform, true);
            ai.setActionCooldownTicks(randomBetween(60, 140));
            ai.setAppearanceHoldTicks(randomBetween(20, 40));
            ai.setLookExposureTicks(0);
            log(Level.INFO, "[AI] Chose: snap-reposition → " + formatPos(x17tf.getPosition()));
            return;
        }

        log(Level.INFO, "[AI] Chose: reposition. [" + personality + "]");
        beginReposition(ai, x17tf, world, target, false);
    }

    // =========================================================================
    // STATE: REPOSITION
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

        teleportToObservationPoint(x17tf, world, target.transform, true);
        ai.setCurrentState(X17State.REPOSITION);
        ai.setAppearanceHoldTicks(randomBetween(REPOSITION_FREEZE_MIN, REPOSITION_FREEZE_MAX));
        ai.setActionCooldownTicks(randomBetween(p_observeMin, p_observeMax));
        ai.setLookExposureTicks(0);

        log(Level.INFO, "[AI] REPOSITION (spotted=" + wasSeen + ") → "
                + formatPos(x17tf.getPosition()) + " | repos=" + repositionsRemaining);
    }

    private void tickReposition(X17AIComponent ai, TransformComponent x17tf, TargetData target) {
        if (ai.getAppearanceHoldTicks() > 0)
            return;
        ai.setCurrentState(X17State.STALK);
        ai.setLookExposureTicks(0);
        faceTarget(x17tf, target.transform.getPosition());
        log(Level.INFO, "[AI] Resumed STALK @ " + formatPos(x17tf.getPosition())
                + " | repos=" + repositionsRemaining);
    }

    // =========================================================================
    // STATE: HUNT_APPROACH
    // =========================================================================

    /**
     * Begins a slow creeping approach. X17 teleports to a flanking position
     * (never directly in front), then creeps at HUNT_SPEED_CREEP — barely
     * perceptible, deeply unsettling. Speed increases slightly when close.
     */
    private void beginHuntApproach(X17AIComponent ai, TransformComponent x17tf,
            TransformComponent playerTf) {
        teleportToFlankingPosition(x17tf, playerTf);
        ai.setCurrentState(X17State.HUNT_APPROACH);
        ai.setHuntCommitmentTicks(randomBetween(HUNT_COMMIT_MIN, HUNT_COMMIT_MAX));
        ai.setLookExposureTicks(0);
        ai.setActionCooldownTicks(0);
        log(Level.INFO, "[AI] HUNT_APPROACH @ " + formatPos(x17tf.getPosition())
                + " commit=" + ai.getHuntCommitmentTicks() + "t");
    }

    /**
     * Three exit conditions:
     * 1. Close enough → AMBUSH_SCARE
     * 2. Personality abort roll (every 40t) → REPOSITION
     * 3. Commitment timer expired → REPOSITION
     */
    private void tickHuntApproach(X17AIComponent ai, TransformComponent x17tf, TargetData target) {

        double dist = x17tf.getPosition().distanceTo(target.transform.getPosition());

        if (dist <= AMBUSH_TRIGGER_DIST) {
            enterAmbushScare(ai, x17tf, target.transform);
            return;
        }

        // Periodic abort roll — personality-weighted.
        if (ai.getHuntCommitmentTicks() % 40 == 0 && rng.nextDouble() < p_abortHuntChance) {
            log(Level.INFO, "[AI] Hunt aborted mid-approach. [" + personality + "]");
            beginReposition(ai, x17tf, null, target, false);
            return;
        }

        if (ai.getHuntCommitmentTicks() <= 0) {
            log(Level.INFO, "[AI] Hunt timed out — repositioning.");
            beginReposition(ai, x17tf, null, target, false);
            return;
        }

        double speed = (dist <= HUNT_CLOSE_THRESHOLD) ? HUNT_SPEED_CLOSE : HUNT_SPEED_CREEP;
        faceTarget(x17tf, target.transform.getPosition());
        moveTowards(x17tf, target.transform.getPosition(), speed);
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
        if (soundSystem != null)
            soundSystem.notifyAmbushScare(x17tf.getPosition());
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
    // STATE: CHASE (RAGE) — entry ONLY via X17DamageSystem
    // =========================================================================

    public void onX17HitByPlayer(X17AIComponent ai, TransformComponent x17tf,
            @javax.annotation.Nullable TransformComponent playerTf) {
        if (ai.getCurrentState() == X17State.RETREAT)
            return;

        if (ai.getHitWindowTicks() <= 0)
            ai.resetCombatHitCount();
        ai.setHitWindowTicks(X17AIComponent.HIT_WINDOW_TICKS);
        ai.incrementCombatHitCount();

        if (ai.getCombatHitCount() >= X17AIComponent.ESCAPE_HIT_THRESHOLD) {
            ai.resetCombatHitCount();
            ai.setHitWindowTicks(0);
            ai.setFledFromCombat(true);
            log(Level.INFO, "[AI] Combat escape — too many hits.");
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
        if (distAfter < lastDistToPlayer - 0.05)
            ai.setHuntCommitmentTicks(RAGE_COMMIT_TICKS);
        if (ai.getHuntCommitmentTicks() <= 0) {
            log(Level.INFO, "[AI] Rage blocked — RETREAT.");
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
        if (soundSystem != null)
            soundSystem.notifyRageDeactivated();
        log(Level.INFO, "[AI] TRUE_VANISH cooldown=" + spawnCooldownTicks + "t.");
    }

    private void tickTrueVanish(X17AIComponent ai) {
        if (ai.getVanishTimerTicks() <= 0)
            ai.setCurrentState(X17State.DORMANT);
    }

    private void enterRetreat(X17AIComponent ai, TransformComponent x17tf, Vector3d awayFrom) {
        if (soundSystem != null)
            soundSystem.notifyRageDeactivated();
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
        log(Level.INFO, "[AI] RETREAT cooldown=" + RETREAT_COOLDOWN_TICKS + "t.");
    }

    private void forceDormant(X17AIComponent ai, TransformComponent x17tf, int cooldownTicks) {
        ai.setCurrentState(X17State.DORMANT);
        ai.setVanishTimerTicks(0);
        ai.setAppearanceHoldTicks(0);
        ai.setLookExposureTicks(0);
        ai.setHuntCommitmentTicks(0);
        ai.setSpawnCooldownTicks(Math.max(ai.getSpawnCooldownTicks(), cooldownTicks));
        if (soundSystem != null)
            soundSystem.notifyRageDeactivated();
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
            activeWorldHash = hash;
            activeEntityIndex = entityIndex;
            return true;
        }
        if (activeEntityIndex == entityIndex)
            return true;
        ai.setCurrentState(X17State.TRUE_VANISH);
        ai.setVanishTimerTicks(1);
        return false;
    }

    // =========================================================================
    // TELEPORTATION
    // =========================================================================

    /**
     * Scores 28 candidate positions and picks the best observation point.
     * Rewards: tree/foliage cover, positions behind the player, preferred range.
     * X17 prefers to appear behind the player, in trees — hard to be sure of.
     */
    private void teleportToObservationPoint(TransformComponent x17tf, World world,
            TransformComponent playerTf, boolean returning) {
        Vector3d playerPos = playerTf.getPosition();
        double playerYaw = playerTf.getRotation().getYaw();
        int minRange = returning ? RETURN_OBSERVE_RANGE_MIN : INITIAL_OBSERVE_RANGE_MIN;
        int maxRange = returning ? RETURN_OBSERVE_RANGE_MAX : INITIAL_OBSERVE_RANGE_MAX;
        double preferred = returning ? 54.0 : 46.0;
        double center = playerYaw + Math.PI;
        double spread = returning ? 2.7 : 2.3;

        Vector3d best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < 28; i++) {
            double angle = center + randomRange(-spread / 2.0, spread / 2.0);
            double distance = randomBetween(minRange, maxRange);
            Vector3d c = new Vector3d(
                    playerPos.getX() + Math.sin(angle) * distance,
                    playerPos.getY(),
                    playerPos.getZ() + Math.cos(angle) * distance);
            int score = scoreObservationPoint(world, c)
                    + scoreViewConcealment(playerPos, playerYaw, c)
                    - (int) Math.round(Math.abs(distance - preferred) * 2.0);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null)
            best = new Vector3d(
                    playerPos.getX() + minRange, playerPos.getY(), playerPos.getZ() + minRange);

        x17tf.teleportPosition(best);
        faceTarget(x17tf, playerPos);
    }

    private int scoreObservationPoint(World world, Vector3d c) {
        if (world == null)
            return 0;
        int cx = (int) Math.floor(c.getX());
        int cy = (int) Math.floor(c.getY());
        int cz = (int) Math.floor(c.getZ());
        int score = 0, trees = 0;
        for (int x = -2; x <= 2; x++)
            for (int y = -1; y <= 3; y++)
                for (int z = -2; z <= 2; z++) {
                    try {
                        BlockType bt = world.getBlockType(cx + x, cy + y, cz + z);
                        if (bt == null)
                            continue;
                        String id = normalizeBlockId(bt.getId());
                        if (id.contains("leaves") || id.contains("log") || id.contains("tree")
                                || id.contains("bark") || id.contains("wood")) {
                            score += 10;
                            trees++;
                        } else if (id.contains("grass") || id.contains("dirt") || id.contains("stone"))
                            score++;
                    } catch (Exception ignored) {
                    }
                }
        if (trees == 0)
            score -= 45;
        else
            score += Math.min(12, trees * 2);
        return score;
    }

    /**
     * Strongly prefers positions behind the player — most unsettling spawn
     * location.
     */
    private int scoreViewConcealment(Vector3d playerPos, double playerYaw, Vector3d candidate) {
        double yawDelta = Math.abs(normalizeAngle(
                Math.atan2(candidate.getX() - playerPos.getX(),
                        candidate.getZ() - playerPos.getZ()) - playerYaw));
        if (yawDelta >= 2.45)
            return 55;
        if (yawDelta >= 1.85)
            return 30;
        if (yawDelta >= 1.25)
            return 8;
        if (yawDelta >= 0.70)
            return -22;
        return -50;
    }

    /**
     * Flanking teleport for HUNT_APPROACH.
     * Spawns to the player's side (~72° offset), never directly in front.
     * X17 appears in peripheral vision and then creeps into view.
     */
    private void teleportToFlankingPosition(TransformComponent x17tf, TransformComponent playerTf) {
        Vector3d playerPos = playerTf.getPosition();
        double baseAngle = playerTf.getRotation().getYaw();
        double sideOffset = (rng.nextBoolean() ? 1.0 : -1.0)
                * (Math.PI * 0.40 + randomRange(-0.30, 0.30)); // ~72° ± 17°
        double angle = baseAngle + sideOffset;
        double distance = randomBetween(HUNT_RANGE_MIN, HUNT_RANGE_MAX);
        x17tf.teleportPosition(new Vector3d(
                playerPos.getX() + Math.sin(angle) * distance,
                playerPos.getY(),
                playerPos.getZ() + Math.cos(angle) * distance));
        faceTarget(x17tf, playerPos);
        log(Level.INFO, "[AI] Flank @ " + formatPos(x17tf.getPosition())
                + " dist=" + String.format("%.1f", distance));
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
        double dx = target.getX() - pos.getX(), dz = target.getZ() - pos.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= 0.01)
            return;
        x17tf.setPosition(new Vector3d(
                pos.getX() + (dx / dist) * speed,
                target.getY(),
                pos.getZ() + (dz / dist) * speed));
    }

    private void faceTarget(TransformComponent x17tf, Vector3d target) {
        Vector3d pos = x17tf.getPosition();
        x17tf.setRotation(new Vector3f(0f,
                (float) Math.atan2(target.getX() - pos.getX(), target.getZ() - pos.getZ()), 0f));
    }

    private boolean isPlayerWatchingX17(TransformComponent playerTf, TransformComponent x17tf) {
        Vector3d pPos = playerTf.getPosition(), xPos = x17tf.getPosition();
        double dx = xPos.getX() - pPos.getX(), dy = xPos.getY() - pPos.getY(), dz = xPos.getZ() - pPos.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001 || dist > LOOK_RANGE)
            return false;
        if (Math.abs(normalizeAngle(Math.atan2(dx, dz) - playerTf.getRotation().getYaw())) > PLAYER_FOV_HALF)
            return false;
        return Math.abs(normalizeAngle(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))
                - playerTf.getRotation().getX())) <= PLAYER_PITCH_HALF;
    }

    @SuppressWarnings("deprecation")
    private TargetData selectNearestPlayer(World world, Store<EntityStore> store) {
        if (world.getPlayers() == null)
            return null;
        for (Player p : world.getPlayers()) {
            if (p == null || p.getReference() == null)
                continue;
            TransformComponent tf = store.getComponent(p.getReference(), TransformComponent.getComponentType());
            if (tf != null)
                return new TargetData(p, tf, 0.0);
        }
        return null;
    }

    private void rememberTarget(X17AIComponent ai, TransformComponent playerTf) {
        Vector3d pos = playerTf.getPosition();
        ai.setLastKnownPlayerPos(pos.getX(), pos.getY(), pos.getZ());
    }

    private boolean isDaytime(Store<EntityStore> store) {
        try {
            return !store.getResource(WorldTimeResource.getResourceType())
                    .isDayTimeWithinRange(NIGHT_START, NIGHT_END);
        } catch (Exception e) {
            return false;
        }
    }

    private double normalizeAngle(double a) {
        while (a > Math.PI)
            a -= Math.PI * 2;
        while (a < -Math.PI)
            a += Math.PI * 2;
        return a;
    }

    private int randomBetween(int min, int max) {
        if (max <= min)
            return min;
        return min + rng.nextInt((max - min) + 1);
    }

    private double randomRange(double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }

    private String normalizeBlockId(String raw) {
        if (raw == null)
            return "";
        String id = raw.contains(":") ? raw.substring(raw.lastIndexOf(':') + 1) : raw;
        if (id.startsWith("*"))
            id = id.substring(1);
        return id.toLowerCase();
    }

    private String formatPos(Vector3d pos) {
        return String.format("(%.1f,%.1f,%.1f)", pos.getX(), pos.getY(), pos.getZ());
    }

    private void log(Level level, String msg) {
        if (X17Plugin.getInstance() != null)
            X17Plugin.getInstance().log(level, "[X17-AI] " + msg);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return X17AIComponent.getComponentType();
    }

    private static final class TargetData {
        final Player player;
        final TransformComponent transform;
        final double distance;

        TargetData(Player p, TransformComponent tf, double d) {
            player = p;
            transform = tf;
            distance = d;
        }
    }
}
