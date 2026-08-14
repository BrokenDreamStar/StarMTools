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
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.back")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        teleportManager.goBack(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }
}
