package team.starm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 消息管理器：从 plugins/StarMTools/message.yml 读取玩家可配置的文本，
 * 支持 &amp; 颜色代码与 {占位符}。首次启动若无该文件则从 jar 内拷贝默认版。
 */
public class MessageManager {

    private final StarMTools plugin;
    private FileConfiguration messages;

    public MessageManager(StarMTools plugin) {
        this.plugin = plugin;
        reload();
    }

    /** （重新）加载 message.yml；不存在则先从 jar 内生成默认文件。 */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "message.yml");
        if (!file.exists()) {
            plugin.saveResource("message.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);

        // 回填缺失的消息键，保证新增消息在旧文件升级时可用
        YamlConfiguration defaults = loadResourceDefaults();
        if (defaults != null) {
            boolean changed = fillMissing("", messages, defaults);
            if (changed) {
                try {
                    messages.save(file);
                } catch (Exception e) {
                    plugin.getLogger().warning("无法保存 message.yml: " + e.getMessage());
                }
            }
        }
    }

    /** 读取 jar 内默认 message.yml；无法读取返回 null。 */
    private YamlConfiguration loadResourceDefaults() {
        InputStream in = plugin.getResource("message.yml");
        if (in == null) return null;
        try (in) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("无法读取内置默认消息: " + e.getMessage());
            return null;
        }
    }

    /** 把 defaults 中 target 缺失的键补进 target。二级嵌套即可覆盖本项目所有消息结构。 */
    private static boolean fillMissing(String prefix, org.bukkit.configuration.ConfigurationSection target,
                                       org.bukkit.configuration.ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (defaults.isConfigurationSection(key)) {
                org.bukkit.configuration.ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection == null) {
                    targetSection = target.createSection(key);
                }
                changed |= fillMissing(path, targetSection, defaults.getConfigurationSection(key));
            } else if (!target.contains(key, true)) {
                target.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 按点分路径读取一条消息（支持 &amp; 颜色与 {占位符}）。
     * {@code replacements} 为若干 "name=value" 字符串，会对 {name} 做替换。
     */
    public String get(String key, String... replacements) {
        String message = messages.getString(key, "");
        if (message == null) {
            return "";
        }
        for (String pair : replacements) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            message = message.replace("{" + name + "}", value);
        }
        return message;
    }

    /** 将 legacy（&amp; 颜色码）文本转成 Adventure Component。 */
    public static Component component(String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
    }

    /** 取一条消息并转成 Component（配合 get 的占位符替换）。 */
    public Component component(String key, String... replacements) {
        return component(get(key, replacements));
    }

    /** 给发送者发送一条配置化消息（含占位符替换）。 */
    public void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(component(key, replacements));
    }
}
