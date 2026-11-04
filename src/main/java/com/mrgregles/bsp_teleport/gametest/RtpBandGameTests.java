package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.band.BandData;
import com.mrgregles.bsp_teleport.band.BandService;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * B41: the band's MORE tab offers a random teleport only where /rtp works. This server's own name in the band's list
 * stands for "random here"; other servers are listed only when their heartbeat says /rtp is on there.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class RtpBandGameTests {
    @GameTest(template = "empty")
    public static void band_lists_random_teleport_only_where_it_is_on(GameTestHelper h) {
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        String here = TeleportConfig.serverName();
        boolean was = TeleportConfig.getOr(TeleportConfig.RTP_ENABLED, true);
        try {
            PlayerDirectory.update("RtpOn-Test", List.of(), true);
            PlayerDirectory.update("RtpOff-Test", List.of(), false);

            TeleportConfig.RTP_ENABLED.set(true);
            BandData on = BandService.build(player);
            h.assertTrue(on.rtpServers().contains(here), "random here is offered while /rtp is on: " + on.rtpServers());
            h.assertTrue(on.rtpServers().contains("RtpOn-Test"), "a server with /rtp on is listed: " + on.rtpServers());
            h.assertFalse(on.rtpServers().contains("RtpOff-Test"), "a server with /rtp off is not listed: " + on.rtpServers());

            TeleportConfig.RTP_ENABLED.set(false);
            BandData off = BandService.build(player);
            h.assertFalse(off.rtpServers().contains(here), "random here is gone while /rtp is off here: " + off.rtpServers());
            h.assertTrue(off.rtpServers().contains("RtpOn-Test"), "other servers keep their own setting: " + off.rtpServers());
        } finally {
            TeleportConfig.RTP_ENABLED.set(was);
            PlayerDirectory.markDown("RtpOn-Test");
            PlayerDirectory.markDown("RtpOff-Test");
        }
        h.succeed();
    }
}
