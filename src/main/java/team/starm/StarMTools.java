package team.starm;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class StarMTools extends JavaPlugin {

    private DatabaseManager databaseManager;
    private RightClickListener rightClickListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        databaseManager = new DatabaseManager(this);
        databaseManager.init();

        TeleportManager teleportManager = new TeleportManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(teleportManager, this);

        getCommand("starmtools").setExecutor(new CommandHandler(this));
        getCommand("fly").setExecutor(new FlyCommand(this, databaseManager));
        getCommand("serverinfo").setExecutor(new ServerInfoCommand(this));
        getCommand("tpa").setExecutor(new TpaCommand(this, teleportManager));
        getCommand("tpahere").setExecutor(new TpaHereCommand(this, teleportManager));
        getCommand("back").setExecutor(new BackCommand(this, teleportManager));
        getCommand("warp").setExecutor(new WarpCommand(this, teleportManager));
        getCommand("setwarp").setExecutor(new SetWarpCommand(this, teleportManager));
        getCommand("delwarp").setExecutor(new DelWarpCommand(this, teleportManager));

        rightClickListener = new RightClickListener(this);
        getServer().getPluginManager().registerEvents(rightClickListener, this);

        // 替换原版 /plugins（/pl）的输出，由配置开关在运行时控制
        getServer().getPluginManager().registerEvents(new PluginsDisplayListener(this), this);

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent event) {
                databaseManager.removeFromCache(event.getPlayer().getUniqueId());
            }
        }, this);

        getLogger().info("配置加载成功");

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();
                restoreFly(player);

                String raw = getConfig().getString("first-join-command", "");
                if (!raw.isEmpty() && databaseManager.isFirstJoin(player.getUniqueId())) {
                    databaseManager.markJoined(player.getUniqueId());
                    String cmd = raw;
                    if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                        cmd = PlaceholderAPI.setPlaceholders(player, raw);
                    }
                    if (cmd.toLowerCase().startsWith("server ")) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.substring(7));
                    } else {
                        String command = cmd.toLowerCase().startsWith("player ") ? cmd.substring(7) : cmd;
                        player.performCommand(command);
                    }
                }
            }

            @EventHandler
            public void onWorldChange(PlayerChangedWorldEvent event) {
                if (!getConfig().getBoolean("fly.restore-on-world-change", true)) return;
                long delay = getConfig().getLong("fly.restore-delay-ticks", 6L);
                Bukkit.getScheduler().runTaskLater(StarMTools.this,
                    () -> restoreFly(event.getPlayer()), delay);
            }

            @EventHandler
            public void onRespawn(PlayerRespawnEvent event) {
                if (!getConfig().getBoolean("fly.restore-on-respawn", true)) return;
                long delay = getConfig().getLong("fly.restore-delay-ticks", 6L);
                Bukkit.getScheduler().runTaskLater(StarMTools.this,
                    () -> restoreFly(event.getPlayer()), delay);
            }

            private void restoreFly(Player player) {
                if (!player.isOnline()) return;
                Boolean flying = databaseManager.getFlyState(player.getUniqueId());
                if (flying != null && flying && !player.getAllowFlight()) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            }
        }, this);
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    public RightClickListener getRightClickListener() {
        return rightClickListener;
    }
}
