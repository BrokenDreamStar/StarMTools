package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class DelWarpCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public DelWarpCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, "error.only-player");
            return true;
        }
        if (!player.hasPermission("starmtool.delwarp")) {
            plugin.getMessageManager().send(player, "error.no-permission");
            return true;
        }
        if (args.length != 1) {
            plugin.getMessageManager().send(player, "warp.del-usage");
            return true;
        }
        teleportManager.delWarp(player, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) return List.of();
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String name : teleportManager.warpNames()) {
                if (name.toLowerCase().startsWith(args[0].toLowerCase())) result.add(name);
            }
            return result;
        }
        return List.of();
    }
}
