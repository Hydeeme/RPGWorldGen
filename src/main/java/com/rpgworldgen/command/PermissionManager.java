package com.rpgworldgen.command;

import org.bukkit.command.CommandSender;

/**
 * Central permission checks for /rpgworld. Pure logic over Bukkit's
 * {@link CommandSender#hasPermission(String)} so it is unit-testable with stubs.
 */
public class PermissionManager {

    public static final String ADMIN = "rpgworld.admin";
    public static final String CREATE = "rpgworld.create";
    public static final String GENERATE = "rpgworld.generate";
    public static final String PREVIEW = "rpgworld.preview";
    public static final String REGENERATE = "rpgworld.regenerate";
    public static final String RELOAD = "rpgworld.reload";
    public static final String INFO = "rpgworld.info";

    /** Granular check: the specific node or the admin wildcard passes. */
    public boolean can(CommandSender sender, String node) {
        return sender.hasPermission(ADMIN) || sender.hasPermission(node);
    }
}
