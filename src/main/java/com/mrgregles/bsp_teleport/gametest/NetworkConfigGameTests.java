package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.storage.NetworkConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * GameTests for D149: the database and Redis settings live in config/bsp_teleport-network.toml, because Forge sends the
 * server config file's bytes to every player who joins. Values left in the server file move over at the start and after
 * {@code /bsptp reload}, a value already in the network file wins, and no password reaches the log or stays in the server file.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class NetworkConfigGameTests {
    private static final String EMPTY = "empty";

    /** The server config as Forge's ConfigSync reads it to send at login. */
    private static String serverFile(GameTestHelper h) throws IOException {
        Path path = h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("bsp_teleport-server.toml");
        return Files.readString(path);
    }

    @GameTest(template = EMPTY)
    public static void connection_settings_move_out_of_the_synced_server_config(GameTestHelper h) {
        String dbPw = NetworkConfig.DB_PASSWORD.get(), redisPw = NetworkConfig.REDIS_PASSWORD.get(), host = NetworkConfig.DB_HOST.get();
        int port = NetworkConfig.DB_PORT.get();
        try {
            TeleportConfig.OLD_STORAGE_PASSWORD.set("pw-db-test");
            TeleportConfig.OLD_STORAGE_HOST.set("db.test");
            TeleportConfig.OLD_STORAGE_PORT.set(3307);
            // and a Redis password already set in the network file: that one wins
            TeleportConfig.OLD_REDIS_PASSWORD.set("pw-redis-old");
            NetworkConfig.REDIS_PASSWORD.set("pw-redis-new");
            TeleportConfig.SPEC.save();
            List<String> moved = NetworkConfig.moveFromServerConfig();
            h.assertTrue(NetworkConfig.DB_PASSWORD.get().equals("pw-db-test") && NetworkConfig.DB_HOST.get().equals("db.test")
                    && NetworkConfig.DB_PORT.get() == 3307, "the database settings moved to the network file");
            h.assertTrue(TeleportConfig.OLD_STORAGE_PASSWORD.get().isEmpty() && TeleportConfig.OLD_REDIS_PASSWORD.get().isEmpty()
                    && TeleportConfig.OLD_STORAGE_HOST.get().equals("localhost") && TeleportConfig.OLD_STORAGE_PORT.get() == 3306,
                    "the synced server config holds no password and no address any more");
            h.assertTrue(NetworkConfig.REDIS_PASSWORD.get().equals("pw-redis-new"), "a value already in the network file wins");
            String said = String.join("; ", moved);
            h.assertTrue(said.contains("database password moved") && said.contains("Redis password already set here") && said.contains("db.test"), "the log line says what moved: " + said);
            h.assertTrue(!said.contains("pw-db-test") && !said.contains("pw-redis"), "and never a password");
            String file = serverFile(h);
            h.assertTrue(!file.contains("pw-db-test") && !file.contains("pw-redis") && !file.contains("db.test"), "the server file on disk no longer has them");
            h.assertTrue(NetworkConfig.moveFromServerConfig().isEmpty(), "a second start finds nothing to move");

            // /bsptp reload: a password put back in the server file by hand is moved out at once
            TeleportConfig.OLD_REDIS_PASSWORD.set("pw-redis-again");
            TeleportConfig.SPEC.save();
            NetworkConfig.REDIS_PASSWORD.set(NetworkConfig.REDIS_PASSWORD.getDefault());
            TeleportConfig.reload(h.getLevel().getServer());
            h.assertTrue(TeleportConfig.OLD_REDIS_PASSWORD.get().isEmpty() && NetworkConfig.REDIS_PASSWORD.get().equals("pw-redis-again"), "reload moves it too");
            h.assertTrue(!serverFile(h).contains("pw-redis-again"), "and it is gone from the server file on disk");

            // nothing but the placeholders may name a password in the synced spec
            for (Field f : TeleportConfig.class.getFields()) {
                String name = f.getName();
                if ((name.contains("PASSWORD") || name.contains("HOST")) && !name.startsWith("OLD_")) {
                    h.fail("TeleportConfig." + name + " would put a connection setting in the synced server config");
                }
            }
        } catch (IOException e) {
            h.fail("could not read the server config file: " + e);
        } finally {
            TeleportConfig.OLD_STORAGE_PASSWORD.set(TeleportConfig.OLD_STORAGE_PASSWORD.getDefault());
            TeleportConfig.OLD_REDIS_PASSWORD.set(TeleportConfig.OLD_REDIS_PASSWORD.getDefault());
            TeleportConfig.OLD_STORAGE_HOST.set(TeleportConfig.OLD_STORAGE_HOST.getDefault());
            TeleportConfig.OLD_STORAGE_PORT.set(TeleportConfig.OLD_STORAGE_PORT.getDefault());
            NetworkConfig.DB_PASSWORD.set(dbPw);
            NetworkConfig.REDIS_PASSWORD.set(redisPw);
            NetworkConfig.DB_HOST.set(host);
            NetworkConfig.DB_PORT.set(port);
            NetworkConfig.SPEC.save();
            TeleportConfig.SPEC.save();
        }
        h.succeed();
    }
}
