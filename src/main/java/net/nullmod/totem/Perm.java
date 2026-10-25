package net.nullmod.totem;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

/** Single place for the admin check, so a vanilla permission-API change only needs one edit. */
public final class Perm {
    private Perm() {}

    public static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
