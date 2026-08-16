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
            plugin.getMessageManager().send(sender, "error.only-player");
            return true;
        }
        if (!player.hasPermission("starmtool.warp")) {
            plugin.getMessageManager().send(player, "error.no-permission");
            return true;
        }
        MessageManager mm = plugin.getMessageManager();
        if (args.length == 0) {
            List<String> names = teleportManager.warpNames();
            if (names.isEmpty()) {
                mm.send(player, "warp.not-set-any");
            } else {
                mm.send(player, "warp.list", "names=" + String.join("&7, &e", names));
            }
            return true;
        }
        if (args.length == 1) {
            Warp warp = teleportManager.getWarp(args[0]);
            if (warp == null) {
                mm.send(player, "warp.not-exist", "name=" + args[0]);
                return true;
            }
            Location location = warp.toLocation();
            if (location == null) {
                mm.send(player, "warp.world-not-loaded");
                return true;
            }
            teleportManager.teleportNow(player, location);
            return true;
        }
        mm.send(player, "warp.usage");
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
