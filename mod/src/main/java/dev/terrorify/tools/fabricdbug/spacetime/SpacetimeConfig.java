package dev.terrorify.tools.fabricdbug.spacetime;

import dev.terrorify.tools.fabricdbug.config.FabricDBugConfig;

/** Runtime configuration for the direct SpacetimeDB debug feed. */
public final class SpacetimeConfig {
    private SpacetimeConfig() {}

    public static boolean isEnabled() {
        return FabricDBugConfig.get().enabled;
    }

    public static String getUri() {
        return FabricDBugConfig.get().uri;
    }

    public static String getHotDb() {
        return FabricDBugConfig.get().hotDb;
    }

    public static int getPositionPollTicks() {
        return FabricDBugConfig.get().positionPollTicks;
    }

    public static int getCommandPollTicks() {
        return FabricDBugConfig.get().commandPollTicks;
    }
}
