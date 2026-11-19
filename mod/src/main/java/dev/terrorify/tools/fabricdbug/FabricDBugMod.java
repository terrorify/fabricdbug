package dev.terrorify.tools.fabricdbug;

import com.mojang.blaze3d.platform.InputConstants;
import dev.terrorify.tools.fabricdbug.config.FabricDBugConfigScreen;
import dev.terrorify.tools.fabricdbug.control.SpacetimeCommandFeed;
import dev.terrorify.tools.fabricdbug.identity.IdentityManager;
import dev.terrorify.tools.fabricdbug.input.InputScriptEngine;
import dev.terrorify.tools.fabricdbug.input.SequenceRecorder;
import dev.terrorify.tools.fabricdbug.render.CullingDebugOverlay;
import dev.terrorify.tools.fabricdbug.spacetime.HotPlayerTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Developer-only SpacetimeDB verification oracle and debug suite for PerceptionAuthority V4.
 */
public final class FabricDBugMod implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricdbug");

    private static KeyMapping toggleRecordKey;
    private static KeyMapping cancelRecordKey;
    private static KeyMapping quickPlayKey;
    private static KeyMapping openMenuKey;
    private static KeyMapping clickGuiKey;

    @Override
    public void onInitializeClient() {
        dev.terrorify.tools.fabricdbug.config.FabricDBugConfig.get();
        IdentityManager.init();

        HotPlayerTracker hotPlayers = new HotPlayerTracker();
        InputScriptEngine scripts = new InputScriptEngine();
        SequenceRecorder recorder = new SequenceRecorder(scripts);
        dev.terrorify.tools.fabricdbug.gui.module.ModuleManager.get().setRecorder(recorder);
        SpacetimeCommandFeed commands = new SpacetimeCommandFeed(scripts);
        new CullingDebugOverlay(hotPlayers).install();

        // Register Keymappings (F8 = Record, F9 = Cancel, F7 = Play, F6 = Menu, RSHIFT = ClickGUI)
        toggleRecordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.fabricdbug.toggle_record", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, KeyMapping.Category.DEBUG));
        cancelRecordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.fabricdbug.cancel_record", InputConstants.Type.KEYSYM, InputConstants.KEY_F9, KeyMapping.Category.DEBUG));
        quickPlayKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.fabricdbug.quick_play", InputConstants.Type.KEYSYM, InputConstants.KEY_F7, KeyMapping.Category.DEBUG));
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.fabricdbug.open_menu", InputConstants.Type.KEYSYM, InputConstants.KEY_F6, KeyMapping.Category.DEBUG));
        clickGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.fabricdbug.click_gui", InputConstants.Type.KEYSYM, 344 /* GLFW_KEY_RIGHT_SHIFT */, KeyMapping.Category.DEBUG));
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            scripts.tick();
            recorder.tick();
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (toggleRecordKey.consumeClick()) recorder.toggleRecording();
            while (cancelRecordKey.consumeClick()) recorder.cancelRecording();
            while (quickPlayKey.consumeClick()) recorder.quickPlayLatest();
            while (openMenuKey.consumeClick()) {
                mc.setScreenAndShow(FabricDBugConfigScreen.create(null));
            }
            while (clickGuiKey.consumeClick()) {
                mc.setScreenAndShow(new dev.terrorify.tools.fabricdbug.gui.ClickGuiScreen(null));
            }
            commands.tick();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> commands.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> commands.onDisconnect());

        // Title Screen Integration: add FabricDBug config button
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen) {
                Screens.getWidgets(screen).add(
                    Button.builder(Component.literal("§bDebug GUI"), b -> client.setScreenAndShow(new dev.terrorify.tools.fabricdbug.gui.ClickGuiScreen(screen)))
                        .bounds(scaledWidth - 95, 6, 88, 20)
                        .build()
                );
                Screens.getWidgets(screen).add(
                    Button.builder(Component.literal("§7Settings"), b -> client.setScreenAndShow(FabricDBugConfigScreen.create(screen)))
                        .bounds(scaledWidth - 95, 28, 88, 18)
                        .build()
                );
            }
        });
        LOGGER.info("FabricDBug initialized: PerceptionAuthority oracle, sequence recorder & multi-instance routing active");
    }
}
