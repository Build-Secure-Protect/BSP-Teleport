package com.mrgregles.bsp_teleport.gametest;

import com.mojang.authlib.GameProfile;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.LoginCheck;
import com.mrgregles.bsp_teleport.data.TeleportData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * B39: who is refused while still logging in. The login phase itself (Forge's negotiation, LuckPerms, the proxy's
 * redirect) needs a real proxy and client and is the owner's in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class LoginCheckGameTests {
    @GameTest(template = "empty")
    public static void a_login_without_access_to_a_restricted_server_is_refused(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        TeleportData data = TeleportData.get(server);
        String here = TeleportConfig.serverName();
        boolean enabledBefore = TeleportConfig.ACCESS_ENABLED.get();
        boolean restrictedBefore = data.restrictedServers().stream().anyMatch(s -> s.equalsIgnoreCase(here));
        GameProfile profile = new GameProfile(UUID.randomUUID(), "tp_login_test");
        try {
            TeleportConfig.ACCESS_ENABLED.set(true);
            data.setRestricted(here, false);
            h.assertTrue(LoginCheck.refusal(server, profile, List.of()) == null, "an open server lets everyone in");

            data.setRestricted(here, true);
            h.assertTrue(LoginCheck.refusal(server, profile, List.of()) != null, "a restricted server refuses a player with no grant");

            AccessGrant rented = new AccessGrant(profile.getId(), AccessGrant.Kind.SERVER, here, AccessGrant.Tier.RENT, System.currentTimeMillis() + 60_000, System.currentTimeMillis());
            h.assertTrue(LoginCheck.refusal(server, profile, List.of(rented)) == null, "a live rent lets them in");

            AccessGrant ran = rented.withExpiry(System.currentTimeMillis() - 1_000);
            h.assertTrue(LoginCheck.refusal(server, profile, List.of(ran)) != null, "a rent that ran out does not");

            AccessGrant main = new AccessGrant(profile.getId(), AccessGrant.Kind.SERVER, here, AccessGrant.Tier.MAIN, 0, System.currentTimeMillis());
            h.assertTrue(LoginCheck.refusal(server, profile, List.of(main)) == null, "their main server lets them in");

            TeleportConfig.ACCESS_ENABLED.set(false);
            h.assertTrue(LoginCheck.refusal(server, profile, List.of()) == null, "with the system off, nobody is refused");
            h.succeed();
        } finally {
            TeleportConfig.ACCESS_ENABLED.set(enabledBefore);
            data.setRestricted(here, restrictedBefore);
        }
    }
}
