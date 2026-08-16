package team.starm;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class WarpCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public WarpCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.warp")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        if (args.length == 0) {
            List<String> names = teleportManager.warpNames();
            if (names.isEmpty()) {
                player.sendMessage("§c还没有设置任何传送点。");
            } else {
                player.sendMessage("§a可用传送点: §e" + String.join("§7, §e", names));
            }
            return true;
        }
        if (args.length == 1) {
            Warp warp = teleportManager.getWarp(args[0]);
            if (warp == null) {
                player.sendMessage("§c传送点 §e" + args[0] + " §c不存在。");
                return true;
            }
            Location location = warp.toLocation();
            if (location == null) {
                player.sendMessage("§c传送点所在世界未加载。");
                return true;
            }
            teleportManager.teleportNow(player, location);
            return true;
        }
        player.sendMessage("§c用法: /warp <名字>");
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
