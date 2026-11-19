package dev.terrorify.tools.fabricdbug.gui.module;

import dev.terrorify.tools.fabricdbug.config.FabricDBugConfig;
import dev.terrorify.tools.fabricdbug.control.FreelookBridge;
import dev.terrorify.tools.fabricdbug.control.ZoomifyBridge;
import dev.terrorify.tools.fabricdbug.identity.IdentityManager;
import dev.terrorify.tools.fabricdbug.input.SequenceRecorder;

import java.util.*;
import java.util.stream.Collectors;

public final class ModuleManager {
    private static ModuleManager instance;
    private final List<DebugModule> modules = new ArrayList<>();
    private SequenceRecorder recorder;

    private ModuleManager() {
        FabricDBugConfig cfg = FabricDBugConfig.get();

        // Perception category: memory-backed and server-authoritative sources remain distinct.
        modules.add(new DebugModule("Memory ESP", DebugCategory.PERCEPTION,
            "Visualizes player entities currently present in Minecraft client memory",
            () -> cfg.memoryEspEnabled, val -> { cfg.memoryEspEnabled = val; cfg.save(); }));

        modules.add(new DebugModule("Server ESP", DebugCategory.PERCEPTION,
            "Visualizes SpacetimeDB player physics positions; red means telemetry-only, not a certified hide verdict",
            () -> cfg.serverEspEnabled, val -> { cfg.serverEspEnabled = val; cfg.save(); }));

        modules.add(new DebugModule("ESP Tracers", DebugCategory.PERCEPTION,
            "Draws tracers for enabled memory and server ESP sources",
            () -> cfg.tracersEnabled, val -> { cfg.tracersEnabled = val; cfg.save(); }));

        modules.add(new DebugModule("Chams", DebugCategory.PERCEPTION,
            "Draws translucent through-wall server-position silhouettes for debugging",
            () -> cfg.chamsEnabled, val -> { cfg.chamsEnabled = val; cfg.save(); }));

        modules.add(new DebugModule("Chunk Grid", DebugCategory.PERCEPTION,
            "Shows client-loaded chunk bounds around the local player; not a server occlusion verdict",
            () -> cfg.chunkDebugEnabled, val -> { cfg.chunkDebugEnabled = val; cfg.save(); }));

        // Spacetime Category
        modules.add(new DebugModule("Spacetime Sync", DebugCategory.SPACETIME,
            "Master toggle for direct SpacetimeDB HTTP/WebSocket sync",
            () -> cfg.enabled, val -> { cfg.enabled = val; cfg.save(); }));

        modules.add(new DebugModule("Fast Polling (1t)", DebugCategory.SPACETIME,
            "Reduces command and telemetry poll interval from 5 ticks to 1 tick (high reactivity)",
            () -> cfg.commandPollTicks == 1, val -> {
                cfg.commandPollTicks = val ? 1 : 5;
                cfg.positionPollTicks = val ? 1 : 5;
                cfg.save();
            }));

        // Automation Category
        modules.add(new DebugModule("Input Recorder", DebugCategory.AUTOMATION,
            "Records player movement and look angles into RLE deterministic sequences",
            () -> recorder != null && recorder.isRecording(),
            val -> { if (recorder != null) recorder.toggleRecording(); }));

        modules.add(new DebugModule("Freelook Cam", DebugCategory.AUTOMATION,
            "Engages third-person Freelook camera angle override",
            () -> false,
            val -> { if (val) FreelookBridge.start(null); else FreelookBridge.stop(); }));

        modules.add(new DebugModule("Zoomify Zoom", DebugCategory.AUTOMATION,
            "Forces Zoomify zoom active for distant observation",
            () -> false,
            val -> ZoomifyBridge.setZooming(val)));

        // Fleet Category
        modules.add(new DebugModule("Instance B Mode", DebugCategory.FLEET,
            "Switches cluster instance identity between instance_a and instance_b",
            () -> "instance_b".equalsIgnoreCase(cfg.instanceId),
            val -> {
                cfg.instanceId = val ? "instance_b" : "instance_a";
                cfg.save();
            }));

        modules.add(new DebugModule("Offline Identity", DebugCategory.FLEET,
            "Switches between offline testing username and original profile",
            IdentityManager::isCustomIdentity,
            val -> {
                if (val) {
                    String target = (cfg.offlineUsername != null && !cfg.offlineUsername.isBlank())
                        ? cfg.offlineUsername : "DevBot_" + cfg.instanceId;
                    IdentityManager.applyOfflineUsername(target);
                } else {
                    IdentityManager.restoreOriginalIdentity();
                }
            }));
    }

    public static synchronized ModuleManager get() {
        if (instance == null) {
            instance = new ModuleManager();
        }
        return instance;
    }

    public void setRecorder(SequenceRecorder recorder) {
        this.recorder = recorder;
    }

    public List<DebugModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<DebugModule> getModulesInCategory(DebugCategory category) {
        return modules.stream()
            .filter(m -> m.getCategory() == category)
            .collect(Collectors.toList());
    }
}
