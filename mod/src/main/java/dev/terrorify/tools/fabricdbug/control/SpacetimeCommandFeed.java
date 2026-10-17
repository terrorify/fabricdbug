package dev.terrorify.tools.fabricdbug.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.terrorify.tools.fabricdbug.input.InputScriptEngine;
import dev.terrorify.tools.fabricdbug.spacetime.SpacetimeClient;
import dev.terrorify.tools.fabricdbug.spacetime.SpacetimeConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Consumes custom command intents from Hot. Agents issue the existing
 * command_intent_execute reducer; no local MCP/REST control server is needed.
 * Payload examples: {"action":"stop"} or {"action":"run_script","steps":[...]}
 */
public final class SpacetimeCommandFeed {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricdbug");
    private final SpacetimeClient client = new SpacetimeClient();
    private final InputScriptEngine scripts;
    private String targetUuid;
    private long lastCommandId;
    private boolean initialized;
    private int ticks;
    private boolean polling;

    public SpacetimeCommandFeed(InputScriptEngine scripts) { this.scripts = scripts; }

    public void onJoin() {
        if (!SpacetimeConfig.isEnabled()) return;
        if (Minecraft.getInstance().getUser() == null) return;
        UUID uuid = Minecraft.getInstance().getUser().getProfileId();
        targetUuid = uuid == null ? null : uuid.toString();
        initialized = false;
        lastCommandId = 0;
    }

    public void onDisconnect() {
        targetUuid = null;
        initialized = false;
        scripts.stop();
    }

    public void tick() {
        if (!SpacetimeConfig.isEnabled() || targetUuid == null
            || ++ticks % SpacetimeConfig.getCommandPollTicks() != 0 || polling) return;
        polling = true;
        if (!initialized) {
            client.sqlRowsAsync("SELECT id FROM command_intent")
                .thenAccept(rows -> Minecraft.getInstance().execute(() -> {
                    for (var row : rows) lastCommandId = Math.max(lastCommandId, row.getAsJsonArray().get(0).getAsLong());
                    initialized = true;
                    polling = false;
                }));
            return;
        }
        String safeUuid = targetUuid.replace("'", "''");
        client.sqlRowsAsync("SELECT id, payload FROM command_intent WHERE target_uuid = '" + safeUuid
            + "' AND intent_type = 11 AND id > " + lastCommandId)
            .thenAccept(rows -> Minecraft.getInstance().execute(() -> {
                var ordered = new ArrayList<JsonArray>();
                for (var element : rows) ordered.add(element.getAsJsonArray());
                ordered.sort(java.util.Comparator.comparingLong(row -> row.get(0).getAsLong()));
                for (var element : ordered) {
                    JsonArray row = element.getAsJsonArray();
                    if (row.get(0).getAsLong() <= lastCommandId) continue;
                    lastCommandId = Math.max(lastCommandId, row.get(0).getAsLong());
                    apply(row.get(1).getAsString());
                }
                polling = false;
            }));
    }

    private void apply(String payload) {
        try {
            JsonObject command = JsonParser.parseString(payload).getAsJsonObject();
            switch (command.get("action").getAsString()) {
                case "stop" -> scripts.stop();
                case "run_script" -> scripts.start(parseSteps(command.getAsJsonArray("steps")));
                case "freelook_start" -> FreelookBridge.start(
                    command.has("perspective") ? command.get("perspective").getAsString() : null);
                case "freelook_stop" -> FreelookBridge.stop();
                case "zoom_start" -> ZoomifyBridge.setZooming(true);
                case "zoom_stop" -> ZoomifyBridge.setZooming(false);
                case "screenshot" -> ScreenshotCapture.capture(client, targetUuid);
                case "snapshot" -> snapshot(command);
                case "chat_command" -> {
                    String value = command.get("command").getAsString();
                    String normalized = value.startsWith("/") ? value.substring(1) : value;
                    if (Minecraft.getInstance().getConnection() != null && !normalized.isBlank()) {
                        Minecraft.getInstance().getConnection().sendCommand(normalized);
                    }
                }
                default -> LOGGER.debug("FabricDBug ignored command action {}", command.get("action"));
            }
        } catch (Exception error) {
            LOGGER.warn("FabricDBug rejected command payload: {}", error.toString());
        }
    }

