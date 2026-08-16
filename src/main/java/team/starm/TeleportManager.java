package team.starm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 传送功能核心：待处理请求、预热倒计时、back 位置槽与虚空确认、warp 缓存。
 * 所有方法均在主线程调用。
 */
public class TeleportManager implements Listener {

    public enum RequestType { TPA, TPAHERE }

    public record TeleportRequest(UUID sender, String senderName, UUID target, String targetName,
                                  RequestType type, long expireAtMillis) {}

    /** 等待玩家输入 /back confirm 强制传送的虚空目标。 */
    private record PendingBack(Location destination, boolean fromDeath) {}

    private static final Pattern WARP_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    private final StarMTools plugin;
    private final DatabaseManager databaseManager;
    /** 发送者 UUID -> 类型 -> 请求（每个发送者每种类型最多一条）。 */
    private final Map<UUID, Map<RequestType, TeleportRequest>> requests = new ConcurrentHashMap<>();
    /** 被传送方 UUID -> 预热任务。 */
    private final Map<UUID, WarmupTask> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, Location> deathLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastLocations = new ConcurrentHashMap<>();
    private final Map<UUID, PendingBack> pendingBacks = new ConcurrentHashMap<>();
    private final Map<String, Warp> warps = new ConcurrentHashMap<>();

    public TeleportManager(StarMTools plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        for (Warp warp : databaseManager.listWarps()) {
            warps.put(warp.name(), warp);
        }
    }

    private MessageManager messages() {
        return plugin.getMessageManager();
    }

    // ---------- tpa / tpahere ----------

