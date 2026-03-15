package dev.hytalemod.x17.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * X17AIComponent - v0.2.4
 *
 * Holds the full runtime state for the X17 director-style AI.
 */
public class X17AIComponent implements Component<EntityStore> {

    public enum X17State {
        DORMANT,
        STALK,
        CHASE,
        RAGE,
        VANISH,
        RETREAT,
        AMBUSH_SCARE,
        HUNT_APPROACH,
        REPOSITION,
        TRUE_VANISH;

        public static X17State fromId(int id) {
            X17State[] values = values();
            if (id < 0 || id >= values.length) {
                return DORMANT;
            }
            return values[id];
        }
    }

    public static final double MIN_SPAWN_DISTANCE = 18.0;
    public static final double STALK_DISTANCE = 96.0;
    public static final double CHASE_DISTANCE = 52.0;
    public static final double EXPLOIT_HEIGHT_THRESHOLD = 2.0;

    public static final int ESCAPE_HIT_THRESHOLD = 2;
    public static final int HIT_WINDOW_TICKS = 120;
    public static final int CHASE_TIMEOUT = 120;
    public static final int RAGE_TIMEOUT = 60;
    public static final int VANISH_COOLDOWN = 240;
    public static final int COMBAT_ESCAPE_COOLDOWN = 2400;
    public static final int NIGHT_ACTIVITY_MIN_TICKS = 2400;
    public static final int NIGHT_ACTIVITY_MAX_TICKS = 3600;

    private static ComponentType<EntityStore, X17AIComponent> COMPONENT_TYPE;

