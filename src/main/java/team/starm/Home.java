package team.starm;

import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.UUID;

/** 玩家家的点位数据。 */
public record Home(UUID uuid, String name, String world, double x, double y, double z, float yaw, float pitch) {

    /** 转成 Bukkit 位置；家所在世界未加载时返回 null。 */
    public Location toLocation() {
        var w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, pitch);
    }
}
