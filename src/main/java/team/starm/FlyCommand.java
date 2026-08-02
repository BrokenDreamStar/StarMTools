package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class FlyCommand implements TabExecutor {

    private final StarMTools plugin;
    private final DatabaseManager databaseManager;

    public FlyCommand(StarMTools plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }

        if (!player.hasPermission("starmtool.fly")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }

        boolean flying = !player.getAllowFlight();
        player.setAllowFlight(flying);
        player.setFlying(flying);
        databaseManager.setFlyState(player.getUniqueId(), flying);
        player.sendMessage(flying ? "§a已启用飞行" : "§c已关闭飞行");

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }
}
