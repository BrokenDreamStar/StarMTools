package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class BackCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public BackCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, "error.only-player");
            return true;
        }
        if (!player.hasPermission("starmtool.back")) {
            plugin.getMessageManager().send(player, "error.no-permission");
            return true;
        }
        if (args.length == 0) {
            teleportManager.goBack(player, false);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) {
            teleportManager.goBack(player, true);
            return true;
        }
        plugin.getMessageManager().send(player, "teleport.back-usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && "confirm".startsWith(args[0].toLowerCase())) {
            return List.of("confirm");
        }
        return List.of();
    }
}
