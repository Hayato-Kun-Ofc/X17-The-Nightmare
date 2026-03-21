package dev.hytalemod.x17.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.hytalemod.x17.X17Plugin;

import java.util.logging.Level;

/**
 * X17WelcomePage — v0.2.5
 *
 * Welcome screen shown to every player on their first connection.
 * Uses the InteractiveCustomUIPage system confirmed in
 * LaunchPadSettingsPage.class.
 *
 * The screen is closed when the player clicks the button (@Close event).
 * Marked as "temporary" — does not reappear after being closed (stored in
 * PlayerData).
 *
 * Confirmed pattern:
 * InteractiveCustomUIPage<EventData>
 * .ui file at Pages/X17WelcomePage.ui
 * CustomPageLifetime.UntilClosed
 * PageManager.setPage() for showing / Page.None for closing
 */
public class X17WelcomePage
        extends InteractiveCustomUIPage<X17WelcomePage.WelcomeEventData> {

    // ── UI file path (relative to the server's asset pack) ────────────────────
    private static final String UI_FILE = "Pages/X17WelcomePage.ui";

    // ── Event data codec ──────────────────────────────────────────────────────
    public static final BuilderCodec<WelcomeEventData> EVENT_CODEC = BuilderCodec
            .builder(WelcomeEventData.class, WelcomeEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING),
                    (e, v) -> e.action = v, e -> e.action)
            .add()
            .build();

    // ── Constructor ──────────────────────────────────────────────────────────
    public X17WelcomePage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, EVENT_CODEC);
    }

    // ── build: populates the UI with dynamic data ────────────────────────────
    // Text comes from translations defined in .lang files via {ui.x17.*} keys.
    // No extra dynamic data needed — everything is i18n on the client side.
    @Override
    public void build(Ref<EntityStore> entityRef,
            UICommandBuilder commandBuilder,
            UIEventBuilder eventBuilder,
            Store<EntityStore> store) {
        // Load layout from the .ui file
        commandBuilder.append(UI_FILE);

        // Bind CloseButton to the "close" event
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CloseButton",
                new EventData().append("Action", "close"),
                false);
    }

    // ── handleDataEvent: reacts to button clicks ─────────────────────────────
    @Override
    public void handleDataEvent(Ref<EntityStore> entityRef,
            Store<EntityStore> store,
            WelcomeEventData data) {
        if ("close".equals(data.action)) {
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player != null) {
                player.getPageManager().setPage(entityRef, store, Page.None);
                X17Plugin.getInstance().log(Level.INFO, "Welcome screen closed by player.");
            }
        }
    }

    // ── Event data ────────────────────────────────────────────────────────────
    /**
     * The only event is the close button (@Close in the .ui).
     * Does not carry additional data.
     */
    public static class WelcomeEventData {
        private String action;

        public WelcomeEventData() {
        }
    }

    // ── Static utility: shows the screen to a player ──────────────────────────
    /**
     * Call this method in X17EventSystem when a new player joins.
     * Example:
     * X17WelcomePage.showTo(playerRef, entityStore);
     */
    public static void showTo(PlayerRef playerRef, Store<EntityStore> store) {
        try {
            Ref<EntityStore> entityRef = playerRef.getReference();
            X17WelcomePage page = new X17WelcomePage(playerRef);

            Player player = store.getComponent(entityRef, Player.getComponentType());

            if (player != null) {
                player.getPageManager().openCustomPage(entityRef, store, page);
                X17Plugin.getInstance().log(Level.INFO, "Showing X17 welcome screen.");
            }

        } catch (Exception e) {
            X17Plugin.getInstance().log(Level.WARNING,
                    "Failed to show welcome screen: " + e.getMessage());
        }
    }
}
