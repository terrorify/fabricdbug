package dev.terrorify.tools.fabricdbug;

import dev.terrorify.tools.fabricdbug.control.SpacetimeCommandFeed;
import dev.terrorify.tools.fabricdbug.input.InputScriptEngine;
import dev.terrorify.tools.fabricdbug.render.CullingDebugOverlay;
import dev.terrorify.tools.fabricdbug.spacetime.HotPlayerTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure client-side successor to MCPClient. All control and truth flow through SpacetimeDB. */
public final class FabricDBugMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricdbug");

    @Override
    public void onInitializeClient() {
        dev.terrorify.tools.fabricdbug.config.FabricDBugConfig.get();
        HotPlayerTracker hotPlayers = new HotPlayerTracker();
        InputScriptEngine scripts = new InputScriptEngine();
        SpacetimeCommandFeed commands = new SpacetimeCommandFeed(scripts);
        new CullingDebugOverlay(hotPlayers).install();

        ClientTickEvents.START_CLIENT_TICK.register(mc -> scripts.tick());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            hotPlayers.tick();
            commands.tick();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> commands.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> commands.onDisconnect());

        LOGGER.info("FabricDBug initialized: SpacetimeDB overlay/control active; MCP disabled");
    }
}