    public static final BuilderCodec<X17AIComponent> CODEC = BuilderCodec
            .builder(X17AIComponent.class, X17AIComponent::new)
            .addField(new KeyedCodec<>("State", Codec.INTEGER),
                    (c, v) -> c.currentState = X17State.fromId(v),
                    c -> c.currentState.ordinal())
            .addField(new KeyedCodec<>("SpawnCooldownTicks", Codec.INTEGER),
                    (c, v) -> c.spawnCooldownTicks = v,
                    c -> c.spawnCooldownTicks)
            .addField(new KeyedCodec<>("VanishTimerTicks", Codec.INTEGER),
                    (c, v) -> c.vanishTimerTicks = v,
                    c -> c.vanishTimerTicks)
            .addField(new KeyedCodec<>("HitWindowTicks", Codec.INTEGER),
                    (c, v) -> c.hitWindowTicks = v,
                    c -> c.hitWindowTicks)
            .addField(new KeyedCodec<>("ChaseDurationTicks", Codec.INTEGER),
                    (c, v) -> c.chaseDurationTicks = v,
                    c -> c.chaseDurationTicks)
            .addField(new KeyedCodec<>("CombatHitCount", Codec.INTEGER),
                    (c, v) -> c.combatHitCount = v,
                    c -> c.combatHitCount)
            .addField(new KeyedCodec<>("AggroTargetEntityId", Codec.INTEGER),
                    (c, v) -> c.aggroTargetEntityId = v,
                    c -> c.aggroTargetEntityId)
            .addField(new KeyedCodec<>("LastKnownPlayerX", Codec.DOUBLE),
                    (c, v) -> c.lastKnownPlayerX = v,
                    c -> c.lastKnownPlayerX)
            .addField(new KeyedCodec<>("LastKnownPlayerY", Codec.DOUBLE),
                    (c, v) -> c.lastKnownPlayerY = v,
                    c -> c.lastKnownPlayerY)
            .addField(new KeyedCodec<>("LastKnownPlayerZ", Codec.DOUBLE),
                    (c, v) -> c.lastKnownPlayerZ = v,
                    c -> c.lastKnownPlayerZ)
            .addField(new KeyedCodec<>("SpawnCheckDone", Codec.BOOLEAN),
                    (c, v) -> c.spawnCheckDone = v,
                    c -> c.spawnCheckDone)
            .addField(new KeyedCodec<>("FledFromCombat", Codec.BOOLEAN),
                    (c, v) -> c.fledFromCombat = v,
                    c -> c.fledFromCombat)
            .addField(new KeyedCodec<>("FalseAmbush", Codec.BOOLEAN),
                    (c, v) -> c.falseAmbush = v,
                    c -> c.falseAmbush)
            .addField(new KeyedCodec<>("AmbushScareCooldownTicks", Codec.INTEGER),
                    (c, v) -> c.ambushScareCooldownTicks = v,
                    c -> c.ambushScareCooldownTicks)
            .addField(new KeyedCodec<>("SpawnAllowedThisNight", Codec.BOOLEAN),
                    (c, v) -> c.spawnAllowedThisNight = v,
                    c -> c.spawnAllowedThisNight)
            .addField(new KeyedCodec<>("GhostSoundNight", Codec.BOOLEAN),
                    (c, v) -> c.ghostSoundNight = v,
                    c -> c.ghostSoundNight)
            .addField(new KeyedCodec<>("AttackNight", Codec.BOOLEAN),
                    (c, v) -> c.attackNight = v,
                    c -> c.attackNight)
            .addField(new KeyedCodec<>("CurrentNightNumber", Codec.INTEGER),
                    (c, v) -> c.currentNightNumber = v,
                    c -> c.currentNightNumber)
            .addField(new KeyedCodec<>("NightPresenceBudgetTicks", Codec.INTEGER),
                    (c, v) -> c.nightPresenceBudgetTicks = v,
                    c -> c.nightPresenceBudgetTicks)
            .addField(new KeyedCodec<>("ActionCooldownTicks", Codec.INTEGER),
                    (c, v) -> c.actionCooldownTicks = v,
                    c -> c.actionCooldownTicks)
            .addField(new KeyedCodec<>("RepositionCooldownTicks", Codec.INTEGER),
                    (c, v) -> c.repositionCooldownTicks = v,
                    c -> c.repositionCooldownTicks)
            .addField(new KeyedCodec<>("HuntCommitmentTicks", Codec.INTEGER),
                    (c, v) -> c.huntCommitmentTicks = v,
                    c -> c.huntCommitmentTicks)
            .addField(new KeyedCodec<>("HighGroundPunishCooldownTicks", Codec.INTEGER),
                    (c, v) -> c.highGroundPunishCooldownTicks = v,
                    c -> c.highGroundPunishCooldownTicks)
            .addField(new KeyedCodec<>("AppearanceHoldTicks", Codec.INTEGER),
                    (c, v) -> c.appearanceHoldTicks = v,
                    c -> c.appearanceHoldTicks)
            .addField(new KeyedCodec<>("LookExposureTicks", Codec.INTEGER),
                    (c, v) -> c.lookExposureTicks = v,
                    c -> c.lookExposureTicks)
            .addField(new KeyedCodec<>("AttackAttemptedThisNight", Codec.BOOLEAN),
                    (c, v) -> c.attackAttemptedThisNight = v,
                    c -> c.attackAttemptedThisNight)
            .build();

    private X17State currentState = X17State.DORMANT;
    private int spawnCooldownTicks = 0;
    private int vanishTimerTicks = 0;
    private int hitWindowTicks = 0;
    private int chaseDurationTicks = 0;
    private int combatHitCount = 0;
    private int aggroTargetEntityId = -1;
    private double lastKnownPlayerX = 0.0;
    private double lastKnownPlayerY = 0.0;
    private double lastKnownPlayerZ = 0.0;
    private boolean spawnCheckDone = false;
    private boolean fledFromCombat = false;
    private boolean falseAmbush = false;
    private int ambushScareCooldownTicks = 0;
    private boolean spawnAllowedThisNight = false;
    private boolean ghostSoundNight = false;
    private boolean attackNight = false;
    private int currentNightNumber = -1;
    private int nightPresenceBudgetTicks = 0;
    private int actionCooldownTicks = 0;
    private int repositionCooldownTicks = 0;
    private int huntCommitmentTicks = 0;
    private int highGroundPunishCooldownTicks = 0;
    private int appearanceHoldTicks = 0;
    private int lookExposureTicks = 0;
    private boolean attackAttemptedThisNight = false;

