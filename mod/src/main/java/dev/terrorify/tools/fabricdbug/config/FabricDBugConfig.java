package dev.terrorify.tools.fabricdbug.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricDBugConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricdbug");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("fabricdbug.json");

    private static FabricDBugConfig instance;

    public boolean enabled = true;
    public String instanceId = "instance_a";
    public String uri = "http://127.0.0.1:3000";
    public String hotDb = "soupkitzhot";
    public String controlDb = "fabricdbug_control";
    public int positionPollTicks = 5;
    public int commandPollTicks = 5;
    public boolean tracersEnabled = true;
    public boolean memoryEspEnabled = true;
    public boolean serverEspEnabled = true;
    public boolean chamsEnabled = false;
    public boolean chunkDebugEnabled = false;
    public String espBoxMode = "box";
    public String memoryEspColor = "#40C4FF";
    public String serverMatchedColor = "#69F0AE";
    public String serverOnlyColor = "#FF5252";
    public String chunkGridColor = "#6677AAFF";
    public String offlineUsername = "";
    public static FabricDBugConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static FabricDBugConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                FabricDBugConfig cfg = GSON.fromJson(reader, FabricDBugConfig.class);
                if (cfg != null) return cfg;
            } catch (Exception e) {
                LOGGER.error("Failed to read fabricdbug.json config", e);
            }
        }
        FabricDBugConfig cfg = new FabricDBugConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save fabricdbug.json config", e);
        }
    }
}
