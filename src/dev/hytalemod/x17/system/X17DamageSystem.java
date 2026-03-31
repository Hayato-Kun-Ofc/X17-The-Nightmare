package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.component.X17AIComponent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * X17DamageSystem - v0.2.8
 *
 * Extends DamageEventSystem and routes player-dealt damage to X17AISystem
 * so that RAGE state is triggered correctly — never via a timer.
 */
public class X17DamageSystem extends DamageEventSystem {

    private final Query<EntityStore> query;
    private final X17AISystem aiSystem;

    public X17DamageSystem(X17AISystem aiSystem) {
        this.query = X17AIComponent.getComponentType();
        this.aiSystem = aiSystem;
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(int index,
            ArchetypeChunk<EntityStore> archetypeChunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            Damage damage) {

        X17AIComponent ai = (X17AIComponent) archetypeChunk.getComponent(
                index, X17AIComponent.getComponentType());
        if (ai == null) {
            return;
        }

        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource)) {
            return;
        }

        Damage.EntitySource entitySource = (Damage.EntitySource) source;
        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (attackerRef == null || !attackerRef.isValid()) {
            return;
        }

        TransformComponent x17tf = (TransformComponent) archetypeChunk.getComponent(
                index, TransformComponent.getComponentType());
        TransformComponent attackerTf = store.getComponent(attackerRef, TransformComponent.getComponentType());

        if (x17tf != null && aiSystem != null) {
            aiSystem.onX17HitByPlayer(ai, x17tf, attackerTf);
        }
    }
}
