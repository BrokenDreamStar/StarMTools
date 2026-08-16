package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CommandHandler implements TabExecutor {

    private final StarMTools plugin;

    public CommandHandler(StarMTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.getMessageManager().reload();
            // 自动补全缺失的配置项并保存
            boolean completed = ConfigManager.completeMissing(plugin);
            RightClickListener rcl = plugin.getRightClickListener();
            if (rcl != null) rcl.resetCooldowns();
            plugin.getMessageManager().send(sender,
                    completed ? "starmtools.reloaded-completed" : "starmtools.reloaded");
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload");
        }
        return List.of();
    }
}
