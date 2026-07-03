package dev.hytalemod.x17.ui;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opaque black overlay shown to the player during the X_18 grab hold event.
 */
public class X18BlackScreenPage extends InteractiveCustomUIPage<X18BlackScreenPage.DummyEventData> {

    private static final String UI_FILE = "Pages/X18BlackScreenPage.ui";
    private static final int HARD_TIMEOUT_TICKS = 160;
    private static final int STALE_ENTRY_TICKS = HARD_TIMEOUT_TICKS + 60;

    private static final ConcurrentHashMap<Integer, ActiveBlackScreen> ACTIVE_BLACK_SCREENS =
            new ConcurrentHashMap<>();

    public static final BuilderCodec<DummyEventData> EVENT_CODEC = BuilderCodec
            .builder(DummyEventData.class, DummyEventData::new)
            .build();

    public X18BlackScreenPage(PlayerRef playerRef) {
        // Never use CantClose here. If the AI state machine or close packet fails,
        // the player still needs a guaranteed way back to normal controls/menu.
        super(playerRef, CustomPageLifetime.CanDismiss, EVENT_CODEC);
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
            if (playerRef == null || store == null || playerRef.getReference() == null) {
                return;
            }
            Ref<EntityStore> entityRef = playerRef.getReference();
            X18BlackScreenPage page = new X18BlackScreenPage(playerRef);
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player != null) {
                player.getPageManager().openCustomPage(entityRef, store, page);
                ACTIVE_BLACK_SCREENS.put(keyFor(playerRef), new ActiveBlackScreen(playerRef));
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean closeFor(PlayerRef playerRef, Store<EntityStore> store) {
        try {
            if (playerRef == null || store == null || playerRef.getReference() == null) {
                return false;
            }
            Ref<EntityStore> entityRef = playerRef.getReference();
            Player player = store.getComponent(entityRef, Player.getComponentType());
            if (player == null) {
                return false;
            }

            CustomUIPage current = player.getPageManager().getCustomPage();
            if (current instanceof X18BlackScreenPage) {
                ((X18BlackScreenPage) current).setLifetime(CustomPageLifetime.CanDismiss);
            }

            player.getPageManager().setPage(entityRef, store, Page.None);
            ACTIVE_BLACK_SCREENS.remove(keyFor(playerRef));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Independent fail-safe used by X18BlackScreenSafetySystem. This runs even if
     * the X_18 AI state machine stops ticking during the blackout sequence.
     */
    public static void tickSafety(Store<EntityStore> store) {
        if (store == null || ACTIVE_BLACK_SCREENS.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, ActiveBlackScreen> entry : ACTIVE_BLACK_SCREENS.entrySet()) {
            ActiveBlackScreen active = entry.getValue();
            if (active == null || active.playerRef == null) {
                ACTIVE_BLACK_SCREENS.remove(entry.getKey());
                continue;
            }
            active.ageTicks++;
            if (active.ageTicks >= HARD_TIMEOUT_TICKS && closeFor(active.playerRef, store)) {
                continue;
            }
            if (active.ageTicks >= STALE_ENTRY_TICKS) {
                ACTIVE_BLACK_SCREENS.remove(entry.getKey());
            }
        }
    }

    public static void forceCloseAll(Store<EntityStore> store) {
        if (store == null || ACTIVE_BLACK_SCREENS.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, ActiveBlackScreen> entry : ACTIVE_BLACK_SCREENS.entrySet()) {
            ActiveBlackScreen active = entry.getValue();
            if (active == null || active.playerRef == null
                    || closeFor(active.playerRef, store)) {
                ACTIVE_BLACK_SCREENS.remove(entry.getKey());
            }
        }
    }

    private static int keyFor(PlayerRef playerRef) {
        if (playerRef == null || playerRef.getReference() == null) {
            return System.identityHashCode(playerRef);
        }
        return playerRef.getReference().getIndex();
    }

    private static final class ActiveBlackScreen {
        private final PlayerRef playerRef;
        private int ageTicks;

        private ActiveBlackScreen(PlayerRef playerRef) {
            this.playerRef = playerRef;
        }
    }
}