    public boolean sendRequest(Player sender, String targetName, RequestType type) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages().send(sender, "teleport.player-not-found");
            return false;
        }
        if (target.equals(sender)) {
            messages().send(sender, "teleport.cannot-self");
            return false;
        }

        Map<RequestType, TeleportRequest> byType =
                requests.computeIfAbsent(sender.getUniqueId(), k -> new ConcurrentHashMap<>());
        TeleportRequest old = byType.get(type);
        if (old != null && old.expireAtMillis() > System.currentTimeMillis()) {
            Player oldTarget = Bukkit.getPlayer(old.target());
            if (oldTarget != null && !oldTarget.equals(target)) {
                messages().send(oldTarget, "teleport.revoked-previous", "player=" + sender.getName());
            }
        }

        int timeoutSeconds = plugin.getConfig().getInt(Constants.TELEPORT_REQUEST_TIMEOUT_SECONDS, 60);
        TeleportRequest request = new TeleportRequest(sender.getUniqueId(), sender.getName(),
                target.getUniqueId(), target.getName(), type,
                System.currentTimeMillis() + timeoutSeconds * 1000L);
        byType.put(type, request);

        String verb = messages().get(type == RequestType.TPA ? "teleport.verb-to" : "teleport.verb-here");
        String cmd = type == RequestType.TPA ? "tpa" : "tpahere";
        String acceptText = messages().get("teleport.request-accept-button");
        String denyText = messages().get("teleport.request-deny-button");
        Component accept = MessageManager.component(acceptText)
                .clickEvent(ClickEvent.runCommand("/" + cmd + " accept " + sender.getName()));
        Component deny = MessageManager.component(denyText)
                .clickEvent(ClickEvent.runCommand("/" + cmd + " deny " + sender.getName()));
        target.sendMessage(messages().component("teleport.request-prompt",
                "sender=" + sender.getName(), "verb=" + verb)
                .append(accept).append(Component.text("  ")).append(deny));
        messages().send(sender, "teleport.request-sent",
                "target=" + target.getName(), "timeout=" + timeoutSeconds);

        Bukkit.getScheduler().runTaskLater(plugin,
                () -> expireIfPending(sender.getUniqueId(), type, request), timeoutSeconds * 20L);
        return true;
    }

    public void accept(Player target, String senderName, RequestType type) {
        Player sender = Bukkit.getPlayerExact(senderName);
        if (sender == null) {
            messages().send(target, "teleport.target-not-found");
            return;
        }
        Map<RequestType, TeleportRequest> byType = requests.get(sender.getUniqueId());
        TeleportRequest request = byType == null ? null : byType.get(type);
        if (request == null || request.expireAtMillis() <= System.currentTimeMillis()
                || !request.target().equals(target.getUniqueId())) {
            messages().send(target, "teleport.no-pending-request", "player=" + senderName);
            return;
        }

        Player moving = type == RequestType.TPA ? sender : target;
        if (warmups.containsKey(moving.getUniqueId())) {
            messages().send(target, "teleport.already-warming");
            return;
        }

        byType.remove(type);
        if (byType.isEmpty()) requests.remove(sender.getUniqueId());

        // 通知双方：请求发送者收到"对方已接受"，接受方收到自己的确认提示
        messages().send(sender, "teleport.request-accepted", "target=" + target.getName());
        messages().send(target, "teleport.accept-confirmed", "sender=" + sender.getName());

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
            messages().send(target, "teleport.no-pending-request", "player=" + senderName);
            return;
        }
        byType.remove(type);
        if (byType.isEmpty()) requests.remove(sender.getUniqueId());
        // 通知双方：请求发送者收到"对方已拒绝"，拒绝方收到自己的确认提示
        messages().send(sender, "teleport.target-denied", "target=" + target.getName());
        messages().send(target, "teleport.request-denied", "sender=" + sender.getName());
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
            messages().send(sender, "teleport.sender-request-expired", "target=" + expected.targetName());
        }
        if (target != null) {
            messages().send(target, "teleport.target-request-expired", "sender=" + expected.senderName());
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
            showTimer();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 20L, 20L);
        }

        @Override
        public void run() {
            remaining--;
            if (remaining <= 0) {
                finish();
            } else {
                showTimer();
            }
        }

        /** 在动作栏显示当前剩余秒数。 */
        private void showTimer() {
            player.sendActionBar(messages().component("teleport.warmup-countdown", "seconds=" + remaining));
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

        void cancel(String legacyReason) {
            cancelTask();
            if (player.isOnline()) {
                player.sendActionBar(Component.empty()); // 清除残留倒计时
                player.sendMessage(MessageManager.component(legacyReason));
            }
            if (other != null && other.isOnline()) {
                messages().send(other, "teleport.cancel-by-other", "player=" + player.getName());
            }
        }
    }

    // ---------- back ----------

    /** /back：优先死亡点（一次性），否则上次插件传送前的位置。目标位于虚空时需 /back confirm 确认。 */
    public void goBack(Player player, boolean confirmed) {
        UUID uuid = player.getUniqueId();
        if (confirmed) {
            confirmUnsafeBack(player);
            return;
        }

        Location death = deathLocations.get(uuid);
        Location destination = death != null ? death : lastLocations.get(uuid);
        if (destination == null) {
            messages().send(player, "teleport.back-no-location");
            return;
        }
        if (isInVoid(destination)) {
            pendingBacks.put(uuid, new PendingBack(destination.clone(), death != null));
            messages().send(player, "teleport.back-unsafe");
            return;
        }

        // 只有真正执行传送时才消耗死亡点，避免"提示不安全"后死亡点被白白清掉
        if (death != null) {
            deathLocations.remove(uuid);
        }
        pendingBacks.remove(uuid);
        teleportNow(player, destination);
    }

    /** /back confirm：对刚提示过的虚空目标执行强制传送。 */
    private void confirmUnsafeBack(Player player) {
        UUID uuid = player.getUniqueId();
        PendingBack pending = pendingBacks.remove(uuid);
        if (pending == null) {
            messages().send(player, "teleport.back-confirm-no-pending");
            return;
        }
        if (pending.fromDeath()) {
            deathLocations.remove(uuid);
        }
        teleportNow(player, pending.destination());
    }

    /** 目标世界未加载或 Y 不高于世界最低高度时视为虚空。 */
    private boolean isInVoid(Location destination) {
        if (destination == null || destination.getWorld() == null) {
            return true;
        }
        return destination.getY() <= destination.getWorld().getMinHeight();
    }

    // ---------- 通用传送 ----------

    /** 记录当前位置为"上次位置"，然后立即传送（tpa/warp/back 共用的最终一步）。 */
    public void teleportNow(Player player, Location destination) {
        WarmupTask warmup = warmups.get(player.getUniqueId());
        if (warmup != null) {
            warmup.cancel(messages().get("teleport.cancelled-new-teleport"));
        }
        lastLocations.put(player.getUniqueId(), player.getLocation().clone());
        player.teleportAsync(destination).whenComplete((ok, ex) -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (ok != null && ok) {
                    messages().send(player, "teleport.success");
                } else {
                    messages().send(player, "teleport.fail");
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
            messages().send(player, "warp.name-invalid");
            return false;
        }
        if (warps.containsKey(name)) {
            messages().send(player, "warp.already-exists", "name=" + name);
            return false;
        }
        Location loc = player.getLocation();
        Warp warp = new Warp(name, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch());
        databaseManager.saveWarp(warp);
        warps.put(name, warp);
        messages().send(player, "warp.set", "name=" + name);
        return true;
    }

    public boolean delWarp(Player player, String name) {
        if (warps.remove(name) == null) {
            messages().send(player, "warp.delete-not-exist", "name=" + name);
            return false;
        }
        databaseManager.deleteWarp(name);
        messages().send(player, "warp.deleted", "name=" + name);
        return true;
    }

    public List<String> warpNames() {
        return warps.keySet().stream().sorted().toList();
    }

    // ---------- 事件 ----------

    /**
     * 记录其它插件（例如 StarMSkyblock 的 /is、/is spawn）触发的传送，
     * 使 /back 也能返回这些传送前的位置。
     * 使用 MONITOR + ignoreCancelled 仅记录实际发生的插件传送。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != TeleportCause.PLUGIN) return;
        Location from = event.getFrom();
        if (from.getWorld() == null) return;
        lastLocations.put(event.getPlayer().getUniqueId(), from.clone());
    }

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
        double cancelDistance = plugin.getConfig().getDouble(
                Constants.TELEPORT_CANCEL_MOVE_DISTANCE, 0.5);
        if (from.distanceSquared(warmup.startLocation) > cancelDistance * cancelDistance) {
            warmup.cancel(messages().get("teleport.cancel-move"));
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        WarmupTask warmup = warmups.get(player.getUniqueId());
        if (warmup == null) return;
        warmup.cancel(messages().get("teleport.cancel-damage"));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        deathLocations.put(uuid, event.getEntity().getLocation().clone());
        pendingBacks.remove(uuid); // 新死亡点会取代旧的待确认返回目标
        WarmupTask warmup = warmups.remove(uuid);
        if (warmup != null) {
            warmup.cancelTask();
            if (warmup.other != null && warmup.other.isOnline()) {
                messages().send(warmup.other, "teleport.cancel-death", "player=" + event.getEntity().getName());
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
                    messages().send(target, "teleport.quit-invalidates", "player=" + request.senderName());
                }
            }
        }
        for (Map<RequestType, TeleportRequest> senderRequests : requests.values()) {
            senderRequests.entrySet().removeIf(entry -> {
                if (!entry.getValue().target().equals(uuid)) return false;
                Player sender = Bukkit.getPlayer(entry.getValue().sender());
                if (sender != null) {
                    messages().send(sender, "teleport.quit-invalidates-yours", "player=" + player.getName());
                }
                return true;
            });
        }
        requests.values().removeIf(Map::isEmpty);

        WarmupTask warmup = warmups.remove(uuid);
        if (warmup != null) warmup.cancelTask();

        deathLocations.remove(uuid);
        lastLocations.remove(uuid);
        pendingBacks.remove(uuid);
    }
}
