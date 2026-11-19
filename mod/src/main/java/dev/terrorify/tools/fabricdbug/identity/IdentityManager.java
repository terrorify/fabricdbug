package dev.terrorify.tools.fabricdbug.identity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages player identity for dual-instance testing without restarting the client.
 * Allows applying an offline username/UUID and restoring the original authenticated profile.
 */
public final class IdentityManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricdbug");
    private static User originalUser;
    private static boolean initialized = false;

    public static synchronized void init() {
        if (!initialized) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                originalUser = mc.getUser();
                initialized = true;
                LOGGER.info("FabricDBug cached original identity: {} ({})", originalUser.getName(), originalUser.getProfileId());
            }
        }
    }

    public static boolean applyOfflineUsername(String username) {
        if (username == null || username.trim().isEmpty()) return false;
        init();
        try {
            String trimmed = username.trim();
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + trimmed).getBytes(StandardCharsets.UTF_8));
            User newUser = new User(trimmed, offlineUuid, "offline_token", Optional.empty(), Optional.empty());
            setMinecraftUser(newUser);
            LOGGER.info("FabricDBug switched identity to: {} ({})", trimmed, offlineUuid);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to switch identity to offline user", e);
            return false;
        }
    }

    public static boolean restoreOriginalIdentity() {
        if (originalUser == null) return false;
        try {
            setMinecraftUser(originalUser);
            LOGGER.info("FabricDBug restored original identity: {} ({})", originalUser.getName(), originalUser.getProfileId());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to restore original identity", e);
            return false;
        }
    }

    public static String getCurrentUsername() {
        Minecraft mc = Minecraft.getInstance();
        return (mc != null && mc.getUser() != null) ? mc.getUser().getName() : "Unknown";
    }

    public static String getOriginalUsername() {
        return originalUser != null ? originalUser.getName() : "None";
    }

    public static boolean isCustomIdentity() {
        if (originalUser == null) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getUser() != null && !mc.getUser().getProfileId().equals(originalUser.getProfileId());
    }

    private static void setMinecraftUser(User user) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        for (Field f : Minecraft.class.getDeclaredFields()) {
            if (f.getType() == User.class) {
                f.setAccessible(true);
                f.set(mc, user);
                return;
            }
        }
        throw new NoSuchFieldException("Could not find User field in Minecraft class");
    }
}
