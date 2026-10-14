package com.rpgworldgen.command;

import com.rpgworldgen.RPGWorldGeneratorPlugin;
import com.rpgworldgen.WorldGenerationManager;
import com.rpgworldgen.model.PlannedStructure;
import com.rpgworldgen.model.WorldPreset;
import com.rpgworldgen.plan.WorldPlanningEngine;
import com.rpgworldgen.sched.GenerationTaskScheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /rpgworld command tree with granular permission checks and tab completion.
 * Destructive operations (regenerate) require explicit typed confirmation.
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    private final RPGWorldGeneratorPlugin plugin;
    private final PermissionManager permissions = new PermissionManager();
    private final Map<String, Long> pendingConfirm = new HashMap<>();

    public CommandManager(RPGWorldGeneratorPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean need(CommandSender s, String perm) {
        if (permissions.can(s, perm)) return true;
        s.sendMessage(msg("no-permission", "&cYou do not have permission (needs " + perm + ")."));
        return false;
    }

    private void tell(CommandSender sender, String message) {
        sender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', message));
    }

    private String msg(String key, String def) {
        return plugin.getConfigManager().msg(key, def);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            return help(sender);
        }
        WorldGenerationManager worlds = plugin.getWorldManager();
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create": {
                if (!need(sender, "rpgworld.create")) return true;
                if (args.length < 3) {
                    tell(sender, msg("usage-create", "&cUsage: /rpgworld create <world> <5000|6000|8000|custom> [seed]"));
                    return true;
                }
                String name = args[1];
                int size;
                try {
                    size = Integer.parseInt(args[2]);
                    WorldPreset.validateSize(size);
                } catch (NumberFormatException e) {
                    tell(sender, "&cSize must be a number (5000, 6000, 8000, or custom 2000-12000).");
                    return true;
                } catch (IllegalArgumentException e) {
                    tell(sender, "&c" + e.getMessage());
                    return true;
                }
                long seed = 0;
                if (args.length >= 4) {
                    try { seed = Long.parseLong(args[3]); }
                    catch (NumberFormatException e) { tell(sender, "&cSeed must be a number."); return true; }
                }
                WorldGenerationManager.CreateResult r = worlds.createWorld(name, size, seed);
                tell(sender, (r.ok ? "&a" : "&c") + r.message);
                return true;
            }
            case "preview": {
                if (!need(sender, "rpgworld.preview")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld preview <world>"); return true; }
                String err = worlds.planWorld(args[1]);
                if (err != null) { tell(sender, "&c" + err); return true; }
                tell(sender, describePlan(args[1]));
                return true;
            }
            case "generate": {
                if (!need(sender, "rpgworld.generate")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld generate <world>"); return true; }
                String err = worlds.startGeneration(args[1]);
                tell(sender, err == null
                    ? msg("generate-started", "&aGeneration started. See /rpgworld status " + args[1])
                    : "&c" + err);
                return true;
            }
            case "pause": {
                if (!need(sender, "rpgworld.generate")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld pause <world>"); return true; }
                tell(sender, plugin.getScheduler().pause(args[1])
                    ? "&aPaused." : "&cNothing running for '" + args[1] + "'.");
                return true;
            }
            case "resume": {
                if (!need(sender, "rpgworld.generate")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld resume <world>"); return true; }
                tell(sender, plugin.getScheduler().resume(args[1])
                    ? "&aResumed." : "&cNothing paused for '" + args[1] + "'.");
                return true;
            }
            case "cancel": {
                if (!need(sender, "rpgworld.generate")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld cancel <world>"); return true; }
                tell(sender, plugin.getScheduler().cancel(args[1])
                    ? "&eCancelled." : "&cNo job for '" + args[1] + "'.");
                return true;
            }
            case "status":
            case "info": {
                if (!need(sender, "rpgworld.info")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld " + sub + " <world>"); return true; }
                tell(sender, worlds.statusLine(args[1]));
                return true;
            }
            case "regions": {
                if (!need(sender, "rpgworld.info")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld regions <world>"); return true; }
                WorldPlanningEngine.Plan plan = worlds.getPlan(args[1]);
                if (plan == null) { tell(sender, "&cNo plan for '" + args[1] + "'. Run preview first."); return true; }
                StringBuilder sb = new StringBuilder("&aRegions in " + args[1] + ":\n");
                for (PlannedStructure s : plan.structures) {
                    sb.append("&7- ").append(s.getKind()).append(" &f").append(s.getName())
                      .append(" &7(").append(s.getX()).append(',').append(s.getZ())
                      .append(") tier ").append(s.getTier()).append('\n');
                }
                tell(sender, sb.toString());
                return true;
            }
            case "locate": {
                if (!need(sender, "rpgworld.info")) return true;
                if (args.length < 3) { tell(sender, "&cUsage: /rpgworld locate <world> <village|city|kingdom|dungeon>"); return true; }
                WorldPlanningEngine.Plan plan = worlds.getPlan(args[1]);
                if (plan == null) { tell(sender, "&cNo plan for '" + args[1] + "'."); return true; }
                PlannedStructure.Kind kind;
                try {
                    String k = args[2].toUpperCase();
                    if (k.equals("KINGDOM")) k = "KINGDOM_CAPITAL";
                    kind = PlannedStructure.Kind.valueOf(k);
                } catch (IllegalArgumentException e) {
                    tell(sender, "&cUnknown type. Use village|city|kingdom|dungeon.");
                    return true;
                }
                StringBuilder sb = new StringBuilder();
                for (PlannedStructure s : plan.byKind(kind)) {
                    sb.append(s.getName()).append(" at ").append(s.getX()).append(',').append(s.getZ()).append('\n');
                }
                tell(sender, sb.length() == 0 ? "&cNone found." : sb.toString());
                return true;
            }
            case "regenerate": {
                if (!need(sender, "rpgworld.regenerate")) return true;
                if (args.length < 3) { tell(sender, "&cUsage: /rpgworld regenerate <world> <region-id> [--confirm]"); return true; }
                boolean confirm = args.length >= 4 && args[3].equalsIgnoreCase("--confirm");
                String key = sender.getName() + ":" + args[1] + ":" + args[2];
                if (!confirm) {
                    pendingConfirm.put(key, System.currentTimeMillis());
                    tell(sender, "&eWARNING: this will rebuild region '" + args[2] + "' in '" + args[1]
                        + "'. Back up your world first! Repeat with --confirm within 60s to proceed.");
                    return true;
                }
                Long t = pendingConfirm.get(key);
                if (t == null || System.currentTimeMillis() - t > 60000) {
                    tell(sender, "&cConfirmation expired. Run without --confirm first.");
                    return true;
                }
                pendingConfirm.remove(key);
                tell(sender, worlds.regenerateRegion(args[1], args[2]));
                return true;
            }
            case "reload": {
                if (!need(sender, "rpgworld.reload")) return true;
                plugin.getConfigManager().loadAll();
                tell(sender, "&aConfiguration reloaded.");
                return true;
            }
            case "link": {
                if (!need(sender, "rpgworld.generate")) return true;
                if (args.length < 2) { tell(sender, "&cUsage: /rpgworld link <world>"); return true; }
                String world = args[1];
                String mv = worlds.linkMultiverse(world);
                String wg = worlds.getIntegrations().hasWorldGuard()
                    ? worlds.mirrorWorldGuardZones(world)
                    : "WorldGuard not installed; built-in zones active.";
                tell(sender, "&aMultiverse: &f" + mv + "\n&a" + wg + "\n" + worlds.integrationLine(world));
                return true;
            }
            default:
                return help(sender);
        }
    }

    private boolean help(CommandSender sender) {
        tell(sender, "§6RPGWorldGenerator commands:\n"
            + "§e/rpgworld create <world> <5000|6000|8000> [seed]\n"
            + "§e/rpgworld preview <world> §7- plan without building\n"
            + "§e/rpgworld generate <world>\n"
            + "§e/rpgworld pause|resume|cancel <world>\n"
            + "§e/rpgworld status|info <world>\n"
            + "§e/rpgworld regions <world>\n"
            + "§e/rpgworld locate <world> <village|city|kingdom|dungeon>\n"
            + "§e/rpgworld regenerate <world> <region> [--confirm]\n"
            + "§e/rpgworld link <world> §7- verify/repair Multiverse + WorldGuard mirrors\n"
            + "§e/rpgworld reload");
        return true;
    }

    private String describePlan(String world) {
        WorldPlanningEngine.Plan plan = plugin.getWorldManager().getPlan(world);
        if (plan == null) return "&cNo plan available.";
        StringBuilder sb = new StringBuilder("&aPlan for " + world + " (seed " + plan.seed + "):\n");
        for (PlannedStructure.Kind k : PlannedStructure.Kind.values()) {
            sb.append("&7").append(k).append(": &f").append(plan.byKind(k).size()).append('\n');
        }
        sb.append("&7Roads: &f").append(plan.roads.size()).append('\n');
        if (!plan.validationErrors.isEmpty()) {
            sb.append("&cWarnings:\n");
            for (String e : plan.validationErrors) sb.append("&c- ").append(e).append('\n');
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return filter(args[0], Arrays.asList("help", "create", "preview", "generate", "pause",
                "resume", "cancel", "status", "regions", "locate", "regenerate", "reload", "info", "link"));
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            return new ArrayList<>(plugin.getWorldManager().getSizes().keySet());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return filter(args[1], Arrays.asList("5000", "6000", "8000"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("locate")) {
            return filter(args[2], Arrays.asList("village", "city", "kingdom", "dungeon"));
        }
        return Collections.emptyList();
    }

    private List<String> filter(String prefix, List<String> options) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase().startsWith(prefix.toLowerCase())) out.add(o);
        }
        return out;
    }
}
