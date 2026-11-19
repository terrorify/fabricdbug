package dev.terrorify.tools.fabricdbug.input;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Records in-game player inputs and look angles into RLE-compressed deterministic sequences.
 */
public final class SequenceRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricdbug");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SEQUENCES_DIR = FabricLoader.getInstance().getGameDir().resolve("fabricdbug").resolve("sequences");

    private final InputScriptEngine scriptEngine;
    private boolean recording = false;
    private final List<InputScriptEngine.Step> recordedSteps = new ArrayList<>();
    private Set<InputScriptEngine.Key> lastKeys = EnumSet.noneOf(InputScriptEngine.Key.class);
    private float lastYaw = 0f;
    private float lastPitch = 0f;
    private int stepTicks = 0;

    public SequenceRecorder(InputScriptEngine scriptEngine) {
        this.scriptEngine = scriptEngine;
    }

    public boolean isRecording() { return recording; }

    public synchronized void toggleRecording() {
        if (recording) {
            stopAndSave(null);
        } else {
            startRecording();
        }
    }

    public synchronized void startRecording() {
        recording = true;
        recordedSteps.clear();
        stepTicks = 0;
        lastKeys = sampleCurrentKeys();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            lastYaw = player.getYRot();
            lastPitch = player.getXRot();
        }
        notifyPlayer("§c● FabricDBug: Recording started (F8 to stop, F9 to cancel)...");
    }

    public synchronized void cancelRecording() {
        if (!recording) return;
        recording = false;
        recordedSteps.clear();
        stepTicks = 0;
        notifyPlayer("§7FabricDBug: Recording cancelled.");
    }

    public synchronized String stopAndSave(String customName) {
        if (!recording) return null;
        flushActiveStep();
        recording = false;

        if (recordedSteps.isEmpty()) {
            notifyPlayer("§eFabricDBug: No inputs recorded (empty sequence).");
            return null;
        }

        String name = (customName != null && !customName.isBlank()) ? customName
            : "seq_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        saveSequenceToFile(name, recordedSteps);
        notifyPlayer("§a✔ FabricDBug: Saved sequence §f" + name + " §7(" + recordedSteps.size() + " steps)");
        return name;
    }

    public synchronized void tick() {
        if (!recording) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        Set<InputScriptEngine.Key> currentKeys = sampleCurrentKeys();
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        boolean keysEqual = currentKeys.equals(lastKeys);
        boolean anglesEqual = Math.abs(currentYaw - lastYaw) < 0.2f && Math.abs(currentPitch - lastPitch) < 0.2f;

        if (keysEqual && anglesEqual) {
            stepTicks++;
        } else {
            flushActiveStep();
            lastKeys = currentKeys;
            lastYaw = currentYaw;
            lastPitch = currentPitch;
            stepTicks = 1;
        }
    }

    private void flushActiveStep() {
        if (stepTicks > 0) {
            recordedSteps.add(new InputScriptEngine.Step(stepTicks, EnumSet.copyOf(lastKeys), lastYaw, lastPitch));
            stepTicks = 0;
        }
    }

    public static void saveSequenceToFile(String name, List<InputScriptEngine.Step> steps) {
        try {
            Files.createDirectories(SEQUENCES_DIR);
            Path file = SEQUENCES_DIR.resolve(name + ".json");
            JsonObject root = new JsonObject();
            root.addProperty("name", name);
            JsonArray array = new JsonArray();
            for (InputScriptEngine.Step step : steps) {
                JsonObject s = new JsonObject();
                s.addProperty("ticks", step.ticks());
                s.addProperty("yaw", step.yaw());
                s.addProperty("pitch", step.pitch());
                JsonArray keys = new JsonArray();
                for (InputScriptEngine.Key k : step.keys()) keys.add(k.name().toLowerCase());
                s.add("keys", keys);
                array.add(s);
            }
            root.add("steps", array);
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(root, w);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save sequence {}", name, e);
        }
    }

    public static List<String> listSavedSequences() {
        List<String> list = new ArrayList<>();
        if (!Files.exists(SEQUENCES_DIR)) return list;
        try (var stream = Files.list(SEQUENCES_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .map(p -> p.getFileName().toString().replace(".json", ""))
                  .forEach(list::add);
        } catch (Exception ignored) {}
        list.sort(String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    public static List<InputScriptEngine.Step> loadSequence(String name) {
        Path file = SEQUENCES_DIR.resolve(name + ".json");
        if (!Files.exists(file)) return List.of();
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            JsonArray array = root.getAsJsonArray("steps");
            List<InputScriptEngine.Step> steps = new ArrayList<>();
            for (var el : array) {
                JsonObject s = el.getAsJsonObject();
                int ticks = s.get("ticks").getAsInt();
                Float yaw = s.has("yaw") ? s.get("yaw").getAsFloat() : null;
                Float pitch = s.has("pitch") ? s.get("pitch").getAsFloat() : null;
                Set<InputScriptEngine.Key> keys = EnumSet.noneOf(InputScriptEngine.Key.class);
                if (s.has("keys")) {
                    for (var k : s.getAsJsonArray("keys")) {
                        try {
                            keys.add(InputScriptEngine.Key.valueOf(k.getAsString().toUpperCase()));
                        } catch (Exception ignored) {}
                    }
                }
                steps.add(new InputScriptEngine.Step(ticks, keys, yaw, pitch));
            }
            return steps;
        } catch (Exception e) {
            LOGGER.error("Failed to load sequence {}", name, e);
            return List.of();
        }
    }

    public void quickPlayLatest() {
        List<String> list = listSavedSequences();
        if (list.isEmpty()) {
            notifyPlayer("§eFabricDBug: No saved sequences found.");
            return;
        }
        String latest = list.get(list.size() - 1);
        List<InputScriptEngine.Step> steps = loadSequence(latest);
        if (!steps.isEmpty()) {
            notifyPlayer("§b▶ FabricDBug: Replaying §f" + latest);
            scriptEngine.start(steps);
        }
    }

    private Set<InputScriptEngine.Key> sampleCurrentKeys() {
        Options opt = Minecraft.getInstance().options;
        Set<InputScriptEngine.Key> keys = EnumSet.noneOf(InputScriptEngine.Key.class);
        if (opt == null) return keys;
        if (opt.keyUp.isDown()) keys.add(InputScriptEngine.Key.FORWARD);
        if (opt.keyDown.isDown()) keys.add(InputScriptEngine.Key.BACK);
        if (opt.keyLeft.isDown()) keys.add(InputScriptEngine.Key.LEFT);
        if (opt.keyRight.isDown()) keys.add(InputScriptEngine.Key.RIGHT);
        if (opt.keyJump.isDown()) keys.add(InputScriptEngine.Key.JUMP);
        if (opt.keySprint.isDown()) keys.add(InputScriptEngine.Key.SPRINT);
        if (opt.keyShift.isDown()) keys.add(InputScriptEngine.Key.SNEAK);
        if (opt.keyAttack.isDown()) keys.add(InputScriptEngine.Key.ATTACK);
        if (opt.keyUse.isDown()) keys.add(InputScriptEngine.Key.USE);
        return keys;
    }

    private static void notifyPlayer(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
