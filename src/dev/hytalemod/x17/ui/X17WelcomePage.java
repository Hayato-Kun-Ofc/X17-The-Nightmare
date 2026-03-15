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
 * X17WelcomePage — v0.2.4
 *
 * Tela de boas-vindas mostrado a cada jogador na primeira ligação.
 * Usa o sistema InteractiveCustomUIPage confirmado em
 * LaunchPadSettingsPage.class.
 *
 * O écran é fechado quando o jogador clica no botão (@Close event).
 * Marcado como "temporário" — não reaparece após ser fechado (guardado em
 * PlayerData).
 *
 * Padrão confirmado:
 * InteractiveCustomUIPage<EventData>
 * ficheiro .ui em Pages/X17WelcomePage.ui
 * CustomPageLifetime.UntilClosed
 * PageManager.setPage() para mostrar / Page.None para fechar
 */
public class X17WelcomePage
        extends InteractiveCustomUIPage<X17WelcomePage.WelcomeEventData> {

    // ── Caminho do ficheiro UI (relativo ao pack de assets do servidor) ───────
    private static final String UI_FILE = "Pages/X17WelcomePage.ui";

    // ── Codec dos dados de evento ─────────────────────────────────────────────
    public static final BuilderCodec<WelcomeEventData> EVENT_CODEC = BuilderCodec
            .builder(WelcomeEventData.class, WelcomeEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING),
                    (e, v) -> e.action = v, e -> e.action)
            .add()
            .build();

    // ── Construtor ────────────────────────────────────────────────────────────
    public X17WelcomePage(PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss, EVENT_CODEC);
    }

    // ── build: preenche o UI com dados dinâmicos ───────────────────────────────
    // O texto vem das traduções definidas nos ficheiros .lang via chaves {ui.x17.*}
    // Nenhum dado dinâmico extra necessário — tudo é i18n do lado do cliente.
    @Override
    public void build(Ref<EntityStore> entityRef,
            UICommandBuilder commandBuilder,
            UIEventBuilder eventBuilder,
            Store<EntityStore> store) {
        // Carrega o layout do ficheiro .ui
        commandBuilder.append(UI_FILE);

        // Liga o botão CloseButton ao evento "close"
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CloseButton",
                new EventData().append("Action", "close"),
                false);
    }

    // ── handleDataEvent: reage ao clique do botão ─────────────────────────────
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

    // ── Dados de evento ───────────────────────────────────────────────────────
    /**
     * O único evento é o botão de fecho (@Close no .ui).
     * Não transporta dados adicionais.
     */
    public static class WelcomeEventData {
        private String action;

        public WelcomeEventData() {
        }
    }

    // ── Utilitário estático: mostra o écran a um jogador ─────────────────────
    /**
     * Chama este método no X17EventSystem quando um novo jogador entra.
     * Exemplo:
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
