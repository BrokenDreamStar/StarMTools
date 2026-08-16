package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SetHomeCommand implements TabExecutor {

    private static final String DEFAULT_HOME = "home";

    private final HomeManager homeManager;
    private final MessageManager messages;

    public SetHomeCommand(HomeManager homeManager, MessageManager messages) {
        this.homeManager = homeManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.only-player");
            return true;
        }
        if (!player.hasPermission("starmtool.sethome")) {
            messages.send(player, "error.no-permission");
            return true;
        }
        if (args.length > 1) {
            messages.send(player, "home.set-usage");
            return true;
        }
        String name = args.length == 1 ? args[0] : DEFAULT_HOME;
        homeManager.setHome(player, name);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }
}
