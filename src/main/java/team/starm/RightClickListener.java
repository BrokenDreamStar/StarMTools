package team.starm;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RightClickListener implements Listener {

    private final StarMTools plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public RightClickListener(StarMTools plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        Material material = item.getType();
        String path = "right-click-items." + material.name();
        if (!plugin.getConfig().contains(path, true)) {
            return;
        }
        String command = plugin.getConfig().getString(path);
        if (command == null || command.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        int cooldownMillis = plugin.getConfig().getInt("cooldown-millis", 0);
        if (cooldownMillis > 0) {
            long now = System.currentTimeMillis();
            long lastUsed = cooldowns.getOrDefault(uuid, 0L);
            if (now - lastUsed < cooldownMillis) {
                return;
            }
            cooldowns.put(uuid, now);
        }

        player.performCommand(command);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    public void resetCooldowns() {
        cooldowns.clear();
    }
}