    public X17AIComponent() {
    }

    private X17AIComponent(X17AIComponent other) {
        this.currentState = other.currentState;
        this.spawnCooldownTicks = other.spawnCooldownTicks;
        this.vanishTimerTicks = other.vanishTimerTicks;
        this.hitWindowTicks = other.hitWindowTicks;
        this.chaseDurationTicks = other.chaseDurationTicks;
        this.combatHitCount = other.combatHitCount;
        this.aggroTargetEntityId = other.aggroTargetEntityId;
        this.lastKnownPlayerX = other.lastKnownPlayerX;
        this.lastKnownPlayerY = other.lastKnownPlayerY;
        this.lastKnownPlayerZ = other.lastKnownPlayerZ;
        this.spawnCheckDone = other.spawnCheckDone;
        this.fledFromCombat = other.fledFromCombat;
        this.falseAmbush = other.falseAmbush;
        this.ambushScareCooldownTicks = other.ambushScareCooldownTicks;
        this.spawnAllowedThisNight = other.spawnAllowedThisNight;
        this.ghostSoundNight = other.ghostSoundNight;
        this.attackNight = other.attackNight;
        this.currentNightNumber = other.currentNightNumber;
        this.nightPresenceBudgetTicks = other.nightPresenceBudgetTicks;
        this.actionCooldownTicks = other.actionCooldownTicks;
        this.repositionCooldownTicks = other.repositionCooldownTicks;
        this.huntCommitmentTicks = other.huntCommitmentTicks;
        this.highGroundPunishCooldownTicks = other.highGroundPunishCooldownTicks;
        this.appearanceHoldTicks = other.appearanceHoldTicks;
        this.lookExposureTicks = other.lookExposureTicks;
        this.attackAttemptedThisNight = other.attackAttemptedThisNight;
    }

    public static void init(ComponentType<EntityStore, X17AIComponent> type) {
        COMPONENT_TYPE = type;
    }

    public static ComponentType<EntityStore, X17AIComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public void configureNightDirective(int nightNumber, boolean spawnAllowed, boolean ghostNight,
            boolean attackNight, int spawnDelayTicks, int presenceBudgetTicks) {
        boolean directiveChanged = currentNightNumber != nightNumber
                || spawnAllowedThisNight != spawnAllowed
                || ghostSoundNight != ghostNight
                || this.attackNight != attackNight;

        currentNightNumber = nightNumber;
        spawnAllowedThisNight = spawnAllowed;
        ghostSoundNight = ghostNight;
        this.attackNight = attackNight;

        if (!spawnAllowed) {
            falseAmbush = false;
            attackAttemptedThisNight = false;
            appearanceHoldTicks = 0;
            lookExposureTicks = 0;
            huntCommitmentTicks = 0;
            actionCooldownTicks = 0;
            repositionCooldownTicks = 0;
            nightPresenceBudgetTicks = 0;
            if (currentState != X17State.DORMANT && currentState != X17State.VANISH) {
                currentState = X17State.VANISH;
                vanishTimerTicks = 1;
            } else if (currentState == X17State.DORMANT) {
                spawnCooldownTicks = Math.max(spawnCooldownTicks, 200);
            }
            return;
        }

        if (!directiveChanged) {
            return;
        }

        spawnCheckDone = false;
        fledFromCombat = false;
        falseAmbush = false;
        combatHitCount = 0;
        hitWindowTicks = 0;
        chaseDurationTicks = 0;
        aggroTargetEntityId = -1;
        nightPresenceBudgetTicks = presenceBudgetTicks;
        actionCooldownTicks = spawnDelayTicks;
        repositionCooldownTicks = Math.max(20, spawnDelayTicks / 2);
        huntCommitmentTicks = 0;
        highGroundPunishCooldownTicks = 0;
        appearanceHoldTicks = 0;
        lookExposureTicks = 0;
        attackAttemptedThisNight = false;
        currentState = X17State.DORMANT;
        vanishTimerTicks = 0;
        if (spawnCooldownTicks < spawnDelayTicks) {
            spawnCooldownTicks = spawnDelayTicks;
        }
    }

