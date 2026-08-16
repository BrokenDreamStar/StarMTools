package team.starm;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 家（home）功能核心：玩家家的缓存、数量限制与查询。
 * 所有方法均在主线程调用。
 */
public class HomeManager {

    private static final Pattern HOME_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private final StarMTools plugin;
    private final DatabaseManager databaseManager;
    /** 玩家 UUID -> (家名 -> 家)。按需从数据库加载。 */
    private final Map<UUID, Map<String, Home>> homesByPlayer = new ConcurrentHashMap<>();

    public HomeManager(StarMTools plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    /** 玩家最大可设置的家数（从配置读取）。 */
    public int maxHomes() {
        return plugin.getConfig().getInt(Constants.HOMES_MAX_PER_PLAYER, 5);
    }

    private Map<String, Home> homesOf(UUID uuid) {
        return homesByPlayer.computeIfAbsent(uuid, u -> {
            Map<String, Home> map = new ConcurrentHashMap<>();
            for (Home home : databaseManager.listHomes(u)) {
                map.put(home.name(), home);
            }
            return map;
        });
    }

    public void removeFromCache(UUID uuid) {
        homesByPlayer.remove(uuid);
    }

    public Home getHome(Player player, String name) {
        return homesOf(player.getUniqueId()).get(name);
    }

    public int countHomes(Player player) {
        return homesOf(player.getUniqueId()).size();
    }

    public List<String> homeNames(Player player) {
        return new ArrayList<>(homesOf(player.getUniqueId()).keySet());
    }

    public List<Home> listHomes(Player player) {
        return new ArrayList<>(homesOf(player.getUniqueId()).values());
    }

    /**
     * 设置一个家。返回是否成功；失败时会直接向玩家发送原因消息。
     */
    public boolean setHome(Player player, String name) {
        MessageManager mm = plugin.getMessageManager();
        if (!HOME_NAME_PATTERN.matcher(name).matches()) {
            mm.send(player, "home.name-invalid");
            return false;
        }
        Map<String, Home> homes = homesOf(player.getUniqueId());
        if (!homes.containsKey(name) && homes.size() >= maxHomes()) {
            mm.send(player, "home.max-reached", "max=" + maxHomes());
            return false;
        }
        Location loc = player.getLocation();
        Home home = new Home(player.getUniqueId(), name, loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        databaseManager.saveHome(home);
        homes.put(name, home);
        mm.send(player, "home.set", "name=" + name);
        return true;
    }

    /**
     * 删除一个家。返回是否成功；失败时会直接向玩家发送原因消息。
     */
    public boolean delHome(Player player, String name) {
        MessageManager mm = plugin.getMessageManager();
        if (homesOf(player.getUniqueId()).remove(name) == null) {
            mm.send(player, "home.delete-not-exist", "name=" + name);
            return false;
        }
        databaseManager.deleteHome(player.getUniqueId(), name);
        mm.send(player, "home.deleted", "name=" + name);
        return true;
    }
}
