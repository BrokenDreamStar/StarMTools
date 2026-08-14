package team.starm;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class TpaHereCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public TpaHereCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.tpahere")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            teleportManager.accept(player, args[1], TeleportManager.RequestType.TPAHERE);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deny")) {
            teleportManager.deny(player, args[1], TeleportManager.RequestType.TPAHERE);
            return true;
        }
        if (args.length == 1) {
            teleportManager.sendRequest(player, args[0], TeleportManager.RequestType.TPAHERE);
            return true;
        }
        player.sendMessage("§c用法: /tpahere <玩家> 或 /tpahere accept|deny <玩家>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("accept", "deny"));
            for (Player online : Bukkit.getOnlinePlayers()) options.add(online.getName());
            return filter(options, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            return filter(teleportManager.pendingSenderNames(player, TeleportManager.RequestType.TPAHERE), args[1]);
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
