package dev.terrorify.tools.fabricdbug.gui.module;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Functional representation of an individual toggleable debug module in the ClickGUI.
 */
public class DebugModule {
    private final String name;
    private final DebugCategory category;
    private final String description;
    private final BooleanSupplier stateGetter;
    private final Consumer<Boolean> stateSetter;

    public DebugModule(String name, DebugCategory category, String description,
                       BooleanSupplier stateGetter, Consumer<Boolean> stateSetter) {
        this.name = name;
        this.category = category;
        this.description = description;
        this.stateGetter = stateGetter;
        this.stateSetter = stateSetter;
    }

    public String getName() { return name; }
    public DebugCategory getCategory() { return category; }
    public String getDescription() { return description; }

    public boolean isEnabled() {
        return stateGetter.getAsBoolean();
    }

    public void setEnabled(boolean enabled) {
        stateSetter.accept(enabled);
    }

    public void toggle() {
        setEnabled(!isEnabled());
    }
}
