package team.starm;

import org.bukkit.Bukkit;
import org.bukkit.Location;

/** warp 点位数据。 */
public record Warp(String name, String world, double x, double y, double z, float yaw, float pitch) {

    /** 转成 Bukkit 位置；点位所在世界未加载时返回 null。 */
    public Location toLocation() {
        var w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, pitch);
    }
}