    public void onX17DamagedByPlayer(Ref<EntityStore> attackerRef) {
        if (attackerRef != null && attackerRef.isValid()) {
            aggroTargetEntityId = attackerRef.getIndex();
        }

        if (hitWindowTicks <= 0) {
            combatHitCount = 0;
        }

        hitWindowTicks = HIT_WINDOW_TICKS;
        combatHitCount++;
        attackAttemptedThisNight = true;
        appearanceHoldTicks = 0;
        lookExposureTicks = 0;
        falseAmbush = false;

        fledFromCombat = true;
        currentState = X17State.VANISH;
        vanishTimerTicks = 1;
        huntCommitmentTicks = 0;
        chaseDurationTicks = 0;
        spawnCooldownTicks = COMBAT_ESCAPE_COOLDOWN;
    }

    public X17State getCurrentState() {
        return currentState;
    }

    public void setCurrentState(X17State currentState) {
        this.currentState = currentState;
    }

    public int getSpawnCooldownTicks() {
        return spawnCooldownTicks;
    }

    public void setSpawnCooldownTicks(int spawnCooldownTicks) {
        this.spawnCooldownTicks = Math.max(0, spawnCooldownTicks);
    }

    public void decrementSpawnCooldown() {
        if (spawnCooldownTicks > 0) {
            spawnCooldownTicks--;
        }
    }

    public int getVanishTimerTicks() {
        return vanishTimerTicks;
    }

    public void setVanishTimerTicks(int vanishTimerTicks) {
        this.vanishTimerTicks = Math.max(0, vanishTimerTicks);
    }

    public void decrementVanishTimer() {
        if (vanishTimerTicks > 0) {
            vanishTimerTicks--;
        }
    }

    public int getHitWindowTicks() {
        return hitWindowTicks;
    }

    public void setHitWindowTicks(int hitWindowTicks) {
        this.hitWindowTicks = Math.max(0, hitWindowTicks);
    }

    public void decrementHitWindow() {
        if (hitWindowTicks > 0) {
            hitWindowTicks--;
        }
    }

    public int getChaseDurationTicks() {
        return chaseDurationTicks;
    }

    public void incrementChaseDuration() {
        chaseDurationTicks++;
    }

    public void resetChaseDuration() {
        chaseDurationTicks = 0;
    }

    public int getCombatHitCount() {
        return combatHitCount;
    }

    public void incrementCombatHitCount() {
        combatHitCount++;
    }

    public void resetCombatHitCount() {
        combatHitCount = 0;
    }

    public int getAggroTargetEntityId() {
        return aggroTargetEntityId;
    }

