package team.starm;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置文件补全工具：只补全"结构性配置项"缺失时的默认值，并同时补上对应注释。
 * 结构性配置项（如 fly.*、teleport.*、cooldown-millis 等）有确定的默认值，
 * 玩家误删后会自动回填（含注释）；而用户自定义内容节（right-click-items.*、plugins-display.groups.*）
 * 默认示例仅作参考，绝不会被回填进玩家配置。
 */
public final class ConfigManager {

    /** 结构性配置项：路径 -> 默认值，及补全时附带的一行注释。 */
    private static final Map<String, Map.Entry<Object, String>> STRUCTURED_DEFAULTS = buildDefaults();

    private ConfigManager() {}

    /**
     * 补全 config 中缺失的结构性配置项（连同注释）并保存，返回是否发生了补全。
     */
    public static boolean completeMissing(StarMTools plugin) {
        YamlConfiguration config = (YamlConfiguration) plugin.getConfig();
        boolean changed = false;
        for (Map.Entry<String, Map.Entry<Object, String>> entry : STRUCTURED_DEFAULTS.entrySet()) {
            if (!config.contains(entry.getKey(), true)) {
                config.set(entry.getKey(), entry.getValue().getKey());
                config.setComments(entry.getKey(), List.of(entry.getValue().getValue()));
                changed = true;
            }
        }
        if (changed) {
            plugin.saveConfig();
        }
        return changed;
    }

    private static Map<String, Map.Entry<Object, String>> buildDefaults() {
        Map<String, Map.Entry<Object, String>> map = new LinkedHashMap<>();
        put(map, "cooldown-millis", 1000, "右键触发冷却时间（毫秒），每个玩家独立计算；设为 0 禁用");
        put(map, "first-join-command", "", "玩家首次进入服务器时执行的命令；设为空字符串禁用");
        put(map, "fly.restore-on-world-change", true, "切换世界时是否恢复飞行状态");
        put(map, "fly.restore-on-respawn", true, "玩家死亡重生后是否恢复飞行状态");
        put(map, "fly.restore-delay-ticks", 6, "切换世界/重生后延迟多久恢复飞行（tick，20 tick = 1 秒）");
        put(map, "teleport.request-timeout-seconds", 60, "tpa/tpahere 请求超时（秒）");
        put(map, "teleport.warmup-seconds", 3, "传送预热时间（秒），设为 0 立即传送；期间移动或受伤害会取消");
        put(map, "teleport.cancel-on-move", true, "预热期间是否因移动距离过大而取消传送");
        put(map, "teleport.cancel-move-distance", 0.5, "预热期间移动超过该距离（格，欧几里得距离）即取消传送");
        put(map, "homes.max-per-player", 5, "每个玩家最多可设置多少个家");
        put(map, "plugins-display.enabled", true, "是否启用自定义 /plugins 显示");
        put(map, "plugins-display.permission", "bukkit.command.plugins", "查看 /plugins 所需的权限，留空表示任何人");
        put(map, "plugins-display.color-by-status", true, "插件名按启用状态着色（启用=绿色，禁用=红色）");
        return map;
    }

    private static void put(Map<String, Map.Entry<Object, String>> map, String path, Object value, String comment) {
        map.put(path, Map.entry(value, comment));
    }
}
