package dev.terrorify.tools.fabricdbug.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class FabricDBugConfigScreen {
    public static Screen create(Screen parent) {
        FabricDBugConfig config = FabricDBugConfig.get();

        return YetAnotherConfigLib.createBuilder()
            .title(Component.literal("FabricDBug Configuration"))
            .category(ConfigCategory.createBuilder()
                .name(Component.literal("SpacetimeDB Connection"))
                .tooltip(Component.literal("Configure connection to local SpacetimeDB instance"))
                .option(Option.<Boolean>createBuilder()
                    .name(Component.literal("Enabled"))
                    .description(OptionDescription.of(Component.literal("Enable or disable FabricDBug telemetry and control")))
                    .binding(true, () -> config.enabled, val -> config.enabled = val)
                    .controller(BooleanControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("SpacetimeDB URI"))
                    .description(OptionDescription.of(Component.literal("HTTP URL of SpacetimeDB instance (e.g. http://127.0.0.1:3000)")))
                    .binding("http://127.0.0.1:3000", () -> config.uri, val -> config.uri = val)
                    .controller(StringControllerBuilder::create)
                    .build())
                .option(Option.<String>createBuilder()
                    .name(Component.literal("Hot Database"))
                    .description(OptionDescription.of(Component.literal("SpacetimeDB database module name (stomzydevhot or soupkitzhot)")))
                    .binding("stomzydevhot", () -> config.hotDb, val -> config.hotDb = val)
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
            .save(config::save)
            .build()
            .generateScreen(parent);
    }
}
