package com.mrgregles.bsp_teleport.gametest;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.block.AccessShopBlock;
import com.mrgregles.bsp_teleport.block.AccessShopBlockEntity;
import com.mrgregles.bsp_teleport.registry.ModBlocks;
import com.mrgregles.bsp_teleport.shop.AccessShops;
import com.mrgregles.bsp_teleport.shop.ShopEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * D174 to D180: the Access Shop stands as four parts and goes as one, keeps its settings, and is listed for Copy from.
 * Opening, buying and the screens need a client and are the owner's in-game check.
 */
@GameTestHolder(BSPTeleport.MODID)
@PrefixGameTestTemplate(false)
public class AccessShopGameTests {
    private static BlockPos place(GameTestHelper h, BlockPos rel) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(rel);
        level.setBlock(c, ModBlocks.ACCESS_SHOP.get().defaultBlockState().setValue(AccessShopBlock.FACING, Direction.NORTH), 3);
        AccessShopBlock.placeParts(level, c, Direction.NORTH);
        return c;
    }

    @GameTest(template = "empty")
    public static void a_shop_stands_keeps_its_settings_and_goes_as_one(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = place(h, new BlockPos(1, 1, 1));
        BlockPos b = place(h, new BlockPos(4, 1, 4));
        try {
            for (BlockPos p : AccessShopBlock.parts(a, Direction.NORTH)) {
                BlockState s = level.getBlockState(p);
                h.assertTrue(s.getBlock() instanceof AccessShopBlock && AccessShopBlock.controller(s, p).equals(a), "every part points at the controller: " + p);
            }
            h.assertTrue(level.getBlockEntity(a) instanceof AccessShopBlockEntity, "the controller holds the settings");
            h.assertTrue(level.getBlockEntity(a.above()) == null, "the other parts hold none");

            AccessShopBlockEntity shop = (AccessShopBlockEntity) level.getBlockEntity(a);
            shop.configure("  Spawn shop  ", "Tokens here", List.of(new ShopEntry(2, true, 72, 25_000, true, true, 200_000, true),
                    new ShopEntry(99, true, -5, -1, false, false, 0, false)));
            h.assertTrue("Spawn shop".equals(shop.name()), "the name is trimmed: " + shop.name());
            ShopEntry bad = shop.entries().get(1);
            h.assertTrue(bad.token() == 20 && bad.rentHours() == 1 && bad.rentPrice() == 0, "values are kept in range: " + bad);

            CompoundTag tag = shop.saveWithoutMetadata();
            AccessShopBlockEntity copy = new AccessShopBlockEntity(a, level.getBlockState(a));
            copy.load(tag);
            h.assertTrue(copy.name().equals("Spawn shop") && copy.message().equals("Tokens here") && copy.entries().equals(shop.entries()),
                    "the settings survive a save and load");

            h.assertTrue(AccessShops.others(level, b).stream().anyMatch(k -> k.pos().equals(a) && k.name().equals("Spawn shop")),
                    "the other shop lists this one for Copy from");

            level.setBlock(a, Blocks.AIR.defaultBlockState(), 3);
            for (BlockPos p : AccessShopBlock.parts(a, Direction.NORTH)) {
                h.assertTrue(!(level.getBlockState(p).getBlock() instanceof AccessShopBlock), "removing the controller takes every part: " + p);
            }
            h.assertTrue(AccessShops.others(level, b).stream().noneMatch(k -> k.pos().equals(a)), "and takes it off the list");
            h.succeed();
        } finally {
            for (BlockPos c : List.of(a, b)) {
                for (BlockPos p : AccessShopBlock.parts(c, Direction.NORTH)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }
}
