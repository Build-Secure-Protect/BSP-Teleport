package com.mrgregles.bsp_teleport.gametest;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.BspInventorySync;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GameTests for request T1 from BSP-InventorySync: the departure tells that mod to start the inventory save after
 * BSP-Core has taken the totems and before the proxy is asked, and goes exactly as before when the mod is absent.
 * The dev run has no BSP-InventorySync; the second test points the adapter's real lookup at {@link FakeInventorySyncApi}.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class InventorySyncGameTests {
    private static final String EMPTY = "empty";
    private static final String TARGET = "BSP-Target";

    /** A mock player whose connection writes "connect <server>" into the given list for each proxy Connect message. */
    private static ServerPlayer recordingPlayer(GameTestHelper h, List<String> steps) {
        ServerPlayer player = TestPlayers.newPlayer(h);
        player.connection = new ServerGamePacketListenerImpl(h.getLevel().getServer(), TestPlayers.connection(), player) {
            @Override
            public void send(Packet<?> packet) {
                if (packet instanceof ClientboundCustomPayloadPacket payload && payload.getIdentifier().toString().equals("bungeecord:main")) {
                    FriendlyByteBuf data = payload.getData();
                    byte[] bytes = new byte[data.readableBytes()];
                    data.getBytes(data.readerIndex(), bytes);
                    ByteArrayDataInput in = ByteStreams.newDataInput(bytes);
                    steps.add(in.readUTF().toLowerCase() + " " + in.readUTF());
                }
            }
        };
        return player;
    }

    @GameTest(template = EMPTY)
    public static void without_inventory_sync_the_adapter_is_absent_and_a_departure_still_goes(GameTestHelper h) {
        if (ModList.get().isLoaded(BspInventorySync.MODID)) {
            h.fail("this test expects a dev run without bsp_inventorysync");
            return;
        }
        if (BspInventorySync.isPresent()) {
            h.fail("the adapter should report itself absent");
            return;
        }
        List<String> steps = new java.util.ArrayList<>();
        ServerPlayer player = recordingPlayer(h, steps);
        try {
            if (BspInventorySync.beginTransfer(player, TARGET) || BspInventorySync.cancelTransfer(player)) {
                h.fail("an absent mod must answer false");
                return;
            }
            if (!TeleportService.INSTANCE.sendToServer(player, TARGET)) {
                h.fail("the departure was refused");
                return;
            }
            if (!BspCore.isTransferring(player.getUUID())) {
                h.fail("BSP-Core was not told");
                return;
            }
            if (!steps.equals(List.of("connect " + TARGET))) {
                h.fail("expected one Connect to " + TARGET + ", got " + steps);
                return;
            }
        } finally {
            BspCore.endTransfer(player);
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public static void inventory_sync_is_told_after_bsp_core_and_before_the_connect(GameTestHelper h) {
        String failure = BspInventorySync.resolve(FakeInventorySyncApi.class.getName());
        if (failure != null) {
            BspInventorySync.clear();
            h.fail("the lookup refused the stand-in: " + failure);
            return;
        }
        FakeInventorySyncApi.reset();
        ServerPlayer player = recordingPlayer(h, FakeInventorySyncApi.STEPS);
        try {
            TeleportService.INSTANCE.sendToServer(player, TARGET);
            List<String> expected = List.of("inventory " + TARGET + " core=true", "connect " + TARGET);
            if (!FakeInventorySyncApi.STEPS.equals(expected)) {
                h.fail("expected " + expected + ", got " + FakeInventorySyncApi.STEPS);
                return;
            }
            if (!BspInventorySync.cancelTransfer(player) || !FakeInventorySyncApi.STEPS.get(2).equals("cancel")) {
                h.fail("cancelTransfer did not reach the stand-in");
                return;
            }
            // A throw from the other mod is logged and the switch still goes.
            BspCore.endTransfer(player);
            FakeInventorySyncApi.reset();
            FakeInventorySyncApi.throwOnBegin = true;
            TeleportService.INSTANCE.sendToServer(player, TARGET);
            if (!FakeInventorySyncApi.STEPS.equals(List.of("connect " + TARGET))) {
                h.fail("a throwing beginTransfer must not stop the Connect, got " + FakeInventorySyncApi.STEPS);
                return;
            }
        } finally {
            BspInventorySync.clear();
            FakeInventorySyncApi.reset();
            BspCore.endTransfer(player);
        }
        h.succeed();
    }
}
