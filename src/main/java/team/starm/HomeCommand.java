package team.starm;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class HomeCommand implements TabExecutor {

    private static final String DEFAULT_HOME = "home";

    private final HomeManager homeManager;
    private final TeleportManager teleportManager;
    private final MessageManager messages;

    public HomeCommand(HomeManager homeManager, TeleportManager teleportManager, MessageManager messages) {
        this.homeManager = homeManager;
        this.teleportManager = teleportManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.only-player");
            return true;
        }
        if (!player.hasPermission("starmtool.home")) {
            messages.send(player, "error.no-permission");
            return true;
        }
        if (args.length > 1) {
            messages.send(player, "home.usage");
            return true;
        }

        String name = args.length == 1 ? args[0] : DEFAULT_HOME;
        Home home = homeManager.getHome(player, name);
        if (home == null) {
            if (args.length == 0) {
                List<String> names = homeManager.homeNames(player);
                if (names.isEmpty()) {
                    messages.send(player, "home.none");
                } else {
                    messages.send(player, "home.list", "names=" + String.join("&7, &e", names));
                }
            } else {
                messages.send(player, "home.not-exist", "name=" + name);
            }
            return true;
        }
        Location location = home.toLocation();
        if (location == null) {
            messages.send(player, "home.world-not-loaded");
            return true;
        }
        teleportManager.teleportNow(player, location);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String name : homeManager.homeNames(player)) {
                if (name.toLowerCase().startsWith(args[0].toLowerCase())) result.add(name);
            }
            return result;
        }
        return List.of();
    }
}
