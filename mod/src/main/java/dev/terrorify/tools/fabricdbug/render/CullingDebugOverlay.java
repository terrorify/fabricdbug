package dev.terrorify.tools.fabricdbug.render;

import dev.terrorify.tools.fabricdbug.config.FabricDBugConfig;
import dev.terrorify.tools.fabricdbug.spacetime.HotPlayerTracker;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Developer-only comparison of client-tracked entities and SpacetimeDB-authoritative positions. */
public final class CullingDebugOverlay {
    private static final int CHUNK_RADIUS = 3;
    private final HotPlayerTracker tracker;

    public CullingDebugOverlay(HotPlayerTracker tracker) {
        this.tracker = tracker;
    }

    public void install() {
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            FabricDBugConfig cfg = FabricDBugConfig.get();
            if (!cfg.enabled || (!cfg.memoryEspEnabled && !cfg.serverEspEnabled
                && !cfg.chunkDebugEnabled && !cfg.chamsEnabled)) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;

            DrawableGizmoPrimitives depthTested = new DrawableGizmoPrimitives();
            DrawableGizmoPrimitives throughWalls = new DrawableGizmoPrimitives();
            Vec3 camera = context.levelState().cameraRenderState.pos;
            Vec3 tracerOrigin = hudTracerOrigin(camera);

            if (cfg.memoryEspEnabled) {
                int memoryColor = color(cfg.memoryEspColor, 0xFF40C4FF);
                for (Player player : mc.level.players()) {
                    if (player == mc.player || !player.isAlive()) continue;
                    drawEntity(depthTested, player.getBoundingBox(), memoryColor, cfg.espBoxMode);
                    if (cfg.tracersEnabled) {
                        depthTested.addLine(tracerOrigin, player.getBoundingBox().getCenter(), memoryColor, 1.5f);
                    }
                }
            }

            if (cfg.serverEspEnabled || cfg.chamsEnabled) {
                for (HotPlayerTracker.Position position : tracker.snapshot().values()) {
                    if (position.uuid().equalsIgnoreCase(mc.player.getUUID().toString())) continue;

                    Player clientPlayer = findClientPlayer(mc, position.uuid());
                    boolean receivedByClient = clientPlayer != null;
                    int color = color(receivedByClient ? cfg.serverMatchedColor : cfg.serverOnlyColor,
                        receivedByClient ? 0xFF69F0AE : 0xFFFF5252);
                    AABB box = receivedByClient
                        ? clientPlayer.getBoundingBox()
                        : new AABB(position.x() - 0.3, position.y(), position.z() - 0.3,
                            position.x() + 0.3, position.y() + 1.8, position.z() + 0.3);

                    if (cfg.serverEspEnabled) {
                        drawEntity(depthTested, box, color, cfg.espBoxMode);
                        if (cfg.tracersEnabled) {
                            depthTested.addLine(tracerOrigin,
                                new Vec3(box.getCenter().x, box.minY + (box.maxY - box.minY) * 0.5, box.getCenter().z), color, 2.0f);
                        }
                    }
                    if (cfg.chamsEnabled) {
                        addFilledBox(throughWalls, box, (color & 0x00FFFFFF) | 0x55000000);
                    }
                }
            }

            if (cfg.chunkDebugEnabled) {
                drawClientChunkGrid(depthTested, mc, cfg.chunkGridColor);
            }

            depthTested.submit(context.submitNodeCollector(), context.levelState().cameraRenderState, true);
            // Filled diagnostic silhouettes intentionally ignore depth so occluded server-only
            // telemetry can be inspected. This is a developer oracle visualization, not game state.
            throughWalls.submit(context.submitNodeCollector(), context.levelState().cameraRenderState, false);
        });
    }

    private static Player findClientPlayer(Minecraft mc, String uuid) {
        try {
            return mc.level.getPlayerByUUID(UUID.fromString(uuid));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void drawEntity(DrawableGizmoPrimitives gizmos, AABB box, int color, String mode) {
        if ("corners".equalsIgnoreCase(mode)) {
            addCornerBox(gizmos, box, color);
        } else {
            addWireBox(gizmos, box, color);
        }
    }

    private static void addWireBox(DrawableGizmoPrimitives gizmos, AABB box, int color) {
        Vec3[] bottom = {new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ),
            new Vec3(box.maxX, box.minY, box.maxZ), new Vec3(box.minX, box.minY, box.maxZ)};
        Vec3[] top = {new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ),
            new Vec3(box.maxX, box.maxY, box.maxZ), new Vec3(box.minX, box.maxY, box.maxZ)};
        for (int i = 0; i < 4; i++) {
            gizmos.addLine(bottom[i], bottom[(i + 1) % 4], color, 2.0f);
            gizmos.addLine(top[i], top[(i + 1) % 4], color, 2.0f);
            gizmos.addLine(bottom[i], top[i], color, 2.0f);
        }
    }

    private static void addCornerBox(DrawableGizmoPrimitives gizmos, AABB box, int color) {
        double x = (box.maxX - box.minX) * 0.28;
        double y = (box.maxY - box.minY) * 0.22;
        double z = (box.maxZ - box.minZ) * 0.28;
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            double bx = sx < 0 ? box.minX : box.maxX;
            double bz = sz < 0 ? box.minZ : box.maxZ;
            double tx = sx < 0 ? box.minX : box.maxX;
            double tz = sz < 0 ? box.minZ : box.maxZ;
            gizmos.addLine(new Vec3(bx, box.minY, bz), new Vec3(bx + sx * x, box.minY, bz), color, 2.5f);
            gizmos.addLine(new Vec3(bx, box.minY, bz), new Vec3(bx, box.minY + y, bz), color, 2.5f);
            gizmos.addLine(new Vec3(bx, box.minY, bz), new Vec3(bx, box.minY, bz + sz * z), color, 2.5f);
            gizmos.addLine(new Vec3(tx, box.maxY, tz), new Vec3(tx - sx * x, box.maxY, tz), color, 2.5f);
            gizmos.addLine(new Vec3(tx, box.maxY, tz), new Vec3(tx, box.maxY - y, tz), color, 2.5f);
            gizmos.addLine(new Vec3(tx, box.maxY, tz), new Vec3(tx, box.maxY, tz - sz * z), color, 2.5f);
        }
    }

    private static void addFilledBox(DrawableGizmoPrimitives gizmos, AABB box, int color) {
        Vec3 a = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 b = new Vec3(box.maxX, box.minY, box.minZ);
        Vec3 c = new Vec3(box.maxX, box.minY, box.maxZ);
        Vec3 d = new Vec3(box.minX, box.minY, box.maxZ);
        Vec3 e = new Vec3(box.minX, box.maxY, box.minZ);
        Vec3 f = new Vec3(box.maxX, box.maxY, box.minZ);
        Vec3 g = new Vec3(box.maxX, box.maxY, box.maxZ);
        Vec3 h = new Vec3(box.minX, box.maxY, box.maxZ);
        gizmos.addQuad(a, b, f, e, color);
        gizmos.addQuad(b, c, g, f, color);
        gizmos.addQuad(c, d, h, g, color);
        gizmos.addQuad(d, a, e, h, color);
        gizmos.addQuad(e, f, g, h, color);
        gizmos.addQuad(a, d, c, b, color);
    }

    private static void drawClientChunkGrid(DrawableGizmoPrimitives gizmos, Minecraft mc, String configuredColor) {
        int color = color(configuredColor, 0x8877AAFF);
        int playerChunkX = mc.player.blockPosition().getX() >> 4;
        int playerChunkZ = mc.player.blockPosition().getZ() >> 4;
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();
        for (int cx = playerChunkX - CHUNK_RADIUS; cx <= playerChunkX + CHUNK_RADIUS; cx++) {
            for (int cz = playerChunkZ - CHUNK_RADIUS; cz <= playerChunkZ + CHUNK_RADIUS; cz++) {
                BlockPos probe = new BlockPos(cx * 16, mc.player.blockPosition().getY(), cz * 16);
                if (!mc.level.hasChunkAt(probe)) continue;
                double x0 = cx * 16.0, x1 = x0 + 16.0;
                double z0 = cz * 16.0, z1 = z0 + 16.0;
                gizmos.addLine(new Vec3(x0, minY, z0), new Vec3(x1, minY, z0), color, 1.0f);
                gizmos.addLine(new Vec3(x1, minY, z0), new Vec3(x1, minY, z1), color, 1.0f);
                gizmos.addLine(new Vec3(x1, minY, z1), new Vec3(x0, minY, z1), color, 1.0f);
                gizmos.addLine(new Vec3(x0, minY, z1), new Vec3(x0, minY, z0), color, 1.0f);
                gizmos.addLine(new Vec3(x0, maxY, z0), new Vec3(x1, maxY, z0), color, 1.0f);
                gizmos.addLine(new Vec3(x1, maxY, z0), new Vec3(x1, maxY, z1), color, 1.0f);
                gizmos.addLine(new Vec3(x1, maxY, z1), new Vec3(x0, maxY, z1), color, 1.0f);
                gizmos.addLine(new Vec3(x0, maxY, z1), new Vec3(x0, maxY, z0), color, 1.0f);
                for (int i = 0; i < 4; i++) {
                    double x = i < 2 ? x0 : x1;
                    double z = (i & 1) == 0 ? z0 : z1;
                    gizmos.addLine(new Vec3(x, minY, z), new Vec3(x, maxY, z), color, 1.0f);
                }
            }
        }
    }

    private static Vec3 hudTracerOrigin(Vec3 cameraPosition) {
        var camera = Minecraft.getInstance().gameRenderer.mainCamera();
        var forward = camera.forwardVector();
        var up = camera.upVector();
        return cameraPosition.add(forward.x() * .18 - up.x() * .105,
            forward.y() * .18 - up.y() * .105, forward.z() * .18 - up.z() * .105);
    }

    private static int color(String value, int fallback) {
        if (value == null) return fallback;
        try {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            long parsed = Long.parseLong(hex, 16);
            if (hex.length() == 6) return (int) (0xFF000000L | parsed);
            if (hex.length() == 8) return (int) parsed;
        } catch (NumberFormatException ignored) {
            // Invalid config color falls back to the documented default.
        }
        return fallback;
    }
}
