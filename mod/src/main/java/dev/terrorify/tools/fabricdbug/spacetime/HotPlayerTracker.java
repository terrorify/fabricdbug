package dev.terrorify.tools.fabricdbug.spacetime;

import com.google.gson.JsonArray;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authoritative player positions read directly from Hot; never from client entity packets. */
public final class HotPlayerTracker {
    private static final long ONLINE_FRESHNESS_MS = 10_000L;
    public record Position(String uuid, String worldId, String serverId,
                           double x, double y, double z, long timestamp) {}

    private final SpacetimeClient client = new SpacetimeClient();
    private final ConcurrentHashMap<String, Position> positions = new ConcurrentHashMap<>();
    private final AtomicBoolean queryRunning = new AtomicBoolean();
    private int ticks;

    public Map<String, Position> snapshot() { return Map.copyOf(positions); }

    public void tick() {
        if (!SpacetimeConfig.isEnabled() || ++ticks % SpacetimeConfig.getPositionPollTicks() != 0
            || !queryRunning.compareAndSet(false, true)) return;
        client.sqlRowsAsync("SELECT player_uuid, world_id, server_id, x, y, z, timestamp FROM player_physics_state")
            .whenComplete((rows, error) -> {
                try {
                    if (error != null) return;
                    ConcurrentHashMap<String, Position> next = new ConcurrentHashMap<>();
                    long now = System.currentTimeMillis();
                    for (var element : rows) {
                        JsonArray row = element.getAsJsonArray();
                        Position position = new Position(row.get(0).getAsString(), row.get(1).getAsString(),
                            row.get(2).getAsString(), row.get(3).getAsDouble(), row.get(4).getAsDouble(),
                            row.get(5).getAsDouble(), row.get(6).getAsLong());
                        if (Math.abs(now - position.timestamp()) <= ONLINE_FRESHNESS_MS) {
                            next.put(position.uuid(), position);
                        }
                    }
                    positions.clear();
                    positions.putAll(next);
                } finally {
                    queryRunning.set(false);
                }
            });
    }
}
