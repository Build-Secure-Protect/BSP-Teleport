package com.mrgregles.bsp_teleport.shop;

import com.mrgregles.bsp_teleport.access.AccessChecks;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessRules;
import com.mrgregles.bsp_teleport.access.AccessService;
import com.mrgregles.bsp_teleport.access.TokenAssignment;
import com.mrgregles.bsp_teleport.block.AccessShopBlockEntity;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.item.AccessTokenItem;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Access Shops on this server (D174 to D180): opening one, buying from it, an admin's settings and Copy from.
 *
 * <p>Each shop's settings live in its controller's block entity. This saved list keeps a copy of every shop's name and
 * settings by place, so Copy from works even when the other shop's chunk is not loaded.
 */
public final class AccessShops extends SavedData {
    private static final String ID = "bsp_teleport_shops";
    /** How near the controller a player must stand to buy or save, in blocks (the shop is two wide, plus reach). */
    private static final double REACH = 10;

    /** A shop as Copy from lists it. {@code key} is "dimension x y z". */
    public record Known(String key, String dimension, BlockPos pos, String name, CompoundTag settings) {
        public String label() {
            return name + " (" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")";
        }
    }

    private final Map<String, Known> shops = new LinkedHashMap<>();
    /** D176: players sent to the free main-server pick from a shop, so the shop opens once they choose. */
    private static final Map<UUID, GlobalPos> AFTER_CHOICE = new HashMap<>();

