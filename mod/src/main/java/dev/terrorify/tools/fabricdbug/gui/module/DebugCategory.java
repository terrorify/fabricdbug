package dev.terrorify.tools.fabricdbug.gui.module;

public enum DebugCategory {
    PERCEPTION("Perception", 0xFF00D4FF),
    SPACETIME("Spacetime", 0xFF9D4EDD),
    AUTOMATION("Automation", 0xFFFF9E00),
    FLEET("Fleet & Dev", 0xFF2EC4B6);

    private final String displayName;
    private final int accentColor;

    DebugCategory(String displayName, int accentColor) {
        this.displayName = displayName;
        this.accentColor = accentColor;
    }

    public String getDisplayName() { return displayName; }
    public int getAccentColor() { return accentColor; }
}
