package dev.terrorify.tools.fabricdbug.config;

import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.terrorify.tools.fabricdbug.identity.IdentityManager;
import dev.terrorify.tools.fabricdbug.input.SequenceRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class FabricDBugConfigScreen {
    public static Screen create(Screen parent) {
        FabricDBugConfig config = FabricDBugConfig.get();
        IdentityManager.init();

        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal("FabricDBug Control & Oracle"))
            // Category 1: SpacetimeDB & Multi-Instance Fleet
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("SpacetimeDB & Fleet"))
                .tooltip(Component.literal("SpacetimeDB connection and multi-client routing"))
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Enabled"))
                    .description(OptionDescription.of(Component.literal("Enable or disable FabricDBug telemetry and control")))
                    .binding(true, () -> config.enabled, val -> config.enabled = val)
                    .controller(BooleanControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Instance ID"))
                    .description(OptionDescription.of(Component.literal("Cluster instance ID for dual-client testing (e.g. instance_a or instance_b)")))
                    .binding("instance_a", () -> config.instanceId, val -> config.instanceId = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("SpacetimeDB URI"))
                    .description(OptionDescription.of(Component.literal("HTTP URL of SpacetimeDB instance (e.g. http://127.0.0.1:3000)")))
                    .binding("http://127.0.0.1:3000", () -> config.uri, val -> config.uri = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Hot Database"))
                    .description(OptionDescription.of(Component.literal("Active server hot database module (e.g. soupkitzhot, prisonhot)")))
                    .binding("soupkitzhot", () -> config.hotDb, val -> config.hotDb = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Control Database"))
                    .description(OptionDescription.of(Component.literal("Dedicated orchestrator control database (fabricdbug_control)")))
                    .binding("fabricdbug_control", () -> config.controlDb, val -> config.controlDb = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<Integer>createBuilder()
                    .name(Component.literal("Position Poll Ticks"))
                    .description(OptionDescription.of(Component.literal("Interval in ticks between position sync polls")))
                    .binding(5, () -> config.positionPollTicks, val -> config.positionPollTicks = val)
                    .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1, 40).step(1))
                    .build())
                .option(Option.<Integer>createBuilder()
                    .name(Component.literal("Command Poll Ticks"))
                    .description(OptionDescription.of(Component.literal("Interval in ticks between command queue polls")))
                    .binding(5, () -> config.commandPollTicks, val -> config.commandPollTicks = val)
                    .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1, 40).step(1))
                    .build())
                .build())

            // Category 2: Separate client-memory ESP from server telemetry.
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("ESP & Authority Diagnostics"))
                .tooltip(Component.literal("Debug-only visuals; server telemetry is not itself a PerceptionAuthority verdict"))
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Memory ESP"))
                    .description(OptionDescription.of(Component.literal("Draw player entities currently present in the Minecraft client world")))
                    .binding(true, () -> config.memoryEspEnabled, val -> config.memoryEspEnabled = val)
                    .controller(BooleanControllerBuilder::create)
                    .build())
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Server ESP"))
                    .description(OptionDescription.of(Component.literal("Draw player positions supplied by SpacetimeDB Hot telemetry")))
                    .binding(true, () -> config.serverEspEnabled, val -> config.serverEspEnabled = val)
                    .controller(BooleanControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Box Style"))
                    .description(OptionDescription.of(Component.literal("Use 'box' or 'corners'")))
                    .binding("box", () -> config.espBoxMode, val -> config.espBoxMode = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Tracers"))
                    .description(OptionDescription.of(Component.literal("Draw source-colored camera-to-player lines")))
                    .binding(true, () -> config.tracersEnabled, val -> config.tracersEnabled = val)
                    .controller(BooleanControllerBuilder::create)
                    .build())
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Through-Wall Chams"))
                    .description(OptionDescription.of(Component.literal("Translucent server-position silhouettes; developer diagnostics only")))
                    .binding(false, () -> config.chamsEnabled, val -> config.chamsEnabled = val)
                    .controller(BooleanControllerBuilder::create)
                    .build())
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Client Chunk Grid"))
                    .description(OptionDescription.of(Component.literal("Outline locally loaded chunks near the player; does not show server occlusion state")))
                    .binding(false, () -> config.chunkDebugEnabled, val -> config.chunkDebugEnabled = val)
                    .controller(BooleanControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Memory ESP Color"))
                    .description(OptionDescription.of(Component.literal("Hex color, e.g. #40C4FF")))
                    .binding("#40C4FF", () -> config.memoryEspColor, val -> config.memoryEspColor = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Server + Client Color"))
                    .description(OptionDescription.of(Component.literal("Hex color for a Hot position also present in the client entity list")))
                    .binding("#69F0AE", () -> config.serverMatchedColor, val -> config.serverMatchedColor = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Server-Only Color"))
                    .description(OptionDescription.of(Component.literal("Hex color for Hot telemetry with no matching client entity; this is not a certified hidden verdict")))
                    .binding("#FF5252", () -> config.serverOnlyColor, val -> config.serverOnlyColor = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Chunk Grid Color"))
                    .description(OptionDescription.of(Component.literal("Hex color, optionally including alpha, e.g. #6677AAFF")))
                    .binding("#6677AAFF", () -> config.chunkGridColor, val -> config.chunkGridColor = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .build())

            // Category 3: Identity & Account Swapping
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Identity & Accounts"))
                .tooltip(Component.literal("Switch offline test usernames on the fly without launcher restarts"))
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Offline Username"))
                    .description(OptionDescription.of(Component.literal("Username to assume (e.g. DevPlayer_A, DevPlayer_B). Click Apply below.")))
                    .binding("", () -> config.offlineUsername, val -> config.offlineUsername = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Apply Offline Identity"))
                    .description(OptionDescription.of(Component.literal("Switches client identity to the entered offline username")))
                    .text(Component.literal("Switch Now"))
                    .action((yaclScreen, button) -> {
                        if (IdentityManager.applyOfflineUsername(config.offlineUsername)) {
                            notifyMessage("Switched identity to: " + config.offlineUsername);
                        }
                    })
                    .build())
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Restore Original Identity"))
                    .description(OptionDescription.of(Component.literal("Restores original profile: " + IdentityManager.getOriginalUsername())))
                    .text(Component.literal("Restore Original"))
                    .action((yaclScreen, button) -> {
                        if (IdentityManager.restoreOriginalIdentity()) {
                            notifyMessage("Restored original identity: " + IdentityManager.getOriginalUsername());
                        }
                    })
                    .build())
                .build())

            // Category 4: Sequences & Keybinds
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("Sequences & Recording"))
                .tooltip(Component.literal("Deterministic input scripts for regression tests"))
                .option(ButtonOption.createBuilder()
                    .name(Component.literal("Keybind Shortcuts"))
                    .description(OptionDescription.of(Component.literal("F8 = Start/Stop Recording | F9 = Cancel Recording | F7 = Quick Playback | F6 = Open Menu")))
                    .text(Component.literal("Saved Sequences: " + SequenceRecorder.listSavedSequences().size()))
                    .action((yaclScreen, button) -> {})
                    .build())
                .build())

            .save(config::save)
            .build()
            .generateScreen(parent);
    }

    private static void notifyMessage(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§b[FabricDBug] §f" + text));
        }
    }
}
