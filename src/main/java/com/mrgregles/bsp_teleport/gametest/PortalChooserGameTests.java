package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.mixin.PlayerListAccessor;
import com.mrgregles.bsp_teleport.portal.PortalChoice;
import com.mrgregles.bsp_teleport.portal.PortalChooser;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** GameTests for the destination chooser's hold (D98, D99): a choosing player is kept still, and a cancel releases them. */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class PortalChooserGameTests {
    private static final String EMPTY = "empty";

    private static List<ServerPlayer> writable(MinecraftServer server) {
        return ((PlayerListAccessor) server.getPlayerList()).bsp_teleport$players();
    }

    @GameTest(template = EMPTY)
    public static void a_choosing_player_is_held_still_and_a_cancel_lets_them_go(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        BlockPos corePos = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlock(corePos, ModBlocks.PORTAL_CORE.get().defaultBlockState(), 3);
        PortalCoreBlockEntity core = (PortalCoreBlockEntity) h.getLevel().getBlockEntity(corePos);
        if (core == null) {
            h.fail("no core block entity");
            return;
        }
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 3)));
        player.setPos(at);
        writable(server).add(player);
        try {
            PortalDestination a = PortalDestination.warp("market", false).withLabel("Market");
            PortalDestination b = PortalDestination.warp("arena", true).withLabel("Arena");
            List<PortalChooser.Candidate> candidates = List.of(new PortalChooser.Candidate(0, a, PortalChoice.State.OK), new PortalChooser.Candidate(1, b, PortalChoice.State.OK));
            PortalChoice choice = new PortalChoice(Component.literal("Hub"), List.of(
                    new PortalChoice.Option(0, Component.literal("Market"), Component.literal("warp"), PortalChoice.State.OK),
                    new PortalChoice.Option(1, Component.literal("Arena"), Component.literal("portal warp"), PortalChoice.State.OK)));
            PortalChooser.start(player, core, candidates, choice);
            if (!PortalChooser.isChoosing(player)) {
                h.fail("the player should be choosing");
                return;
            }
            // Something moves them (knockback, another mod): the next tick puts them back.
            player.setPos(at.add(2, 0, 0));
            PortalChooser.tick(server);
            if (player.position().distanceTo(at) > 0.01) {
                h.fail("the hold did not put the player back: " + player.position());
                return;
            }
            // A pressed greyed entry is ignored; the player stays held.
            PortalChooser.answer(player, 7);
            if (!PortalChooser.isChoosing(player)) {
                h.fail("an unknown option index should not end the choice");
                return;
            }
            PortalChooser.answer(player, -1);
            if (PortalChooser.isChoosing(player)) {
                h.fail("cancel should end the choice");
                return;
            }
            if (PortalChooser.heldAt(player) != null) {
                h.fail("nothing should be held after cancel");
                return;
            }
            h.succeed();
        } finally {
            writable(server).remove(player);
        }
    }
}
