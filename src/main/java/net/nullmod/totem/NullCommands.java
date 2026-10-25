package net.nullmod.totem;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public final class NullCommands {
    private NullCommands() {}

    /** Brigadier is case-sensitive, so both /null and /Null are registered. */
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(build("null"));
        d.register(build("Null"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root) {
        LiteralArgumentBuilder<CommandSourceStack> ability = Commands.literal("ability");
        for (Ability a : Ability.values()) ability.then(Commands.literal(a.id).executes(c -> ability(c, a)));

        return Commands.literal(root)
                .then(ability)
                .then(Commands.literal("cooldown").requires(Perm::isAdmin)
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                                    if (!Perm.isAdmin(c.getSource())) return deny(c);
                                    ServerPlayer t = EntityArgument.getPlayer(c, "player");
                                    NullManager.resetCooldowns(t);
                                    c.getSource().sendSuccess(() -> Component.literal(NullManager.PFX + "Cooldowns reset for " + t.getGameProfile().name()), true);
                                    return 1;
                                }))))
                .then(Commands.literal("mode").requires(Perm::isAdmin)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("survival").executes(c -> mode(c, GameType.SURVIVAL)))
                                .then(Commands.literal("spectator").executes(c -> mode(c, GameType.SPECTATOR)))))
                .then(Commands.literal("effects")
                        .then(Commands.literal("on").executes(c -> effects(c, true)))
                        .then(Commands.literal("off").executes(c -> effects(c, false))))
                .then(Commands.literal("status").executes(NullCommands::status));
    }

    private static int deny(CommandContext<CommandSourceStack> c) {
        c.getSource().sendFailure(Component.literal("You do not have permission."));
        return 0;
    }

    private static int ability(CommandContext<CommandSourceStack> c, Ability a) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) { c.getSource().sendFailure(Component.literal("Only players can use abilities.")); return 0; }
        NullManager.activate(p, a);   // same checks as item use: totem, enabled, awakened, cooldown, spam lock
        return 1;
    }

    private static int mode(CommandContext<CommandSourceStack> c, GameType type) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!Perm.isAdmin(c.getSource())) return deny(c);
        ServerPlayer t = EntityArgument.getPlayer(c, "player");
        t.setGameMode(type);
        PlayerData d = NullData.get(t.getUUID());
        if (d.deathPending) { d.deathPending = false; NullData.markDirty(); }   // admin decision overrides a pending Death Protocol
        c.getSource().sendSuccess(() -> Component.literal(NullManager.PFX + t.getGameProfile().name() + " -> " + type.getName()), true);
        return 1;
    }

    private static int effects(CommandContext<CommandSourceStack> c, boolean on) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) { c.getSource().sendFailure(Component.literal("Only players.")); return 0; }
        NullData.get(p.getUUID()).effectsOn = on;
        NullData.markDirty();
        c.getSource().sendSuccess(() -> Component.literal(NullManager.PFX + "Personal NULL effects: " + (on ? "on" : "off")), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) { c.getSource().sendFailure(Component.literal("Only players.")); return 0; }
        PlayerData d = NullData.get(p.getUUID());
        NullConfig cfg = NullConfig.get();
        StringBuilder sb = new StringBuilder(NullManager.PFX + "Status\n");
        sb.append("\u00a77Awakened: \u00a7f").append(d.awakened ? "yes" : "no (use the totem once)").append('\n');
        sb.append("\u00a77Totem state: \u00a7f").append(NullManager.stateOf(p)).append(NullManager.carries(p) ? "" : " (no totem carried)").append('\n');
        sb.append("\u00a77Selected: \u00a7f").append(NullManager.selected(p).label).append('\n');
        for (Ability a : Ability.values()) {
            sb.append("\u00a77").append(a.label).append(": \u00a7f");
            if (!cfg.enabled(a)) sb.append("disabled");
            else {
                long cd = NullManager.cooldownLeft(p.getUUID(), a);
                sb.append(cd > 0 ? String.format(java.util.Locale.ROOT, "cooldown %.1fs", cd / 20.0) : "ready");
            }
            sb.append('\n');
        }
        sb.append("\u00a77Shield active: \u00a7f").append(NullManager.shieldActive(p.getUUID())).append('\n');
        sb.append("\u00a77Personal effects: \u00a7f").append(d.effectsOn ? "on" : "off");
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}
