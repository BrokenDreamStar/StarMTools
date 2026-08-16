package team.starm;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class TpaCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public TpaCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, "error.only-player");
            return true;
        }
        if (!player.hasPermission("starmtool.tpa")) {
            plugin.getMessageManager().send(player, "error.no-permission");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            teleportManager.accept(player, args[1], TeleportManager.RequestType.TPA);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deny")) {
            teleportManager.deny(player, args[1], TeleportManager.RequestType.TPA);
            return true;
        }
        if (args.length == 1) {
            teleportManager.sendRequest(player, args[0], TeleportManager.RequestType.TPA);
            return true;
        }
        plugin.getMessageManager().send(player, "teleport.tpa-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) options.add(online.getName());
            return filter(options, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            return filter(teleportManager.pendingSenderNames(player, TeleportManager.RequestType.TPA), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) result.add(option);
        }
        return result;
    }
}
