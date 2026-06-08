package dev.hytalemod.x17.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * X18BlackScreenPage - v0.3.4
 *
 * Opaque black overlay shown to the player during the X_18 grab hold event.
 */
public class X18BlackScreenPage extends InteractiveCustomUIPage<X18BlackScreenPage.DummyEventData> {

    private static final String UI_FILE = "Pages/X18BlackScreenPage.ui";

    public static final BuilderCodec<DummyEventData> EVENT_CODEC = BuilderCodec
            .builder(DummyEventData.class, DummyEventData::new)
            .build();

    public X18BlackScreenPage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CantClose, EVENT_CODEC);
    }

    @Override
    public void build(Ref<EntityStore> entityRef,
            UICommandBuilder commandBuilder,
            UIEventBuilder eventBuilder,
            Store<EntityStore> store) {
        commandBuilder.append(UI_FILE);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> entityRef,
            Store<EntityStore> store,
            DummyEventData data) {
    }

    public static class DummyEventData {
        public DummyEventData() {
        }
    }

    public static void showTo(PlayerRef playerRef, Store<EntityStore> store) {
        try {
            Ref<EntityStore> entityRef = playerRef.getReference();
            X18BlackScreenPage page = new X18BlackScreenPage(playerRef);
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(entityRef, store, page);
            }
        } catch (Exception ignored) {
        }
    }

    public static void closeFor(PlayerRef playerRef, Store<EntityStore> store) {
        try {
            Ref<EntityStore> entityRef = playerRef.getReference();
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player == null)
                return;

            // Step 1 — if it's our page, change lifetime so the engine allows dismissal
            CustomUIPage current = player.getPageManager().getCustomPage();
            if (current instanceof X18BlackScreenPage) {
                ((X18BlackScreenPage) current).setLifetime(CustomPageLifetime.CanDismiss);
                // Do NOT call sendUpdate() — setPage(None) below supersedes it
            }

            // Step 2 — always force-close regardless of which page is open
            player.getPageManager().setPage(entityRef, store, Page.None);
        } catch (Exception ignored) {
        }
    }
}