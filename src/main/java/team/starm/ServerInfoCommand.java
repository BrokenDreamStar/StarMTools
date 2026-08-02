package team.starm;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ServerInfoCommand implements TabExecutor {

    private final StarMTools plugin;

    public ServerInfoCommand(StarMTools plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !player.hasPermission("starmtool.serverinfo")) {
            player.sendMessage("§c你没有权限执行此命令。");
            return true;
        }

        // 采集系统信息放到异步线程，避免扫描世界文件夹拖慢主线程
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Info info = collectInfo();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player p && !p.isOnline()) return;
                sender.sendMessage(formatInfo(info));
            });
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }

    private record Info(String os, String cpuModel,
                        double systemCpuLoad, double processCpuLoad,
                        long serverUsedMemory, long systemUsedMemory, long systemTotalMemory,
                        long worldSize, long diskTotal, long diskFree) {}

    private Info collectInfo() {
        String os = System.getProperty("os.name", "未知")
                + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "未知") + ")";

        Runtime rt = Runtime.getRuntime();
        long diskTotal = 0, diskFree = 0;
        File cwd = new File(".");
        if (cwd.exists()) {
            diskTotal = cwd.getTotalSpace();
            diskFree = cwd.getFreeSpace();
        }

        return new Info(os, getCpuModel(),
                getCpuLoad(false), getCpuLoad(true),
                rt.totalMemory() - rt.freeMemory(), getSystemUsedMemory(), getSystemTotalMemory(),
                calcWorldSize(), diskTotal, diskFree);
    }

    private String formatInfo(Info info) {
        StringBuilder sb = new StringBuilder();
        sb.append("§e========= §b服务器信息 §e=========\n");
        sb.append("§a系统: §f").append(info.os()).append('\n');
        sb.append("§aCPU: §f").append(info.cpuModel())
                .append(" | 服务端 ").append(formatCpuPercent(info.processCpuLoad()))
                .append(" | 系统 ").append(formatCpuPercent(info.systemCpuLoad())).append('\n');

        if (info.systemTotalMemory() > 0) {
            sb.append("§a内存: §f服务端 ").append(formatBytes(info.serverUsedMemory()))
                    .append(" | 系统 ").append(formatBytes(info.systemUsedMemory()))
                    .append("(").append(percent(info.systemUsedMemory(), info.systemTotalMemory())).append("%)")
                    .append("/").append(formatBytes(info.systemTotalMemory())).append('\n');
        } else {
            sb.append("§a内存: §f服务端 ").append(formatBytes(info.serverUsedMemory()))
                    .append(" | 系统 未知/总内存 未知\n");
        }

        long diskUsed = info.diskTotal() - info.diskFree();
        if (info.diskTotal() > 0) {
            sb.append("§a磁盘: §f存档 ").append(formatBytes(info.worldSize()))
                    .append(" | 系统 ").append(formatBytes(diskUsed))
                    .append("/").append(formatBytes(info.diskTotal())).append('\n');
        } else {
            sb.append("§a磁盘: §f存档 ").append(formatBytes(info.worldSize()))
                    .append(" | 系统 未知/总计 未知\n");
        }

        sb.append("§a版本: §f").append(Bukkit.getName()).append(' ').append(Bukkit.getBukkitVersion()).append('\n');
        sb.append("§aJava: §f").append(System.getProperty("java.version", "未知")).append('\n');
        sb.append("§e===============================");
        return sb.toString();
    }

    /** 读取 CPU 型号：Linux 解析 /proc/cpuinfo，Windows 读环境变量，macOS 用 sysctl，均失败返回"未知"。 */
    private static String getCpuModel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String s = System.getenv("PROCESSOR_IDENTIFIER");
                if (s != null && !s.isBlank()) return s.trim();
            } else if (os.contains("mac")) {
                Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                        .redirectErrorStream(true).start();
                if (p.waitFor(3, TimeUnit.SECONDS)) {
                    String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) return s;
                }
                p.destroyForcibly();
            } else {
                Path cpuinfo = Path.of("/proc/cpuinfo");
                if (Files.isReadable(cpuinfo)) {
                    for (String line : Files.readAllLines(cpuinfo)) {
                        if (line.startsWith("model name")) {
                            int idx = line.indexOf(':');
                            if (idx >= 0) return line.substring(idx + 1).trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 采集失败时返回"未知"
        }
        return "未知";
    }

    /** 系统已用物理内存；非标准 JVM 返回 -1。 */
    private static long getSystemUsedMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sun) {
            try {
                return sun.getTotalMemorySize() - sun.getFreeMemorySize();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 系统物理总内存；非标准 JVM 返回 -1。 */
    private static long getSystemTotalMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sun) {
            try {
                return sun.getTotalMemorySize();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** CPU 占用率(0~1)，process=true 返回服务器进程占用率，否则返回系统整体占用率；获取不到返回 -1。 */
    private static double getCpuLoad(boolean process) {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sun) {
            try {
                return process ? sun.getProcessCpuLoad() : sun.getSystemCpuLoad();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 递归统计所有已加载世界的文件夹大小。 */
    private static long calcWorldSize() {
        long total = 0;
        for (World world : Bukkit.getWorlds()) {
            File folder = world.getWorldFolder();
            if (folder.exists()) total += folderSize(folder);
        }
        return total;
    }

    private static long folderSize(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File file : files) {
            if (file.isDirectory()) total += folderSize(file);
            else total += file.length();
        }
        return total;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String percent(long used, long total) {
        if (total <= 0) return "0";
        return String.format(Locale.ROOT, "%.1f", used * 100.0 / total);
    }

    private static String formatCpuPercent(double load) {
        if (load < 0) return "未知";
        return String.format(Locale.ROOT, "%.1f%%", load * 100.0);
    }
}