    private static AccessShops get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(AccessShops::load, AccessShops::new, ID);
    }

    private static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Placed or reconfigured: the list's copy is brought up to date. */
    public static void remember(ServerLevel level, BlockPos pos, AccessShopBlockEntity shop) {
        CompoundTag settings = new CompoundTag();
        shop.writeSettings(settings);
        AccessShops data = get(level.getServer());
        String k = key(level, pos);
        data.shops.put(k, new Known(k, level.dimension().location().toString(), pos.immutable(), shop.name(), settings));
        data.setDirty();
    }

    public static void forget(ServerLevel level, BlockPos pos) {
        AccessShops data = get(level.getServer());
        if (data.shops.remove(key(level, pos)) != null) {
            data.setDirty();
        }
    }

    /** Every other shop on this server, for Copy from. */
    public static List<Known> others(ServerLevel level, BlockPos pos) {
        String self = key(level, pos);
        List<Known> out = new ArrayList<>();
        get(level.getServer()).shops.values().forEach(k -> {
            if (!k.key().equals(self)) {
                out.add(k);
            }
        });
        return out;
    }

    // Opening

    /** A click on any part: the free pick for a player with no main server (D176), else the shop. */
    public static void use(ServerPlayer player, BlockPos controller) {
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(controller) instanceof AccessShopBlockEntity shop)) {
            return;
        }
        boolean admin = Permissions.isAdmin(player);
        if (!AccessService.enabled() && !admin) {
            tell(player, "access.disabled");
            return;
        }
        if (!admin && AccessService.mainServer(player) == null && !AccessService.restricted(player.server).isEmpty()) {
            AFTER_CHOICE.put(player.getUUID(), GlobalPos.of(level.dimension(), controller.immutable()));
            AccessService.showChoose(player);
            return;
        }
        open(player, level, controller, shop);
    }

    /** D176: the player chose their main server; a shop that sent them to the pick opens now, if they are still at it. */
    public static void afterChoice(ServerPlayer player) {
        GlobalPos at = AFTER_CHOICE.remove(player.getUUID());
        ServerLevel level = player.serverLevel();
        if (at != null && at.dimension().equals(level.dimension()) && near(player, at.pos())
                && level.getBlockEntity(at.pos()) instanceof AccessShopBlockEntity shop) {
            open(player, level, at.pos(), shop);
        }
    }

    private static void open(ServerPlayer player, ServerLevel level, BlockPos controller, AccessShopBlockEntity shop) {
        boolean admin = Permissions.isAdmin(player);
        List<ShopView.Source> sources = new ArrayList<>();
        if (admin) {
            others(level, controller).forEach(k -> sources.add(new ShopView.Source(k.key(), k.label())));
        }
        BandPackets.sendShop(player, new ShopView(controller, shop.name(), shop.message(), shop.entries(), admin, BspCore.coins(player), BspCore.teleportPieces(player), sources));
    }

    private static boolean near(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5) <= REACH * REACH;
    }

    @Nullable
    private static AccessShopBlockEntity shopAt(ServerPlayer player, BlockPos controller) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(controller) || !near(player, controller)) {
            return null;
        }
        return level.getBlockEntity(controller) instanceof AccessShopBlockEntity shop ? shop : null;
    }

    // Buying (D175, D177, D189)

    /**
     * The player confirmed a purchase of entry {@code index}: {@code take} puts the token item in their inventory, else the
     * access is granted at once. Nothing is taken when the access could not be granted. {@code token} and {@code price} are
     * what the player's screen showed: when an admin changed the shop or the token's price since, the purchase is refused
     * (audit). The price is the token's own (D189); the shop only says which tiers it sells.
     */
    public static void buy(ServerPlayer player, BlockPos controller, int index, int token0, int price0, boolean fullTier, boolean take) {
        AccessShopBlockEntity shop = shopAt(player, controller);
        if (shop == null) {
            return;
        }
        if (!AccessService.enabled()) {
            tell(player, "access.disabled");
            return;
        }
        List<ShopEntry> entries = shop.entries();
        TokenAssignment token = index >= 0 && index < entries.size() ? AccessService.tokens(player.server).get(entries.get(index).token()) : null;
        if (index < 0 || index >= entries.size() || !entries.get(index).sells(fullTier) || entries.get(index).token() != token0
                || (token != null && (!token.price().sells(fullTier) || token.price().price(fullTier) != price0))) {
            tell(player, "shop.changed");
            open(player, player.serverLevel(), controller, shop);
            return;
        }
        if (token == null) {
            tell(player, "access.token_unassigned", TokenAssignment.roman(token0));
            return;
        }
        sell(player, token, fullTier, take, () -> {
            AccessShopBlockEntity still = shopAt(player, controller);
            if (still != null) {
                open(player, player.serverLevel(), controller, still);
            }
        });
    }

    /**
     * D185: a purchase from the access pop-up, outside any shop. Only a token the player lacks the access of is sold this
     * way (the pop-up offers nothing else); the price must still be what the pop-up showed. USE NOW then carries on with
     * the trip that was refused, when there was one (D187).
     */
    public static void buyOffered(ServerPlayer player, int token0, int price0, boolean fullTier, boolean take) {
        if (!AccessService.enabled()) {
            tell(player, "access.disabled");
            return;
        }
        TokenAssignment token = AccessService.tokens(player.server).get(token0);
        if (token == null) {
            tell(player, "access.token_unassigned", TokenAssignment.roman(token0));
            return;
        }
        if (!token.price().sells(fullTier) || token.price().price(fullTier) != price0) {
            tell(player, "shop.price_changed");
            AccessService.reoffer(player, token.kind(), token.target());
            return;
        }
        // Audit: a server goes by mayEnter, so a main server, an open server or an admin is not sold what they have.
        boolean has = token.kind() == AccessGrant.Kind.SERVER ? AccessService.mayEnter(player, token.target())
                : AccessRules.has(AccessService.grantsOf(player.server, player.getUUID()), token.kind(), token.target(), System.currentTimeMillis());
        if (has) {
            tell(player, "shop.offer_held", token.target());
            return;
        }
        sell(player, token, fullTier, take, take ? null : () -> AccessService.resumeTrip(player, token.kind(), token.target()));
    }

    /**
     * Charges for one tier of {@code token} at its own price and grants it ({@code take} false) or hands over the token
     * item. {@code after} runs once it went through.
     */
    static void sell(ServerPlayer player, TokenAssignment token, boolean fullTier, boolean take, @Nullable Runnable after) {
        MinecraftServer server = player.server;
        AccessGrant.Tier tier = fullTier ? AccessGrant.Tier.FULL : AccessGrant.Tier.RENT;
        int hours = token.rentHours();
        if (!take) {
            String refusal = AccessRules.refuse(AccessService.grantsOf(server, player.getUUID()), token.kind(), token.target(), tier, System.currentTimeMillis());
            if (refusal != null) {
                tell(player, refusal, token.target());
                return;
            }
        }
        int price = token.price().price(fullTier);
        boolean pieces = token.price().pieces(fullTier);
        String name = "Access Token " + TokenAssignment.roman(token.token());
        String reason = name + (fullTier ? " (full)" : " (rent)");
        AccessChecks.charge(player, new AccessChecks.Result(null, 0, 0, List.of()), pieces ? 0 : price, pieces ? price : 0, reason, paid -> {
            if (paid == null) {
                return;
            }
            if (take) {
                if (player.hasDisconnected() || player.isRemoved()) {
                    // left while the bank answered: the token would be lost, so the payment goes back instead
                    AccessChecks.refund(server, paid, null);
                    return;
                }
                ItemStack stack = ((AccessTokenItem) ModItems.ACCESS_TOKENS[token.token() - 1].get()).stack(tier, fullTier ? 0 : hours);
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
                tell(player, "shop.took", name, token.target());
            } else {
                String refused = AccessService.grant(server, player.getUUID(), token.kind(), token.target(), tier, hours);
                if (refused != null) {
                    AccessChecks.refund(server, paid, player);
                    tell(player, refused, token.target());
                    return;
                }
                if (fullTier) {
                    tell(player, "access.granted", token.target(), Component.translatable("message.bsp_teleport.access.tier_full"));
                } else {
                    tell(player, "access.granted_rent", token.target(), AccessGrant.describeLeft(AccessRules.rentMs(hours)));
                }
            }
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.4F);
            if (after != null) {
                after.run();
            }
        });
    }

    // Settings (D179)

    public static void save(ServerPlayer player, BlockPos controller, String name, String message, List<ShopEntry> entries) {
        AccessShopBlockEntity shop = shopAt(player, controller);
        if (shop == null || !Permissions.isAdmin(player)) {
            return;
        }
        shop.configure(name, message, entries);
        tell(player, "shop.saved", shop.name());
        open(player, player.serverLevel(), controller, shop);
    }

    /** Copies another shop's list and message here; this shop keeps its own name. */
    public static void copy(ServerPlayer player, BlockPos controller, String fromKey) {
        AccessShopBlockEntity shop = shopAt(player, controller);
        if (shop == null || !Permissions.isAdmin(player)) {
            return;
        }
        Known from = get(player.server).shops.get(fromKey);
        if (from == null) {
            tell(player, "shop.copy_gone");
            return;
        }
        shop.configure(shop.name(), from.settings().getString("Message"), AccessShopBlockEntity.readEntries(from.settings()));
        tell(player, "shop.copied", from.name());
        open(player, player.serverLevel(), controller, shop);
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        AFTER_CHOICE.remove(event.getEntity().getUUID());
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }

    // Saving

    private static AccessShops load(CompoundTag tag) {
        AccessShops data = new AccessShops();
        for (Tag t : tag.getList("Shops", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            BlockPos pos = NbtUtils.readBlockPos(s.getCompound("Pos"));
            String dim = s.getString("Dimension");
            String k = dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
            data.shops.put(k, new Known(k, dim, pos, s.getString("Name"), s.getCompound("Settings")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Known k : shops.values()) {
            CompoundTag s = new CompoundTag();
            s.put("Pos", NbtUtils.writeBlockPos(k.pos()));
            s.putString("Dimension", k.dimension());
            s.putString("Name", k.name());
            s.put("Settings", k.settings());
            list.add(s);
        }
        tag.put("Shops", list);
        return tag;
    }
}
