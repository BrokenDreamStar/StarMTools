package team.starm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 传送功能核心：待处理请求、预热倒计时、back 位置槽、warp 缓存。
 * 所有方法均在主线程调用。
 */
public class TeleportManager implements Listener {

    public enum RequestType { TPA, TPAHERE }

    public record TeleportRequest(UUID sender, String senderName, UUID target, String targetName,
                                  RequestType type, long expireAtMillis) {}

    private static final double MOVE_CANCEL_DISTANCE = 0.5;
    private static final Pattern WARP_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private final StarMTools plugin;
    private final DatabaseManager databaseManager;
    /** 发送者 UUID -> 类型 -> 请求（每个发送者每种类型最多一条）。 */
    private final Map<UUID, Map<RequestType, TeleportRequest>> requests = new ConcurrentHashMap<>();
    /** 被传送方 UUID -> 预热任务。 */
    private final Map<UUID, WarmupTask> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, Location> deathLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<String, Warp> warps = new ConcurrentHashMap<>();

    public TeleportManager(StarMTools plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        for (Warp warp : databaseManager.listWarps()) {
            warps.put(warp.name(), warp);
        }
    }

    // ---------- tpa / tpahere ----------

    public boolean sendRequest(Player sender, String targetName, RequestType type) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage("§c玩家不存在或不在线。");
            return false;
        }
        if (target.equals(sender)) {
            sender.sendMessage("§c不能向自己发送传送请求。");
            return false;
        }

        Map<RequestType, TeleportRequest> byType =
                requests.computeIfAbsent(sender.getUniqueId(), k -> new ConcurrentHashMap<>());
        TeleportRequest old = byType.get(type);
        if (old != null && old.expireAtMillis() > System.currentTimeMillis()) {
            Player oldTarget = Bukkit.getPlayer(old.target());
            if (oldTarget != null && !oldTarget.equals(target)) {
                oldTarget.sendMessage("§e" + sender.getName() + " §c撤销了之前的传送请求。");
            }
        }

        int timeoutSeconds = plugin.getConfig().getInt(Constants.TELEPORT_REQUEST_TIMEOUT_SECONDS, 60);
        TeleportRequest request = new TeleportRequest(sender.getUniqueId(), sender.getName(),
                target.getUniqueId(), target.getName(), type,
                System.currentTimeMillis() + timeoutSeconds * 1000L);
        byType.put(type, request);

        String verb = type == RequestType.TPA ? "传送到你身边" : "把你传送过去";
        String cmd = type == RequestType.TPA ? "tpa" : "tpahere";
        Component accept = Component.text("§a§l[接受]")
                .clickEvent(ClickEvent.runCommand("/" + cmd + " accept " + sender.getName()));
        Component deny = Component.text("§c§l[拒绝]")
                .clickEvent(ClickEvent.runCommand("/" + cmd + " deny " + sender.getName()));
        target.sendMessage(Component.text("§e" + sender.getName() + " §a请求" + verb + "，点击响应：§r  ")
                .append(accept).append(Component.text("  ")).append(deny));
        sender.sendMessage("§a请求已发送给 §e" + target.getName()
                + "§a，等待对方响应（" + timeoutSeconds + " 秒内有效）。");

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> expireIfPending(sender.getUniqueId(), type, request), timeoutSeconds * 20L);
        return true;
    }

    public void accept(Player target, String senderName, RequestType type) {
        Player sender = Bukkit.getPlayerExact(senderName);
        if (sender == null) {
            target.sendMessage("§c该玩家不存在或不在线。");
            return;
        }
        Map<RequestType, TeleportRequest> byType = requests.get(sender.getUniqueId());
        TeleportRequest request = byType == null ? null : byType.get(type);
        if (request == null || request.expireAtMillis() <= System.currentTimeMillis()
                || !request.target().equals(target.getUniqueId())) {
            target.sendMessage("§c没有来自 §e" + senderName + " §c的待处理请求，或请求已过期。");
            return;
        }

        Player moving = type == RequestType.TPA ? sender : target;
        if (warmups.containsKey(moving.getUniqueId())) {
            target.sendMessage("§c该玩家已有进行中的传送，请稍后再试。");
            return;
        }

        byType.remove(type);
        if (byType.isEmpty()) requests.remove(sender.getUniqueId());

        Location destination = type == RequestType.TPA ? target.getLocation() : sender.getLocation();
        Player other = type == RequestType.TPA ? target : sender;
        WarmupTask warmup = new WarmupTask(moving, destination, other);
        warmups.put(moving.getUniqueId(), warmup);
        warmup.start();
    }

    public void deny(Player target, String senderName, RequestType type) {
        Player sender = Bukkit.getPlayerExact(senderName);
        Map<RequestType, TeleportRequest> byType = sender == null ? null : requests.get(sender.getUniqueId());
        TeleportRequest request = byType == null ? null : byType.get(type);
        if (request == null || request.expireAtMillis() <= System.currentTimeMillis()
                || !request.target().equals(target.getUniqueId())) {
            target.sendMessage("§c没有来自 §e" + senderName + " §c的待处理请求，或请求已过期。");
            return;
        }
        byType.remove(type);
        if (byType.isEmpty()) requests.remove(sender.getUniqueId());
        if (sender != null) sender.sendMessage("§e" + target.getName() + " §c拒绝了你的传送请求。");
        target.sendMessage("§a已拒绝请求。");
    }

    private void expireIfPending(UUID senderUuid, RequestType type, TeleportRequest expected) {
        Map<RequestType, TeleportRequest> byType = requests.get(senderUuid);
        if (byType == null) return;
        if (byType.get(type) != expected) return; // 请求已被覆盖或处理
        byType.remove(type);
        if (byType.isEmpty()) requests.remove(senderUuid);
        Player sender = Bukkit.getPlayer(expected.sender());
        Player target = Bukkit.getPlayer(expected.target());
        if (sender != null) {
            sender.sendMessage("§c你发送给 §e" + expected.targetName() + " §c的传送请求已过期。");
        }
        if (target != null) {
            target.sendMessage("§e" + expected.senderName() + " §c的传送请求已过期。");
        }
    }

    public List<String> pendingSenderNames(Player target, RequestType type) {
        List<String> names = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map<RequestType, TeleportRequest> byType : requests.values()) {
            TeleportRequest request = byType.get(type);
            if (request != null && request.target().equals(target.getUniqueId())
                    && request.expireAtMillis() > now) {
                Player sender = Bukkit.getPlayer(request.sender());
                if (sender != null) names.add(sender.getName());
            }
        }
        return names;
    }

    // ---------- 预热 ----------

    private final class WarmupTask implements Runnable {

        private final Player player;
        private final Player other;
        private final Location startLocation;
        private final Location destination;
        private final boolean cancelOnMove;
        private int remaining;
        private BukkitTask task;

        WarmupTask(Player player, Location destination, Player other) {
            this.player = player;
            this.other = other;
            this.startLocation = player.getLocation().clone();
            this.destination = destination;
            this.cancelOnMove = plugin.getConfig().getBoolean(Constants.TELEPORT_CANCEL_ON_MOVE, true);
            this.remaining = plugin.getConfig().getInt(Constants.TELEPORT_WARMUP_SECONDS, 3);
        }

        void start() {
            if (remaining <= 0) {
                finish();
                return;
            }
            player.sendMessage("§e传送将在 §f" + remaining + " §e秒后开始，请勿移动");
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 20L, 20L);
        }

        @Override
        public void run() {
            remaining--;
            if (remaining <= 0) {
                finish();
            } else {
                player.sendMessage("§e传送将在 §f" + remaining + " §e秒后开始，请勿移动");
            }
        }

        private void finish() {
            cancelTask();
            if (player.isOnline()) teleportNow(player, destination);
        }

        /** 移除并停掉计时任务，不发消息。 */
        void cancelTask() {
            warmups.remove(player.getUniqueId());
            if (task != null) task.cancel();
        }

        void cancel(String reason) {
            cancelTask();
            if (player.isOnline()) player.sendMessage(reason);
            if (other != null && other.isOnline()) {
                other.sendMessage("§e" + player.getName() + " §c的传送已取消。");
            }
        }
    }

    // ---------- back ----------

    /** /back：优先死亡点（一次性），否则上次插件传送前的位置。 */
    public void goBack(Player player) {
        UUID uuid = player.getUniqueId();
        Location death = deathLocations.remove(uuid);
        Location destination = death != null ? death : lastLocations.get(uuid);
        if (destination == null) {
            player.sendMessage("§c没有可返回的位置。");
            return;
        }
        teleportNow(player, destination);
    }

    // ---------- 通用传送 ----------

    /** 记录当前位置为"上次位置"，然后立即传送（tpa/warp/back 共用的最终一步）。 */
    public void teleportNow(Player player, Location destination) {
        WarmupTask warmup = warmups.get(player.getUniqueId());
        if (warmup != null) warmup.cancel("§c传送已取消：你发起了新的传送。");
        lastLocations.put(player.getUniqueId(), player.getLocation().clone());
        player.teleportAsync(destination).whenComplete((ok, ex) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (ok != null && ok) {
                    player.sendMessage("§a传送成功！");
                } else {
                    player.sendMessage("§c传送失败，请稍后再试。");
                }
            });
        });
    }

    // ---------- warp ----------

    public Warp getWarp(String name) {
        return warps.get(name);
    }

    public boolean setWarp(Player player, String name) {
        if (!WARP_NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage("§c传送点名称只能包含字母、数字、下划线和短横线（1-32 字符）。");
            return false;
        }
        if (warps.containsKey(name)) {
            player.sendMessage("§c已存在同名传送点 §e" + name + "§c。");
            return false;
        }
        Location loc = player.getLocation();
        Warp warp = new Warp(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
        databaseManager.saveWarp(warp);
        warps.put(name, warp);
        player.sendMessage("§a已设置传送点 §e" + name + "§a。");
        return true;
    }

    public boolean delWarp(Player player, String name) {
        if (warps.remove(name) == null) {
            player.sendMessage("§c传送点 §e" + name + " §c不存在。");
            return false;
        }
        databaseManager.deleteWarp(name);
        player.sendMessage("§a已删除传送点 §e" + name + "§a。");
        return true;
    }

    public List<String> warpNames() {
        return warps.keySet().stream().sorted().toList();
    }

    // ---------- 事件 ----------

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        WarmupTask warmup = warmups.get(event.getPlayer().getUniqueId());
        if (warmup == null || !warmup.cancelOnMove) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return; // 跨世界传送等场景下 to 可能为 null
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return; // 仅视角转动
        }
        if (from.distanceSquared(warmup.startLocation) > MOVE_CANCEL_DISTANCE * MOVE_CANCEL_DISTANCE) {
            warmup.cancel("§c传送已取消：移动距离过大。");
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        WarmupTask warmup = warmups.get(player.getUniqueId());
        if (warmup == null) return;
        warmup.cancel("§c传送已取消：你受到了伤害。");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        deathLocations.put(uuid, event.getEntity().getLocation().clone());
        WarmupTask warmup = warmups.remove(uuid);
        if (warmup != null) {
            warmup.cancelTask();
            if (warmup.other != null && warmup.other.isOnline()) {
                warmup.other.sendMessage("§e" + event.getEntity().getName() + " §c死亡，传送已取消。");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Map<RequestType, TeleportRequest> byType = requests.remove(uuid);
        if (byType != null) {
            for (TeleportRequest request : byType.values()) {
                Player target = Bukkit.getPlayer(request.target());
                if (target != null) {
                    target.sendMessage("§e" + request.senderName() + " §c已离线，其传送请求失效。");
                }
            }
        }
        for (Map<RequestType, TeleportRequest> senderRequests : requests.values()) {
            senderRequests.entrySet().removeIf(entry -> {
                if (!entry.getValue().target().equals(uuid)) return false;
                Player sender = Bukkit.getPlayer(entry.getValue().sender());
                if (sender != null) {
                    sender.sendMessage("§e" + player.getName() + " §c已离线，你的传送请求失效。");
                }
                return true;
            });
        }
        requests.values().removeIf(Map::isEmpty);

        WarmupTask warmup = warmups.remove(uuid);
        if (warmup != null) warmup.cancelTask();

        deathLocations.remove(uuid);
        lastLocations.remove(uuid);
    }
}
