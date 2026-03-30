package dev.hytalemod.x17.component;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashSet;
import java.util.Set;

/**
 * X17PlayerComponent — v0.2.7
 * Stores persistent data related to the X17 mod on the player entity.
 */
public class X17PlayerComponent implements Component<EntityStore> {

    private static ComponentType<EntityStore, X17PlayerComponent> COMPONENT_TYPE;

    public static final BuilderCodec<X17PlayerComponent> CODEC = BuilderCodec
            .builder(X17PlayerComponent.class, X17PlayerComponent::new)
            .addField(new KeyedCodec<>("SeenWelcomeWorldsCSV", Codec.STRING),
                    (c, v) -> {
                        c.seenWelcomeWorlds.clear();
                        if (v != null && !v.isEmpty()) {
                            for (String s : v.split(",")) {
                                c.seenWelcomeWorlds.add(s);
                            }
                        }
                    },
                    c -> String.join(",", c.seenWelcomeWorlds))
            .build();

    private Set<String> seenWelcomeWorlds = new HashSet<>();

    public X17PlayerComponent() {
    }

    private X17PlayerComponent(Set<String> seenWelcomeWorlds) {
        this.seenWelcomeWorlds = new HashSet<>(seenWelcomeWorlds);
    }

    public static void init(ComponentType<EntityStore, X17PlayerComponent> type) {
        COMPONENT_TYPE = type;
    }

    public static ComponentType<EntityStore, X17PlayerComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public Set<String> getSeenWelcomeWorlds() {
        return seenWelcomeWorlds;
    }

    public boolean hasSeenWelcomeInWorld(String worldName) {
        return seenWelcomeWorlds.contains(worldName);
    }

    public void markWelcomeAsSeen(String worldName) {
        seenWelcomeWorlds.add(worldName);
    }

    @Override
    public Component<EntityStore> clone() {
        return new X17PlayerComponent(this.seenWelcomeWorlds);
    }
}
