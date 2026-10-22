package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.mixin.PlayerListAccessor;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import com.mrgregles.bsp_teleport.portal.PortalWarmup;
import com.mrgregles.bsp_teleport.portal.Portals;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GameTests for portal warm-ups (D118 to D122) and the Back destination (D110 to D113). The core stands without a
 * frame, so these drive the walk-in's later steps directly ({@code Portals.go} and {@code Portals.travel}) and tick the
 * warm-up by hand; the field itself (stepping out of a real opening, the B9 spot in front of a portal) needs a built
 * portal and is left to the owner's in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class PortalWarmupGameTests {
    private static final String EMPTY = "empty";

    private static List<ServerPlayer> writable(MinecraftServer server) {
        return ((PlayerListAccessor) server.getPlayerList()).bsp_teleport$players();
    }

    private static PortalCoreBlockEntity core(GameTestHelper h, int warmup) {
        BlockPos corePos = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlock(corePos, ModBlocks.PORTAL_CORE.get().defaultBlockState(), 3);
        PortalCoreBlockEntity core = (PortalCoreBlockEntity) h.getLevel().getBlockEntity(corePos);
        if (core == null) {
            h.fail("no core block entity");
        }
        core.setWarmupSeconds(warmup);
        return core;
    }

    private static Destination here(GameTestHelper h, int x, int z) {
        Vec3 v = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(x, 1, z)));
        return new Destination(TeleportConfig.serverName(), h.getLevel().dimension().location().toString(), v.x, v.y, v.z, "");
    }

    private static boolean at(ServerPlayer player, Destination d) {
        return player.position().distanceTo(new Vec3(d.x(), d.y(), d.z())) < 0.01;
    }

    @GameTest(template = EMPTY)
    public static void a_held_warmup_counts_down_keeps_the_player_still_then_moves_them(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        PortalCoreBlockEntity core = core(h, 1);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 3)));
        player.setPos(start);
        writable(server).add(player);
        try {
            Destination to = here(h, 4, 4);
            Portals.go(core, player, to, new PortalWarmup.Hold(start, 0f, 0f), false);
            if (!PortalWarmup.isWarming(player) || PortalWarmup.ticksLeft(player) != 20) {
                h.fail("a 1 s warm-up should be counting 20 ticks, got " + PortalWarmup.ticksLeft(player));
                return;
            }
            // Pushed during the countdown: the chooser's hold carries on and puts them back (D120).
            player.setPos(start.add(2, 0, 0));
            PortalWarmup.tick(server);
            if (player.position().distanceTo(start) > 0.01) {
                h.fail("the hold did not put the player back: " + player.position());
                return;
            }
            for (int i = 0; i < 18; i++) {
                PortalWarmup.tick(server);
            }
            if (!PortalWarmup.isWarming(player) || at(player, to)) {
                h.fail("the player went before the warm-up ended");
                return;
            }
            PortalWarmup.tick(server);
            if (PortalWarmup.isWarming(player) || !at(player, to)) {
                h.fail("the player should have gone at the end of the warm-up: " + player.position());
                return;
            }
            h.succeed();
        } finally {
            writable(server).remove(player);
        }
    }

    @GameTest(template = EMPTY)
    public static void stepping_out_or_being_hurt_cancels_a_warmup(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        PortalCoreBlockEntity core = core(h, 3);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 3)));
        player.setPos(start);
        writable(server).add(player);
        try {
            Destination to = here(h, 4, 4);
            // Not held, and not standing in an opening (this core has none): the first tick counts as stepping out.
            Portals.go(core, player, to, null, false);
            if (!PortalWarmup.isWarming(player)) {
                h.fail("the warm-up should have started");
                return;
            }
            PortalWarmup.tick(server);
            if (PortalWarmup.isWarming(player) || at(player, to)) {
                h.fail("stepping out should cancel without a move");
                return;
            }
            // Held this time, then hurt: the warm-up ends and the player stays (D119, cancelOnDamage on by default).
            Portals.go(core, player, to, new PortalWarmup.Hold(start, 0f, 0f), false);
            MinecraftForge.EVENT_BUS.post(new LivingHurtEvent(player, h.getLevel().damageSources().generic(), 1f));
            if (PortalWarmup.isWarming(player) || at(player, to)) {
                h.fail("being hurt should cancel without a move");
                return;
            }
            h.succeed();
        } finally {
            writable(server).remove(player);
        }
    }

    @GameTest(template = EMPTY)
    public static void a_back_entry_takes_the_player_to_their_back_spot_or_says_why_not(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        PortalCoreBlockEntity core = core(h, 0);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 3)));
        player.setPos(start);
        writable(server).add(player);
        try {
            Component why = Portals.backRefusal(player);
            if (why == null || !(why.getContents() instanceof TranslatableContents t) || !t.getKey().equals("message.bsp_teleport.portal.back_none")) {
                h.fail("a player with no /back spot should get 'Nowhere to go back to', got " + why);
                return;
            }
            Portals.travel(core, player, PortalDestination.back(), null);
            if (player.position().distanceTo(start) > 0.01) {
                h.fail("a player with no /back spot must stay");
                return;
            }
            Destination spot = here(h, 5, 2);
            Teleports.restore(player.getUUID(), 0L, Position.fromDestination(spot));
            if (Portals.backRefusal(player) != null) {
                h.fail("a local /back spot should be usable");
                return;
            }
            Portals.travel(core, player, PortalDestination.back(), null);
            if (!at(player, spot)) {
                h.fail("the Back entry should land the player on their /back spot: " + player.position());
                return;
            }
            // D115: the trip itself records where they left from as the new /back spot.
            Position after = Teleports.back(player);
            if (after == null || Math.abs(after.x() - start.x) > 0.01 || Math.abs(after.z() - start.z) > 0.01) {
                h.fail("after the trip /back should point where the player left from, got " + after);
                return;
            }
            h.succeed();
        } finally {
            writable(server).remove(player);
        }
    }
}
