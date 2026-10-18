package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.mixin.PlayerListAccessor;
import com.mrgregles.bsp_teleport.network.JoinLeaveMessages;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * GameTests for {@code network.suppressJoinLeaveMessages} (VA-T1). A "watcher" mock player sits in the server's player
 * list with a packet listener that records every system chat line it is sent. A second mock player is then taken
 * through the real login ({@code PlayerList.placeNewPlayer}) and logout ({@code onDisconnect}), the two methods the
 * mixins hook, and the watcher's lines say whether the vanilla join and leave announcements went out.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class JoinLeaveGameTests {
    private static final String EMPTY = "empty";

    /** A packet listener that keeps the plain text of every system chat line sent to its player. */
    private static final class Recording extends ServerGamePacketListenerImpl {
        final List<String> lines = new ArrayList<>();
        int infoUpdates;

        Recording(MinecraftServer server, Connection connection, ServerPlayer player) {
            super(server, connection, player);
        }

        /** System chat goes through this overload (with a send listener); the one-argument send calls it too. */
        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (packet instanceof ClientboundSystemChatPacket chat) {
                lines.add(chat.content().getString());
            } else if (packet instanceof ClientboundPlayerInfoUpdatePacket) {
                infoUpdates++;
            }
            super.send(packet, listener);
        }
    }

    /** The real player list; {@code getPlayers()} is an unmodifiable view on Forge. */
    private static List<ServerPlayer> writable(PlayerList players) {
        return ((PlayerListAccessor) players).bsp_teleport$players();
    }

    private record Run(List<String> lines, boolean joinSeen, boolean leaveSeen, boolean listedDuringLogin, int infoUpdates, int dropped) {
    }

    /** Logs a mock player in and out while another mock player watches, with the switch set as given. */
    private static Run loginAndLogout(GameTestHelper h, boolean suppress) {
        MinecraftServer server = h.getLevel().getServer();
        PlayerList players = server.getPlayerList();
        boolean before = TeleportConfig.SUPPRESS_JOIN_LEAVE.get();
        int droppedBefore = JoinLeaveMessages.dropped();

        ServerPlayer watcher = TestPlayers.newPlayer(h);
        Recording recording = new Recording(server, TestPlayers.connection(), watcher);
        watcher.connection = recording;
        writable(players).add(watcher);

        ServerPlayer joiner = TestPlayers.newPlayer(h);
        String name = joiner.getGameProfile().getName();
        boolean listed = false;
        try {
            TeleportConfig.SUPPRESS_JOIN_LEAVE.set(suppress);
            players.placeNewPlayer(TestPlayers.connection(), joiner);
            listed = players.getPlayerByName(name) == joiner;
            joiner.connection.onDisconnect(Component.literal("test over"));
        } finally {
            TeleportConfig.SUPPRESS_JOIN_LEAVE.set(before);
            writable(players).remove(watcher);
            if (players.getPlayerByName(name) != null) {
                players.remove(joiner);
            }
        }
        String joined = Component.translatable("multiplayer.player.joined", name).getString();
        String left = Component.translatable("multiplayer.player.left", name).getString();
        List<String> lines = List.copyOf(recording.lines);
        return new Run(lines, lines.contains(joined), lines.contains(left), listed, recording.infoUpdates,
                JoinLeaveMessages.dropped() - droppedBefore);
    }

    /** Runs a check and turns any exception into a failure that names where it came from. */
    private static void guarded(GameTestHelper h, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            StringBuilder sb = new StringBuilder(e.toString());
            StackTraceElement[] trace = e.getStackTrace();
            for (int i = 0; i < Math.min(8, trace.length); i++) {
                sb.append(" | ").append(trace[i]);
            }
            h.fail(sb.toString());
        }
    }

    @GameTest(template = EMPTY)
    public void switch_off_keeps_vanilla_join_and_leave_lines(GameTestHelper h) {
        guarded(h, () -> switchOff(h));
    }

    private static void switchOff(GameTestHelper h) {
        Run run = loginAndLogout(h, false);
        if (!run.joinSeen()) {
            h.fail("with the switch off the join line should reach other players; got " + run.lines());
        }
        if (!run.leaveSeen()) {
            h.fail("with the switch off the leave line should reach other players; got " + run.lines());
        }
        if (run.dropped() != 0) {
            h.fail("nothing should be hidden with the switch off, hid " + run.dropped());
        }
        if (!run.listedDuringLogin()) {
            h.fail("the player should be in the player list after login");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void switch_on_hides_join_and_leave_lines_only(GameTestHelper h) {
        guarded(h, () -> switchOn(h));
    }

    private static void switchOn(GameTestHelper h) {
        Run run = loginAndLogout(h, true);
        if (run.joinSeen()) {
            h.fail("the join line went out although the switch is on: " + run.lines());
        }
        if (run.leaveSeen()) {
            h.fail("the leave line went out although the switch is on: " + run.lines());
        }
        if (run.dropped() != 2) {
            h.fail("expected exactly the join and the leave line hidden, hid " + run.dropped());
        }
        // The rest of the login is untouched: the player was listed and the watcher still got the tab-list update.
        if (!run.listedDuringLogin()) {
            h.fail("the player should still be in the player list after login");
        }
        if (run.infoUpdates() == 0) {
            h.fail("the watcher should still receive the player-info update on login");
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void switch_on_passes_other_lines_during_login(GameTestHelper h) {
        guarded(h, () -> otherLines(h));
    }

    private static void otherLines(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        boolean before = TeleportConfig.SUPPRESS_JOIN_LEAVE.get();
        ServerPlayer watcher = TestPlayers.newPlayer(h);
        Recording recording = new Recording(server, TestPlayers.connection(), watcher);
        watcher.connection = recording;
        writable(server.getPlayerList()).add(watcher);
        ServerPlayer joiner = TestPlayers.mockServerPlayer(h);
        try {
            TeleportConfig.SUPPRESS_JOIN_LEAVE.set(true);
            // Inside a login window, a line that is not the vanilla announcement must pass.
            JoinLeaveMessages.begin(joiner);
            server.getPlayerList().broadcastSystemMessage(Component.literal("Welcome, " + joiner.getGameProfile().getName() + "!"), false);
            server.getPlayerList().broadcastSystemMessage(Component.translatable("multiplayer.player.joined", joiner.getGameProfile().getName()), false);
            JoinLeaveMessages.end();
            // Outside any window, even the vanilla text passes (another mod announcing someone is not our business).
            server.getPlayerList().broadcastSystemMessage(Component.translatable("multiplayer.player.left", joiner.getGameProfile().getName()), false);
        } finally {
            TeleportConfig.SUPPRESS_JOIN_LEAVE.set(before);
            writable(server.getPlayerList()).remove(watcher);
        }
        if (recording.lines.size() != 2 || !recording.lines.get(0).startsWith("Welcome, ") || !recording.lines.get(1).endsWith("left the game")) {
            h.fail("expected the welcome line and the out-of-window line only, got " + recording.lines);
        }
        h.succeed();
    }
}
