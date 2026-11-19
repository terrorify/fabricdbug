package dev.terrorify.tools.fabricdbug.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.terrorify.tools.fabricdbug.gui.module.DebugCategory;
import dev.terrorify.tools.fabricdbug.gui.module.DebugModule;
import dev.terrorify.tools.fabricdbug.gui.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LiquidBounce-style ClickGUI framework featuring modular draggable category panels.
 */
public class ClickGuiScreen extends Screen {
    private final Screen parent;
    private final List<CategoryPanel> panels = new ArrayList<>();
    private CategoryPanel draggingPanel = null;
    private double dragOffsetX = 0;
    private double dragOffsetY = 0;
    private DebugModule hoveredModule = null;

    public ClickGuiScreen(Screen parent) {
        super(Component.literal("FabricDBug ClickGUI"));
        this.parent = parent;

        int startX = 25;
        int startY = 30;
        int panelWidth = 105;
        int spacing = 115;

        for (DebugCategory cat : DebugCategory.values()) {
            panels.add(new CategoryPanel(cat, startX, startY, panelWidth, ModuleManager.get().getModulesInCategory(cat)));
            startX += spacing;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // Translucent dark screen backdrop
        extractor.fill(0, 0, width, height, 0x6E0A0A10);

        // Branding top bar
        extractor.fill(0, 0, width, 18, 0xE0101018);
        extractor.fill(0, 18, width, 19, 0xFF00D4FF);
        extractor.text(font, "§b§lFabricDBug §7| §fSpacetime Oracle & Debug Client §7(Press §fRSHIFT §7to close)", 10, 5, 0xFFFFFFFF, false);

        hoveredModule = null;

        // Render each category panel
        for (CategoryPanel panel : panels) {
            panel.render(extractor, font, mouseX, mouseY);
            DebugModule hovered = panel.getHoveredModule(mouseX, mouseY);
            if (hovered != null) {
                hoveredModule = hovered;
            }
        }

        // Draw description tooltip bar at bottom if hovering a module
        if (hoveredModule != null) {
            extractor.fill(0, height - 20, width, height, 0xE5101016);
            extractor.fill(0, height - 21, width, height - 20, hoveredModule.getCategory().getAccentColor());
            extractor.text(font, "§b" + hoveredModule.getName() + " §7- §f" + hoveredModule.getDescription(), 10, height - 15, 0xFFFFFFFF, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean wasHandled) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        for (CategoryPanel panel : panels) {
            if (panel.isHoveringHeader(mx, my)) {
                if (button == 0) { // Left click header: start drag
                    draggingPanel = panel;
                    dragOffsetX = mx - panel.x;
                    dragOffsetY = my - panel.y;
                    return true;
                } else if (button == 1) { // Right click header: toggle collapse
                    panel.collapsed = !panel.collapsed;
                    return true;
                }
            } else if (!panel.collapsed && panel.isHoveringBody(mx, my)) {
                if (button == 0) { // Left click module: toggle
                    DebugModule mod = panel.getModuleAt(mx, my);
                    if (mod != null) {
                        mod.toggle();
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, wasHandled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingPanel = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (draggingPanel != null) {
            draggingPanel.x = (int) (event.x() - dragOffsetX);
            draggingPanel.y = (int) (event.y() - dragOffsetY);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE || event.key() == 344 /* GLFW_KEY_RIGHT_SHIFT */) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreenAndShow(parent);
        } else {
            Minecraft.getInstance().setScreenAndShow(null);
        }
    }

    /**
     * Draggable category window containing stacked module toggle buttons.
     */
    private static class CategoryPanel {
        final DebugCategory category;
        int x, y, width;
        final List<DebugModule> modules;
        boolean collapsed = false;
        static final int HEADER_HEIGHT = 18;
        static final int MODULE_HEIGHT = 16;

        CategoryPanel(DebugCategory category, int x, int y, int width, List<DebugModule> modules) {
            this.category = category;
            this.x = x;
            this.y = y;
            this.width = width;
            this.modules = modules;
        }

        boolean isHoveringHeader(double mx, double my) {
            return mx >= x && mx <= x + width && my >= y && my <= y + HEADER_HEIGHT;
        }

        boolean isHoveringBody(double mx, double my) {
            int bodyHeight = modules.size() * MODULE_HEIGHT;
            return mx >= x && mx <= x + width && my >= y + HEADER_HEIGHT && my <= y + HEADER_HEIGHT + bodyHeight;
        }

        DebugModule getModuleAt(double mx, double my) {
            if (collapsed || !isHoveringBody(mx, my)) return null;
            int relY = (int) (my - (y + HEADER_HEIGHT));
            int idx = relY / MODULE_HEIGHT;
            if (idx >= 0 && idx < modules.size()) return modules.get(idx);
            return null;
        }

        DebugModule getHoveredModule(double mx, double my) {
            return getModuleAt(mx, my);
        }

        void render(GuiGraphicsExtractor extractor, net.minecraft.client.gui.Font font, int mx, int my) {
            // Header background
            extractor.fill(x, y, x + width, y + HEADER_HEIGHT, 0xEE161620);
            // Category accent top bar
            extractor.fill(x, y, x + width, y + 2, category.getAccentColor());
            // Header text
            String titleText = category.getDisplayName() + (collapsed ? " §7[+]" : "");
            extractor.centeredText(font, titleText, x + width / 2, y + 5, 0xFFFFFFFF);

            // Render module rows if expanded
            if (!collapsed) {
                int modY = y + HEADER_HEIGHT;
                for (DebugModule mod : modules) {
                    boolean enabled = mod.isEnabled();
                    boolean hovered = mx >= x && mx <= x + width && my >= modY && my < modY + MODULE_HEIGHT;

                    // Module row background
                    int bgColor = enabled ? 0xDD1B3450 : 0xCC111118;
                    extractor.fill(x, modY, x + width, modY + MODULE_HEIGHT, bgColor);

                    // Active left indicator strip
                    if (enabled) {
                        extractor.fill(x, modY, x + 3, modY + MODULE_HEIGHT, category.getAccentColor());
                    }

                    // Hover highlight overlay
                    if (hovered) {
                        extractor.fill(x, modY, x + width, modY + MODULE_HEIGHT, 0x22FFFFFF);
                    }

                    // Module text
                    int textColor = enabled ? 0xFFFFFFFF : 0xFFA0A0AA;
                    extractor.text(font, mod.getName(), x + 6, modY + 4, textColor, false);

                    // Status dot
                    int statusColor = enabled ? 0xFF00E676 : 0xFF555560;
                    extractor.fill(x + width - 7, modY + 6, x + width - 3, modY + 10, statusColor);

                    modY += MODULE_HEIGHT;
                }
                // Bottom outline
                extractor.fill(x, modY, x + width, modY + 1, 0x55444455);
            }
        }
    }
}
