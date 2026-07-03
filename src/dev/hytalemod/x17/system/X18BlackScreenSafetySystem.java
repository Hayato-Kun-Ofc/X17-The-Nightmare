package dev.hytalemod.x17.system;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.ui.X18BlackScreenPage;

/**
 * Independent black-screen fail-safe for X_18.
 *
 * This system is intentionally separate from X18AISystem so the player is not
 * trapped behind the blackout page if the grab/blackout state machine aborts,
 * loses its target, or stops ticking after a game/menu transition.
 */
public class X18BlackScreenSafetySystem extends TickingSystem<EntityStore> {

    @Override
    public void tick(float deltaTime, int tickIndex, Store<EntityStore> store) {
        X18BlackScreenPage.tickSafety(store);
    }
}
