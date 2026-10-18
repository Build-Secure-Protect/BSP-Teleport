package com.mrgregles.bsp_teleport.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.access.AccessService;
import com.mrgregles.bsp_teleport.access.TokenAssignment;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/** {@code /bsptp access ...} for admins (D70, D74, D87) and {@code /mainserver <server>} for players (D71). */
public final class AccessCommands {
    private static final SuggestionProvider<CommandSourceStack> SERVERS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Stream.concat(Stream.of(TeleportConfig.serverName()), PlayerDirectory.servers().stream()).distinct(), builder);
    private static final SuggestionProvider<CommandSourceStack> RESTRICTED = (ctx, builder) ->
            SharedSuggestionProvider.suggest(AccessService.restricted(ctx.getSource().getServer()), builder);

    private AccessCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> mainServer() {
        return Commands.literal("mainserver")
                .requires(src -> src.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("server", StringArgumentType.word()).suggests(RESTRICTED)
                        .executes(ctx -> AccessService.chooseMain(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "server")) ? 1 : 0));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> access() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("access")
                .then(Commands.literal("servers").executes(AccessCommands::servers))
                .then(Commands.literal("server").then(Commands.argument("server", StringArgumentType.word()).suggests(SERVERS)
                        .then(Commands.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "server");
                            boolean on = BoolArgumentType.getBool(ctx, "on");
                            AccessService.setRestricted(ctx.getSource().getServer(), name, on);
                            tell(ctx, on ? "access.server_on" : "access.server_off", name);
                            return 1;
                        }))))
                .then(Commands.literal("list").then(Commands.argument("player", EntityArgument.player()).executes(AccessCommands::list)))
                .then(Commands.literal("main").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("server", StringArgumentType.word()).suggests(RESTRICTED)
                        .executes(ctx -> {
                            ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                            String server = AccessService.properName(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "server"));
                            AccessService.revoke(ctx.getSource().getServer(), target.getUUID(), AccessGrant.Kind.SERVER, AccessService.mainServer(target) == null ? "" : AccessService.mainServer(target));
                            String refusal = AccessService.grant(ctx.getSource().getServer(), target.getUUID(), AccessGrant.Kind.SERVER, server, AccessGrant.Tier.MAIN, 0);
                            tell(ctx, refusal == null ? "access.granted" : refusal, server, Component.translatable("message.bsp_teleport.access.tier_main"));
                            return refusal == null ? 1 : 0;
                        }))))
                .then(Commands.literal("tokens").executes(AccessCommands::tokens))
                .then(Commands.literal("stack").then(Commands.argument("n", IntegerArgumentType.integer(1, TokenAssignment.COUNT))
                        .then(Commands.literal("full").executes(ctx -> stack(ctx, AccessGrant.Tier.FULL, 0)))
                        .then(Commands.literal("rent").executes(ctx -> stack(ctx, AccessGrant.Tier.RENT, 0))
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1, 8760)).executes(ctx -> stack(ctx, AccessGrant.Tier.RENT, IntegerArgumentType.getInteger(ctx, "hours")))))));
        LiteralArgumentBuilder<CommandSourceStack> grant = Commands.literal("grant").then(Commands.argument("player", EntityArgument.player()));
        LiteralArgumentBuilder<CommandSourceStack> revoke = Commands.literal("revoke");
        LiteralArgumentBuilder<CommandSourceStack> token = Commands.literal("token").then(Commands.argument("n", IntegerArgumentType.integer(1, TokenAssignment.COUNT))
                .then(Commands.literal("clear").executes(ctx -> {
                    int n = IntegerArgumentType.getInteger(ctx, "n");
                    AccessService.clearToken(ctx.getSource().getServer(), n);
                    tell(ctx, "access.token_cleared", TokenAssignment.roman(n));
                    return 1;
                }))
                .then(Commands.literal("hours").then(Commands.argument("hours", IntegerArgumentType.integer(1, 8760)).executes(ctx -> editToken(ctx, t -> t.withHours(IntegerArgumentType.getInteger(ctx, "hours")), "access.token_hours"))))
                .then(Commands.literal("hint").executes(ctx -> editToken(ctx, t -> t.withHint(""), "access.token_hint"))
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> editToken(ctx, t -> t.withHint(StringArgumentType.getString(ctx, "text")), "access.token_hint")))));
        for (AccessGrant.Kind kind : AccessGrant.Kind.values()) {
            String word = kind.name().toLowerCase(java.util.Locale.ROOT);
            var targetArg = Commands.argument("target", StringArgumentType.string());
            if (kind == AccessGrant.Kind.SERVER) {
                targetArg = targetArg.suggests(SERVERS);
            }
            grant.then(Commands.argument("player", EntityArgument.player()).then(Commands.literal(word).then(targetArg
                    .then(Commands.literal("main").executes(ctx -> grant(ctx, kind, AccessGrant.Tier.MAIN, 0)))
                    .then(Commands.literal("full").executes(ctx -> grant(ctx, kind, AccessGrant.Tier.FULL, 0)))
                    .then(Commands.literal("rent").executes(ctx -> grant(ctx, kind, AccessGrant.Tier.RENT, TeleportConfig.getOr(TeleportConfig.ACCESS_RENT_HOURS, 168)))
                            .then(Commands.argument("hours", IntegerArgumentType.integer(1, 8760)).executes(ctx -> grant(ctx, kind, AccessGrant.Tier.RENT, IntegerArgumentType.getInteger(ctx, "hours"))))))));
            revoke.then(Commands.argument("player", EntityArgument.player()).then(Commands.literal(word).then(Commands.argument("target", StringArgumentType.string()).executes(ctx -> {
                ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
                String what = StringArgumentType.getString(ctx, "target");
                boolean ok = AccessService.revoke(ctx.getSource().getServer(), target.getUUID(), kind, kind == AccessGrant.Kind.SERVER ? AccessService.properName(ctx.getSource().getServer(), what) : what);
                tell(ctx, ok ? "access.revoked" : "access.none", what);
                return ok ? 1 : 0;
            }))));
            var tokenTarget = Commands.argument("target", StringArgumentType.string());
            if (kind == AccessGrant.Kind.SERVER) {
                tokenTarget = tokenTarget.suggests(SERVERS);
            }
            token.then(Commands.literal(word).then(tokenTarget.executes(ctx -> assign(ctx, kind, TeleportConfig.getOr(TeleportConfig.ACCESS_RENT_HOURS, 168)))
                    .then(Commands.argument("hours", IntegerArgumentType.integer(1, 8760)).executes(ctx -> assign(ctx, kind, IntegerArgumentType.getInteger(ctx, "hours"))))));
        }
        return root.then(grant).then(revoke).then(token);
    }

    /** Gives the admin a stack to put in a shop (D74). */
    private static int stack(CommandContext<CommandSourceStack> ctx, AccessGrant.Tier tier, int hours) throws CommandSyntaxException {
        ServerPlayer admin = ctx.getSource().getPlayerOrException();
        int n = IntegerArgumentType.getInteger(ctx, "n");
        var item = (com.mrgregles.bsp_teleport.item.AccessTokenItem) com.mrgregles.bsp_teleport.registry.ModItems.ACCESS_TOKENS[n - 1].get();
        var stack = item.stack(tier, hours);
        if (!admin.getInventory().add(stack)) {
            admin.drop(stack, false);
        }
        tell(ctx, "access.stack_given", TokenAssignment.roman(n), tier == AccessGrant.Tier.FULL ? "full" : "rent " + (hours > 0 ? hours + " h" : "default"));
        return 1;
    }

    private interface TokenEdit {
        TokenAssignment apply(TokenAssignment token);
    }

    private static int editToken(CommandContext<CommandSourceStack> ctx, TokenEdit edit, String key) {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        TokenAssignment existing = AccessService.tokens(ctx.getSource().getServer()).get(n);
        if (existing == null) {
            tell(ctx, "access.token_unknown", TokenAssignment.roman(n));
            return 0;
        }
        TokenAssignment edited = edit.apply(existing);
        AccessService.setToken(ctx.getSource().getServer(), edited);
        tell(ctx, key, TokenAssignment.roman(n), edited.rentHours());
        return 1;
    }

    private static int assign(CommandContext<CommandSourceStack> ctx, AccessGrant.Kind kind, int hours) {
        int n = IntegerArgumentType.getInteger(ctx, "n");
        String target = StringArgumentType.getString(ctx, "target");
        if (kind == AccessGrant.Kind.SERVER) {
            target = AccessService.properName(ctx.getSource().getServer(), target);
        } else if (kind == AccessGrant.Kind.REGION && !target.contains(":")) {
            target = TeleportConfig.serverName() + ":" + target;
        }
        TokenAssignment existing = AccessService.tokens(ctx.getSource().getServer()).get(n);
        TokenAssignment token = new TokenAssignment(n, kind, target, hours, existing == null ? "" : existing.hint());
        AccessService.setToken(ctx.getSource().getServer(), token);
        tell(ctx, "access.token_set", TokenAssignment.roman(n), token.describe(), hours);
        return 1;
    }

    private static int grant(CommandContext<CommandSourceStack> ctx, AccessGrant.Kind kind, AccessGrant.Tier tier, int hours) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        String what = StringArgumentType.getString(ctx, "target");
        if (kind == AccessGrant.Kind.SERVER) {
            what = AccessService.properName(ctx.getSource().getServer(), what);
        } else if (kind == AccessGrant.Kind.REGION && !what.contains(":")) {
            what = TeleportConfig.serverName() + ":" + what;
        }
        String refusal = AccessService.grant(ctx.getSource().getServer(), target.getUUID(), kind, what, tier, hours);
        if (refusal != null) {
            tell(ctx, refusal, AccessService.mainServer(target) == null ? what : AccessService.mainServer(target));
            return 0;
        }
        if (tier == AccessGrant.Tier.RENT) {
            tell(ctx, "access.granted_rent", what, AccessGrant.describeLeft(hours * 3_600_000L));
        } else {
            tell(ctx, "access.granted", what, Component.translatable("message.bsp_teleport.access.tier_" + tier.name().toLowerCase(java.util.Locale.ROOT)));
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        long now = System.currentTimeMillis();
        var grants = AccessService.all(ctx.getSource().getServer(), target.getUUID());
        if (grants.isEmpty()) {
            tell(ctx, "access.none");
            return 1;
        }
        String text = grants.stream().map(g -> g.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + g.target() + " (" + AccessService.describeTier(g, now).getString() + ")").collect(Collectors.joining(", "));
        tell(ctx, "access.list", target.getGameProfile().getName(), text);
        return 1;
    }

    private static int servers(CommandContext<CommandSourceStack> ctx) {
        var servers = AccessService.restricted(ctx.getSource().getServer());
        if (servers.isEmpty()) {
            tell(ctx, "access.servers_none");
        } else {
            tell(ctx, "access.servers", String.join(", ", servers));
        }
        return 1;
    }

    private static int tokens(CommandContext<CommandSourceStack> ctx) {
        var tokens = AccessService.tokens(ctx.getSource().getServer()).values();
        if (tokens.isEmpty()) {
            tell(ctx, "access.tokens_none");
        } else {
            tell(ctx, "access.tokens", tokens.stream().map(t -> TokenAssignment.roman(t.token()) + " = " + t.describe() + " (rent " + t.rentHours() + " h)").collect(Collectors.joining(", ")));
        }
        return 1;
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendSuccess(() -> Component.translatable("message.bsp_teleport." + key, args), false);
    }
}