    private void snapshot(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null || minecraft.level == null) return;
        JsonObject state = new JsonObject();
        state.addProperty("request", command.has("request") ? command.get("request").getAsString() : "");
        state.addProperty("x", player.getX());
        state.addProperty("y", player.getY());
        state.addProperty("z", player.getZ());
        state.addProperty("yaw", player.getYRot());
        state.addProperty("pitch", player.getXRot());
        state.addProperty("headYaw", player.getYHeadRot());
        int centerChunkX = player.chunkPosition().x();
        int centerChunkZ = player.chunkPosition().z();
        state.addProperty("chunkX", centerChunkX);
        state.addProperty("chunkZ", centerChunkZ);
        state.addProperty("loadedChunkCount", minecraft.level.getChunkSource().getLoadedChunksCount());
        JsonArray missingChunks = new JsonArray();
        for (int chunkX = centerChunkX - 6; chunkX <= centerChunkX + 6; chunkX++) {
            for (int chunkZ = centerChunkZ - 6; chunkZ <= centerChunkZ + 6; chunkZ++) {
                if (!minecraft.level.hasChunk(chunkX, chunkZ)) {
                    JsonArray coordinate = new JsonArray();
                    coordinate.add(chunkX);
                    coordinate.add(chunkZ);
                    missingChunks.add(coordinate);
                }
            }
        }
        state.add("missingChunks", missingChunks);
        state.addProperty("health", player.getHealth());
        state.addProperty("creative", player.getAbilities().instabuild);
        state.addProperty("held", player.getMainHandItem().toString());
        state.addProperty("hit", String.valueOf(minecraft.hitResult));
        JsonArray players = new JsonArray();
        for (var other : minecraft.level.players()) {
            JsonObject entity = new JsonObject();
            entity.addProperty("uuid", other.getUUID().toString());
            entity.addProperty("x", other.getX());
            entity.addProperty("y", other.getY());
            entity.addProperty("z", other.getZ());
            entity.addProperty("yaw", other.getYRot());
            entity.addProperty("pitch", other.getXRot());
            entity.addProperty("headYaw", other.getYHeadRot());
            players.add(entity);
        }
        state.add("players", players);
        JsonArray blocks = new JsonArray();
        if (command.has("blocks")) for (var element : command.getAsJsonArray("blocks")) {
            JsonArray coordinates = element.getAsJsonArray();
            var position = new net.minecraft.core.BlockPos(coordinates.get(0).getAsInt(), coordinates.get(1).getAsInt(), coordinates.get(2).getAsInt());
            JsonObject block = new JsonObject();
            block.add("position", coordinates);
            block.addProperty("state", minecraft.level.getBlockState(position).toString());
            blocks.add(block);
        }
        state.add("blocks", blocks);
        JsonArray args = new JsonArray();
        args.add(targetUuid);
        args.add("snapshot");
        args.add(state.toString());
        client.callReducerAsync("report_fabricdbug_event", args);
    }

    private static ArrayList<InputScriptEngine.Step> parseSteps(JsonArray steps) {
        ArrayList<InputScriptEngine.Step> parsed = new ArrayList<>();
        for (var element : steps) {
            JsonObject step = element.getAsJsonObject();
            EnumSet<InputScriptEngine.Key> keys = EnumSet.noneOf(InputScriptEngine.Key.class);
            for (var key : step.getAsJsonArray("keys")) {
                keys.add(InputScriptEngine.Key.valueOf(key.getAsString().toUpperCase()));
            }
            Float yaw = step.has("yaw") ? step.get("yaw").getAsFloat() : null;
            Float pitch = step.has("pitch") ? step.get("pitch").getAsFloat() : null;
            parsed.add(new InputScriptEngine.Step(step.get("ticks").getAsInt(), keys, yaw, pitch));
        }
        return parsed;
    }
}
