package team.starm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 拦截原版 /plugins（及别名 /pl）命令，按自定义分组显示已加载的插件。
 * 保留 Paper 原版交互特性：鼠标悬停查看插件详情，点击运行 /version 命令。
 */
public class PluginsDisplayListener implements Listener {

    private final StarMTools plugin;

    public PluginsDisplayListener(StarMTools plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!isEnabled() || !isPluginsCommand(firstLabel(event.getMessage()))) {
            return;
        }
        event.setCancelled(true);
        sendDisplay(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!isEnabled() || !isPluginsCommand(firstLabel(event.getCommand()))) {
            return;
        }
        event.setCancelled(true);
        sendDisplay(event.getSender());
    }

    private void sendDisplay(CommandSender sender) {
        String permission = plugin.getConfig().getString(Constants.PLUGINS_DISPLAY_PERMISSION, "bukkit.command.plugins");
        if (!permission.isEmpty() && !sender.hasPermission(permission)) {
            sender.sendMessage(legacyToComponent(message("no-permission")));
            return;
        }
        for (Component line : buildMessage()) {
            sender.sendMessage(line);
        }
    }

    private List<Component> buildMessage() {
        boolean colorByStatus = plugin.getConfig().getBoolean(Constants.PLUGINS_DISPLAY_COLOR_BY_STATUS, true);

        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        Map<String, Plugin> loaded = new LinkedHashMap<>();
        for (Plugin p : plugins) {
            loaded.put(p.getName().toLowerCase(Locale.ROOT), p);
        }

        List<Component> lines = new ArrayList<>();
        lines.add(legacyToComponent(message("header").replace("{count}", String.valueOf(plugins.length))));
        lines.add(legacyToComponent(message("among")));

        Set<String> assigned = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        ConfigurationSection groupsSection = plugin.getConfig().getConfigurationSection(Constants.PLUGINS_DISPLAY_GROUPS);
        if (groupsSection != null) {
            for (String groupLabel : groupsSection.getKeys(false)) {
                List<Component> shown = new ArrayList<>();
                for (String name : groupsSection.getStringList(groupLabel)) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    Plugin p = loaded.get(lower);
                    if (p == null || assigned.contains(lower)) {
                        continue;
                    }
                    assigned.add(lower);
                    shown.add(formatPlugin(p, colorByStatus));
                }
                if (shown.isEmpty()) {
                    continue;
                }
                lines.add(legacyToComponent(groupLabel));
                lines.add(joinWithSpace(shown));
            }
        }

        List<Component> others = new ArrayList<>();
        for (Plugin p : plugins) {
            if (!assigned.contains(p.getName().toLowerCase(Locale.ROOT))) {
                others.add(formatPlugin(p, colorByStatus));
            }
        }
        if (!others.isEmpty()) {
            lines.add(legacyToComponent(message("others-line")));
            lines.add(joinWithSpace(others));
        }

        return lines;
    }

    /** 格式化单个插件名，附带悬停详情和点击交互，还原 Paper 原版 /plugins 的交互体验。 */
    private Component formatPlugin(Plugin p, boolean colorByStatus) {
        TextColor color = colorByStatus
                ? (p.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)
                : null;

        Component hoverText = buildHoverText(p);

        Component name = Component.text(p.getName());
        if (color != null) {
            name = name.color(color);
        }

        return name
                .hoverEvent(HoverEvent.showText(hoverText))
                .clickEvent(ClickEvent.runCommand("/version " + p.getName()));
    }

    /** 构建悬停提示：插件名、版本、作者、网站、简介。 */
    private Component buildHoverText(Plugin p) {
        var desc = p.getDescription();
        MessageManager messages = plugin.getMessageManager();

        var hover = Component.text()
                .append(Component.text(messages.get("plugins-display.hover-name"), NamedTextColor.GRAY))
                .append(Component.text(p.getName(), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text(messages.get("plugins-display.hover-version"), NamedTextColor.GRAY))
                .append(Component.text(desc.getVersion(), NamedTextColor.WHITE));

        if (!desc.getAuthors().isEmpty()) {
            hover = hover
                    .append(Component.newline())
                    .append(Component.text(messages.get("plugins-display.hover-authors"), NamedTextColor.GRAY))
                    .append(Component.text(String.join(", ", desc.getAuthors()), NamedTextColor.WHITE));
        }

        String website = desc.getWebsite();
        if (website != null && !website.isEmpty()) {
            hover = hover
                    .append(Component.newline())
                    .append(Component.text(messages.get("plugins-display.hover-website"), NamedTextColor.GRAY))
                    .append(Component.text(website, NamedTextColor.AQUA));
        }

        String description = desc.getDescription();
        if (description != null && !description.isEmpty()) {
            hover = hover
                    .append(Component.newline())
                    .append(Component.newline())
                    .append(Component.text(description, NamedTextColor.GREEN));
        }

        return hover.build();
    }

    /** 将多个 Component 用空格拼接成一行。 */
    private static Component joinWithSpace(List<Component> parts) {
        var builder = Component.text();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder = builder.append(Component.space());
            }
            builder = builder.append(parts.get(i));
        }
        return builder.build();
    }

    /** 将配置中的 &amp; 颜色代码文本转换为 Adventure Component。 */
    private static Component legacyToComponent(String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacyText);
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean(Constants.PLUGINS_DISPLAY_ENABLED, true);
    }

    private String message(String key) {
        return plugin.getMessageManager().get("plugins-display.messages." + key);
    }

    private static String firstLabel(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        String label = space < 0 ? trimmed : trimmed.substring(0, space);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        return label.toLowerCase(Locale.ROOT);
    }

    private static boolean isPluginsCommand(String label) {
        return "plugins".equals(label) || "pl".equals(label);
    }
}
