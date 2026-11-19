package dev.terrorify.tools.fabricdbug.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import dev.terrorify.tools.fabricdbug.input.InputScriptEngine;
import dev.terrorify.tools.fabricdbug.spacetime.SpacetimeClient;
import dev.terrorify.tools.fabricdbug.spacetime.SpacetimeConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Consumes custom command intents from Hot. Agents issue the existing
 * command_intent_execute reducer; no local MCP/REST control server is needed.
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
    private boolean readyReported;

    // Deterministic catalog test state queue waiting for server stateID echo
    private final Queue<ItemStack> catalogQueue = new LinkedList<>();
    private boolean catalogRunning = false;
    private int catalogTotal = 0;
    private int catalogProcessed = 0;
    private int catalogFailures = 0;
    private ItemStack currentInFlightStack = null;
    private int inFlightSentStateId = -1;
    private int inFlightTimeout = 0;

    public SpacetimeCommandFeed(InputScriptEngine scripts) { this.scripts = scripts; }

    public void onJoin() {
        ensureTarget();
        reportEvent("joined", "{}");
    }

    public void onDisconnect() {
        scripts.stop();
        var minecraft = Minecraft.getInstance();
        JsonObject screenState = new JsonObject();
        var currentScreen = minecraft.gui.screen();
        if (currentScreen instanceof DisconnectedScreen ds) {
            screenState.addProperty("title", ds.getTitle().getString());
            screenState.addProperty("narration", ds.getNarrationMessage().getString());
        } else if (currentScreen != null) {
            screenState.addProperty("screen", currentScreen.getClass().getName());
        } else {
            screenState.addProperty("screen", "none");
        }
        reportEvent("disconnected", screenState.toString());
    }

    private void ensureTarget() {
        if (!SpacetimeConfig.isEnabled() || targetUuid != null) return;
        var user = Minecraft.getInstance().getUser();
        if (user == null || user.getProfileId() == null) return;
        targetUuid = user.getProfileId().toString();
        initialized = false;
        lastCommandId = 0;
        if (!readyReported) {
            readyReported = true;
            reportEvent("client_ready", "{\"screen\":\"title_or_menu\"}");
        }
    }

    private void reportEvent(String kind, String payload) {
        if (targetUuid == null) return;
        JsonArray args = new JsonArray();
        args.add(targetUuid);
        args.add(kind);
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("instance", SpacetimeConfig.getInstanceId());
        try {
            wrapped.add("data", JsonParser.parseString(payload));
        } catch (Exception e) {
            wrapped.addProperty("raw", payload);
        }
        args.add(wrapped.toString());
        client.callReducerAsync("report_fabricdbug_event", args);
    }

    public void tick() {
        ensureTarget();

        // Process catalog test state machine waiting for server stateID echo
        if (catalogRunning) {
            var minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.getConnection() != null) {
                var menu = minecraft.player.containerMenu;
                if (currentInFlightStack != null) {
                    boolean stateChanged = menu.getStateId() != inFlightSentStateId;
                    if (stateChanged) {
                        ItemStack slotStack = menu.getSlot(36).getItem();
                        boolean matches = ItemStack.matches(slotStack, currentInFlightStack);
                        if (matches) {
                            catalogProcessed++;
                        } else {
                            catalogFailures++;
                            JsonObject failure = new JsonObject();
                            failure.addProperty("index", catalogProcessed + catalogFailures);
                            failure.addProperty("expected", currentInFlightStack.toString());
                            failure.addProperty("actual", slotStack.toString());
                            reportEvent("catalog_failure", failure.toString());
                        }
                        currentInFlightStack = null;
                        inFlightTimeout = 0;
                    } else if (--inFlightTimeout <= 0) {
                        catalogFailures++;
                        JsonObject failure = new JsonObject();
                        failure.addProperty("index", catalogProcessed + catalogFailures);
                        failure.addProperty("expected", currentInFlightStack.toString());
                        failure.addProperty("error", "authoritative state acknowledgement timed out");
                        reportEvent("catalog_failure", failure.toString());
                        currentInFlightStack = null;
                    }
                }
                if (catalogProcessed > 0 && catalogProcessed % 250 == 0 && currentInFlightStack == null) {
                    reportEvent("catalog_progress", "{\"processed\":" + catalogProcessed + ",\"total\":" + catalogTotal + ",\"failures\":" + catalogFailures + "}");
                }

                if (currentInFlightStack == null) {
                    if (catalogQueue.isEmpty()) {
                        catalogRunning = false;
                        JsonObject report = new JsonObject();
                        report.addProperty("total", catalogTotal);
                        report.addProperty("processed", catalogProcessed);
                        report.addProperty("failures", catalogFailures);
                        reportEvent("catalog_complete", report.toString());
                    } else {
                        currentInFlightStack = catalogQueue.poll();
                        inFlightSentStateId = menu.getStateId();
                        inFlightTimeout = 40;
                        minecraft.getConnection().send(new ServerboundSetCreativeModeSlotPacket(36, currentInFlightStack));
                    }
                }
            } else {
                catalogRunning = false;
                reportEvent("catalog_complete", "{\"total\":" + catalogTotal + ",\"processed\":" + catalogProcessed + ",\"failures\":" + (catalogFailures + 1) + ",\"error\":\"client disconnected\"}");
            }
        }

        if (!SpacetimeConfig.isEnabled() || targetUuid == null
            || ++ticks % SpacetimeConfig.getCommandPollTicks() != 0 || polling) return;
        polling = true;
        String safeUuid = targetUuid.replace("'", "''");
        String safeInstance = SpacetimeConfig.getInstanceId().replace("'", "''");
        String targetFilter = "(target_uuid = '" + safeUuid + "' OR target_uuid = '" + safeInstance + "')";

        if (!initialized) {
            client.sqlRowsAsync("SELECT id, payload FROM command_intent WHERE " + targetFilter
                + " AND intent_type = 11")
                .thenAccept(rows -> Minecraft.getInstance().execute(() -> {
                    for (var element : rows) {
                        JsonArray row = element.getAsJsonArray();
                        long id = row.get(0).getAsLong();
                        lastCommandId = Math.max(lastCommandId, id);
                    }
                    // Establish a watermark without replaying previously executed commands.
                    initialized = true;
                    polling = false;
                }));
            return;
        }

        client.sqlRowsAsync("SELECT id, payload FROM command_intent WHERE " + targetFilter
            + " AND intent_type = 11 AND id > " + lastCommandId)
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
                case "connect" -> {
                    var minecraft = Minecraft.getInstance();
                    String address = command.has("address") ? command.get("address").getAsString() : "127.0.0.1:25565";
                    var server = new ServerData("FabricDBug local test", address, ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(new TitleScreen(), minecraft,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address),
                        server, false, null);
                    reportEvent("connect_requested", "{\"address\":\"" + address + "\"}");
                }
                case "stop" -> scripts.stop();
                case "run_script" -> scripts.start(parseSteps(command.getAsJsonArray("steps")));
                case "freelook_start" -> FreelookBridge.start(
                    command.has("perspective") ? command.get("perspective").getAsString() : null);
                case "freelook_stop" -> FreelookBridge.stop();
                case "zoom_start" -> ZoomifyBridge.setZooming(true);
                case "zoom_stop" -> ZoomifyBridge.setZooming(false);
                case "screenshot" -> ScreenshotCapture.capture(client, targetUuid);
                case "snapshot" -> snapshot(command);
                case "inventory_snapshot" -> inventorySnapshot(command);
                case "creative_set_slot" -> creativeSetSlot(command);
                case "inventory_click" -> inventoryClick(command);
                case "catalog_test" -> catalogTest(command);
                case "disconnect" -> disconnectClient(command);
                case "codec_probe" -> codecProbe(command);
                case "component_probe" -> componentProbe(command);
                case "private_auth" -> privateAuth();
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

    private void inventorySnapshot(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        var player = minecraft.player;
        if (player == null) return;
        var menu = player.containerMenu;
        var currentScreen = minecraft.gui.screen();

        JsonObject state = new JsonObject();
        state.addProperty("containerId", menu.containerId);
        state.addProperty("stateId", menu.getStateId());
        state.addProperty("screenConnection", currentScreen != null ? currentScreen.getClass().getName() : "none");

        ItemStack cursorItem = menu.getCarried();
        JsonObject cursorJson = new JsonObject();
        cursorJson.addProperty("item", cursorItem.getItem().toString());
        cursorJson.addProperty("count", cursorItem.getCount());
        cursorJson.addProperty("components", cursorItem.getComponents().toString());
        state.add("cursor", cursorJson);

        JsonArray slotsArray = new JsonArray();
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("index", i);
            slotJson.addProperty("containerSlot", slot.getContainerSlot());
            ItemStack stack = slot.getItem();
            slotJson.addProperty("item", stack.getItem().toString());
            slotJson.addProperty("count", stack.getCount());
            slotJson.addProperty("components", stack.getComponents().toString());
            slotsArray.add(slotJson);
        }
        state.add("slots", slotsArray);
        reportEvent("inventory_snapshot", state.toString());
    }

    private void creativeSetSlot(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return;
        int slot = command.get("slot").getAsInt();
        String itemSnbt = command.get("item").getAsString();
        int count = command.has("count") ? command.get("count").getAsInt() : 1;
        try {
            var itemInput = new ItemParser(minecraft.player.registryAccess()).parse(new StringReader(itemSnbt));
            ItemStack stack = itemInput.createItemStack(count);

            minecraft.getConnection().send(new ServerboundSetCreativeModeSlotPacket(slot, stack));
            reportEvent("creative_set_slot_result", "{\"status\":\"success\",\"slot\":" + slot + "}");
        } catch (Exception e) {
            reportEvent("creative_set_slot_result", "{\"status\":\"failure\",\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void inventoryClick(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;
        int containerId = command.has("containerId") ? command.get("containerId").getAsInt() : minecraft.player.containerMenu.containerId;
        int slotId = command.get("slotId").getAsInt();
        int button = command.get("button").getAsInt();
        String clickTypeStr = command.get("clickType").getAsString();
        ContainerInput containerInput = ContainerInput.valueOf(clickTypeStr.toUpperCase());

        minecraft.gameMode.handleContainerInput(containerId, slotId, button, containerInput, minecraft.player);
        reportEvent("inventory_click_result", "{\"status\":\"success\",\"slotId\":" + slotId + "}");
    }

    private void catalogTest(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null || !minecraft.player.getAbilities().instabuild) {
            reportEvent("catalog_complete", "{\"error\":\"connected creative player required\"}");
            return;
        }
        CreativeModeTabs.tryRebuildTabContents(minecraft.getConnection().enabledFeatures(), true, minecraft.player.registryAccess());
        catalogQueue.clear();
        for (Item item : BuiltInRegistries.ITEM) {
            catalogQueue.add(new ItemStack(item));
        }
        for (var tab : CreativeModeTabs.allTabs()) {
            for (ItemStack stack : tab.getDisplayItems()) {
                if (!stack.isEmpty()) {
                    catalogQueue.add(stack);
                }
            }
        }
        catalogTotal = catalogQueue.size();
        catalogProcessed = 0;
        catalogFailures = 0;
        currentInFlightStack = null;
        inFlightSentStateId = -1;
        catalogRunning = true;
        reportEvent("catalog_started", "{\"total\":" + catalogTotal + "}");
    }

    private void disconnectClient(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        JsonObject screenState = new JsonObject();
        var currentScreen = minecraft.gui.screen();
        if (currentScreen instanceof DisconnectedScreen ds) {
            screenState.addProperty("title", ds.getTitle().getString());
            screenState.addProperty("narration", ds.getNarrationMessage().getString());
        }
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().getConnection().disconnect(net.minecraft.network.chat.Component.literal("FabricDBug manual disconnect"));
        }
        reportEvent("disconnect_triggered", screenState.toString());
    }

    private void privateAuth() {
        var minecraft = Minecraft.getInstance();
        if (!Boolean.getBoolean("fabricdbug.background") || minecraft.getConnection() == null) {
            reportEvent("private_auth_result", "{\"status\":\"failure\",\"error\":\"connected background instance required\"}");
            return;
        }
        Path credential = minecraft.gameDirectory.toPath().resolve("fabricdbug/private-auth.json");
        try {
            JsonObject input = JsonParser.parseString(Files.readString(credential)).getAsJsonObject();
            String operation = input.get("operation").getAsString();
            String username = input.get("username").getAsString();
            String password = input.get("password").getAsString();
            if (!username.matches("[A-Za-z0-9_]{3,16}")
                || password.isEmpty() || password.length() > 64
                || password.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("invalid private auth input");
            }
            var payload = new net.minecraft.nbt.CompoundTag();
            String action;
            switch (operation) {
                case "register_username" -> {
                    action = "internode:auth_register_username";
                    payload.putString("username", username);
                }
                case "register_password" -> {
                    action = "internode:auth_register_password";
                    payload.putString("password", password);
                }
                case "register_confirm" -> {
                    action = "internode:auth_register";
                    payload.putString("confirm", password);
                }
                case "login" -> {
                    action = "internode:auth_login";
                    payload.putString("username", username);
                    payload.putString("password", password);
                }
                default -> throw new IllegalArgumentException("unknown private auth operation");
            }
            Files.delete(credential);
            minecraft.getConnection().send(new net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket(
                net.minecraft.resources.Identifier.parse(action), java.util.Optional.of(payload)));
            reportEvent("private_auth_result", "{\"status\":\"sent\",\"operation\":\"" + operation + "\"}");
        } catch (Exception error) {
            // Never echo credentials or parser diagnostics into the public command/event tables.
            reportEvent("private_auth_result", "{\"status\":\"failure\",\"error\":\"private auth file could not be consumed\"}");
        }
    }

    private void componentProbe(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        JsonArray results = new JsonArray();
        if (minecraft.player == null) {
            reportEvent("component_probe_result", "{\"error\":\"not connected\"}");
            return;
        }
        for (var element : command.getAsJsonArray("cases")) {
            JsonObject input = element.getAsJsonObject();
            JsonObject result = new JsonObject();
            result.add("id", input.get("id"));
            result.add("case", input.get("case"));
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), minecraft.player.registryAccess());
            try {
                buf.writeVarInt(input.get("id").getAsInt());
                buf.writeBytes(java.util.Base64.getDecoder().decode(input.get("payload").getAsString()));
                var type = net.minecraft.core.component.DataComponentType.STREAM_CODEC.decode(buf);
                probeValue(type, buf);
                result.addProperty("remaining", buf.readableBytes());
                result.addProperty("accepted", buf.readableBytes() == 0);
            } catch (Exception error) {
                result.addProperty("accepted", false);
                result.addProperty("error", error.toString());
            } finally { buf.release(); }
            results.add(result);
        }
        JsonObject output = new JsonObject();
        output.add("results", results);
        reportEvent("component_probe_result", output.toString());
    }

    private static <T> void probeValue(net.minecraft.core.component.DataComponentType<T> type, RegistryFriendlyByteBuf buf) {
        type.streamCodec().decode(buf);
    }

    private void codecProbe(JsonObject command) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            reportEvent("codec_probe_result", "{\"status\":\"error\",\"message\":\"player null\"}");
            return;
        }
        try {
            Path fabricDbugDir = minecraft.gameDirectory.toPath().resolve("fabricdbug");
            Files.createDirectories(fabricDbugDir);
            Path jsonlPath = fabricDbugDir.resolve("codec_probe-" + UUID.randomUUID() + ".jsonl");

            int tested = 0;
            int passed = 0;
            long totalTrustedBytes = 0;
            long totalUntrustedBytes = 0;

            try (BufferedWriter writer = Files.newBufferedWriter(jsonlPath)) {
                ArrayList<ItemStack> itemsToTest = new ArrayList<>();
                CreativeModeTabs.tryRebuildTabContents(minecraft.getConnection().enabledFeatures(), true, minecraft.player.registryAccess());
                for (Item item : BuiltInRegistries.ITEM) {
                    itemsToTest.add(new ItemStack(item));
                }
                for (var tab : CreativeModeTabs.allTabs()) {
                    for (ItemStack stack : tab.getDisplayItems()) {
                        if (!stack.isEmpty()) {
                            itemsToTest.add(stack);
                        }
                    }
                }

                long coverageIdsCount = itemsToTest.stream().map(stack -> BuiltInRegistries.ITEM.getId(stack.getItem())).distinct().count();
                var registryAccess = minecraft.player.registryAccess();

                for (ItemStack stack : itemsToTest) {
                    tested++;
                    RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
                    RegistryFriendlyByteBuf untrustedBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
                    try {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
                        int trustedBytes = buf.readableBytes();
                        totalTrustedBytes += trustedBytes;

                        ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC.encode(untrustedBuf, stack);
                        int untrustedBytes = untrustedBuf.readableBytes();
                        totalUntrustedBytes += untrustedBytes;

                        var patchBuilder = DataComponentPatch.builder();
                        var components = stack.getComponents();
                        for (var entry : components) {
                            patchBuilder.set(entry);
                        }
                        DataComponentPatch patch = patchBuilder.build();

                        JsonObject record = new JsonObject();
                        record.addProperty("item", stack.getItem().toString());
                        record.addProperty("count", stack.getCount());
                        record.addProperty("trustedBytes", trustedBytes);
                        record.addProperty("untrustedBytes", untrustedBytes);
                        record.addProperty("patchSize", patch.size());
                        byte[] trusted = new byte[buf.readableBytes()];
                        buf.getBytes(buf.readerIndex(), trusted);
                        byte[] untrusted = new byte[untrustedBuf.readableBytes()];
                        untrustedBuf.getBytes(untrustedBuf.readerIndex(), untrusted);
                        record.addProperty("trusted", java.util.Base64.getEncoder().encodeToString(trusted));
                        record.addProperty("untrusted", java.util.Base64.getEncoder().encodeToString(untrusted));
                        RegistryFriendlyByteBuf prototypeBuf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
                        try {
                            DataComponentPatch.STREAM_CODEC.encode(prototypeBuf, patch);
                            byte[] prototype = new byte[prototypeBuf.readableBytes()];
                            prototypeBuf.getBytes(prototypeBuf.readerIndex(), prototype);
                            record.addProperty("prototype", java.util.Base64.getEncoder().encodeToString(prototype));
                            record.addProperty("itemId", BuiltInRegistries.ITEM.getId(stack.getItem()));
                        } finally { prototypeBuf.release(); }

                        writer.write(record.toString());
                        writer.newLine();
                        passed++;
                    } catch (Exception inner) {
                        JsonObject failure = new JsonObject();
                        failure.addProperty("item", stack.getItem().toString());
                        failure.addProperty("error", inner.toString());
                        reportEvent("codec_probe_failure", failure.toString());
                    } finally {
                        buf.release();
                        untrustedBuf.release();
                    }
                }

                JsonObject result = new JsonObject();
                result.addProperty("status", passed == tested ? "success" : "failure");
                result.addProperty("coverageIdsCount", coverageIdsCount);
                result.addProperty("tested", tested);
                result.addProperty("passed", passed);
                result.addProperty("totalTrustedBytes", totalTrustedBytes);
                result.addProperty("totalUntrustedBytes", totalUntrustedBytes);
                result.addProperty("path", jsonlPath.toString());

                reportEvent("codec_probe_result", result.toString());
            }
        } catch (Exception e) {
            reportEvent("codec_probe_result", "{\"status\":\"error\",\"message\":\"" + e.toString().replace("\"", "\\\"") + "\"}");
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