    public void setAggroTargetEntityId(int aggroTargetEntityId) {
        this.aggroTargetEntityId = aggroTargetEntityId;
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

    public boolean isSpawnCheckDone() {
        return spawnCheckDone;
    }

    public void setSpawnCheckDone(boolean spawnCheckDone) {
        this.spawnCheckDone = spawnCheckDone;
    }

    public boolean hasFledFromCombat() {
        return fledFromCombat;
    }

    public void setFledFromCombat(boolean fledFromCombat) {
        this.fledFromCombat = fledFromCombat;
    }

    public boolean isFalseAmbush() {
        return falseAmbush;
    }

    public void setFalseAmbush(boolean falseAmbush) {
        this.falseAmbush = falseAmbush;
    }

    public int getAmbushScareCooldownTicks() {
        return ambushScareCooldownTicks;
    }

    public void setAmbushScareCooldownTicks(int ambushScareCooldownTicks) {
        this.ambushScareCooldownTicks = Math.max(0, ambushScareCooldownTicks);
    }

    public void decrementAmbushScareCooldown() {
        if (ambushScareCooldownTicks > 0) {
            ambushScareCooldownTicks--;
        }
    }

    public boolean isSpawnAllowedThisNight() {
        return spawnAllowedThisNight;
    }

    public void setSpawnAllowedThisNight(boolean spawnAllowedThisNight) {
        this.spawnAllowedThisNight = spawnAllowedThisNight;
    }

    public boolean isGhostSoundNight() {
        return ghostSoundNight;
    }

    public void setGhostSoundNight(boolean ghostSoundNight) {
        this.ghostSoundNight = ghostSoundNight;
    }

    public boolean isAttackNight() {
        return attackNight;
    }

    public void setAttackNight(boolean attackNight) {
        this.attackNight = attackNight;
    }

    public int getCurrentNightNumber() {
        return currentNightNumber;
    }

    public int getNightPresenceBudgetTicks() {
        return nightPresenceBudgetTicks;
    }

    public void setNightPresenceBudgetTicks(int nightPresenceBudgetTicks) {
        this.nightPresenceBudgetTicks = Math.max(0, nightPresenceBudgetTicks);
    }

    public void decrementNightPresenceBudget() {
        if (nightPresenceBudgetTicks > 0) {
            nightPresenceBudgetTicks--;
        }
    }

    public int getActionCooldownTicks() {
        return actionCooldownTicks;
    }

    public void setActionCooldownTicks(int actionCooldownTicks) {
        this.actionCooldownTicks = Math.max(0, actionCooldownTicks);
    }

    public void decrementActionCooldown() {
        if (actionCooldownTicks > 0) {
            actionCooldownTicks--;
        }
    }

    public int getRepositionCooldownTicks() {
        return repositionCooldownTicks;
    }

    public void setRepositionCooldownTicks(int repositionCooldownTicks) {
        this.repositionCooldownTicks = Math.max(0, repositionCooldownTicks);
    }

    public void decrementRepositionCooldown() {
        if (repositionCooldownTicks > 0) {
            repositionCooldownTicks--;
        }
    }

    public int getHuntCommitmentTicks() {
        return huntCommitmentTicks;
    }

    public void setHuntCommitmentTicks(int huntCommitmentTicks) {
        this.huntCommitmentTicks = Math.max(0, huntCommitmentTicks);
    }

    public void decrementHuntCommitment() {
        if (huntCommitmentTicks > 0) {
            huntCommitmentTicks--;
        }
    }

    public int getHighGroundPunishCooldownTicks() {
        return highGroundPunishCooldownTicks;
    }

    public void setHighGroundPunishCooldownTicks(int highGroundPunishCooldownTicks) {
        this.highGroundPunishCooldownTicks = Math.max(0, highGroundPunishCooldownTicks);
    }

    public void decrementHighGroundPunishCooldown() {
        if (highGroundPunishCooldownTicks > 0) {
            highGroundPunishCooldownTicks--;
        }
    }

    public int getAppearanceHoldTicks() {
        return appearanceHoldTicks;
    }

    public void setAppearanceHoldTicks(int appearanceHoldTicks) {
        this.appearanceHoldTicks = Math.max(0, appearanceHoldTicks);
    }

    public void decrementAppearanceHold() {
        if (appearanceHoldTicks > 0) {
            appearanceHoldTicks--;
        }
    }

    public int getLookExposureTicks() {
        return lookExposureTicks;
    }

    public void setLookExposureTicks(int lookExposureTicks) {
        this.lookExposureTicks = Math.max(0, lookExposureTicks);
    }

    public void incrementLookExposureTicks() {
        lookExposureTicks++;
    }

    public void decrementLookExposureTicks() {
        if (lookExposureTicks > 0) {
            lookExposureTicks--;
        }
    }

    public boolean isAttackAttemptedThisNight() {
        return attackAttemptedThisNight;
    }

    public void setAttackAttemptedThisNight(boolean attackAttemptedThisNight) {
        this.attackAttemptedThisNight = attackAttemptedThisNight;
    }

    @Override
    public Component<EntityStore> clone() {
        return new X17AIComponent(this);
    }
}
