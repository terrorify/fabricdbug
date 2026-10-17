package dev.terrorify.tools.fabricdbug.spacetime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Small asynchronous SpacetimeDB HTTP client; no MCP or agent protocol dependency. */
public final class SpacetimeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricdbug");
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public void callReducerAsync(String reducer, JsonArray args) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(SpacetimeConfig.getUri() + "/v1/database/" + SpacetimeConfig.getHotDb() + "/call/" + reducer))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(args.toString()))
            .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            if (error != null) LOGGER.debug("FabricDBug reducer failed: {}", error.toString());
            else if (response.statusCode() >= 300) LOGGER.debug("FabricDBug reducer returned {}", response.statusCode());
        });
    }

    public CompletableFuture<JsonArray> sqlRowsAsync(String sql) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(SpacetimeConfig.getUri() + "/v1/database/" + SpacetimeConfig.getHotDb() + "/sql"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString(sql))
            .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() >= 300) return new JsonArray();
            return rowsOf(response.body());
        }).exceptionally(error -> {
            LOGGER.debug("FabricDBug SQL query failed: {}", error.toString());
            return new JsonArray();
        });
    }

    private static JsonArray rowsOf(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonArray() || parsed.getAsJsonArray().isEmpty()) return new JsonArray();
            JsonObject statement = parsed.getAsJsonArray().get(0).getAsJsonObject();
            return statement.has("rows") ? statement.getAsJsonArray("rows") : new JsonArray();
        } catch (Exception ignored) {
            return new JsonArray();
        }
    }
}
