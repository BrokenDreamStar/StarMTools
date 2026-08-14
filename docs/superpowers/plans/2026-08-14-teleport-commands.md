# 传送命令组实现计划（tpa / tpahere / back / setwarp / warp / delwarp）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 StarMTools 插件新增 tpa/tpahere/back/setwarp/warp/delwarp 六个传送命令及其完整功能（请求/接受/拒绝、预热取消、死亡点返回、SQLite 持久化 warp）。

**Architecture:** 延续现有原生 `TabExecutor` 模式：每个命令一个类，全部委托给新增的 `TeleportManager` 服务类（请求状态、预热倒计时、back 位置槽、warp 缓存，并通过 Listener 处理死亡/移动/受伤/退服事件）。warp 点位持久化到现有 SQLite（`DatabaseManager` 新增 `warps` 表），零新依赖。

**Tech Stack:** Paper API 26.1.2（compileOnly）、Java 25、SQLite（已有 sqlite-jdbc）、Kyori Adventure（Paper 自带，用于可点击聊天消息）。

**设计文档:** `docs/superpowers/specs/2026-08-14-teleport-commands-design.md`

## Global Constraints

- 包名 `team.starm`，扁平结构，与现有代码一致（不新建子包）
- 消息为中文、`§` 颜色码硬编码（与 `FlyCommand` 一致）
- 权限节点前缀 `starmtool.`；tpa/tpahere/back/warp `default: true`，setwarp/delwarp `default: op`
- 配置路径常量统一放在 `Constants.java`
- **用户明确要求自行功能测试（"无需你验证 我自行测试"）：本计划不做单元测试、不起服验证。每个任务的验收标准仅为 `./gradlew compileJava` 编译通过**
- 所有提交在 `dev` 分支（main 用于发布），每任务结束时 commit
- 不新增任何依赖

## 文件结构

**新建：**
- `src/main/java/team/starm/Warp.java` — warp 点位数据 record
- `src/main/java/team/starm/TeleportManager.java` — 核心服务 + 事件监听
- `src/main/java/team/starm/TpaCommand.java` — /tpa（含 accept/deny）
- `src/main/java/team/starm/TpaHereCommand.java` — /tpahere（含 accept/deny）
- `src/main/java/team/starm/BackCommand.java` — /back
- `src/main/java/team/starm/WarpCommand.java` — /warp 与 /warplist（别名）
- `src/main/java/team/starm/SetWarpCommand.java` — /setwarp
- `src/main/java/team/starm/DelWarpCommand.java` — /delwarp

**修改：**
- `src/main/java/team/starm/DatabaseManager.java` — 新增 `warps` 表与 CRUD
- `src/main/java/team/starm/StarMTools.java` — 接线（构造 manager、注册监听器与命令）
- `src/main/java/team/starm/Constants.java` — 新增 3 个配置路径常量
- `src/main/resources/plugin.yml` — 6 个新命令 + 6 个新权限
- `src/main/resources/config.yml` — 新增 `teleport:` 配置段

---

### Task 1: 数据层 — warps 表与 Warp record

**Files:**
- Create: `src/main/java/team/starm/Warp.java`
- Modify: `src/main/java/team/starm/DatabaseManager.java`（init() 建表 + 3 个 CRUD 方法 + imports）
- Modify: `src/main/java/team/starm/Constants.java`（追加 3 个常量）
- Modify: `src/main/resources/config.yml`（新增 teleport 段）

**Interfaces:**
- Consumes: 无（首任务）
- Produces:
  - `Warp(String name, String world, double x, double y, double z, float yaw, float pitch)` record，含 `Location toLocation()`（世界未加载时返回 null）
  - `DatabaseManager.saveWarp(Warp)` / `deleteWarp(String)` / `listWarps() -> List<Warp>`（按 name 排序）
  - `Constants.TELEPORT_REQUEST_TIMEOUT_SECONDS` / `TELEPORT_WARMUP_SECONDS` / `TELEPORT_CANCEL_ON_MOVE`

- [ ] **Step 1: 新建 Warp.java**

完整文件内容：

```java
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
```

- [ ] **Step 2: DatabaseManager 建表**

修改 `src/main/java/team/starm/DatabaseManager.java`：

在 `init()` 的 try-with-resources 块内、`player_data` 表的 `stmt.execute` 之后追加：

```java
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS warps (
                        name VARCHAR(32) PRIMARY KEY,
                        world VARCHAR(255) NOT NULL,
                        x DOUBLE NOT NULL,
                        y DOUBLE NOT NULL,
                        z DOUBLE NOT NULL,
                        yaw FLOAT NOT NULL,
                        pitch FLOAT NOT NULL
                    )
                """);
```

- [ ] **Step 3: DatabaseManager 新增 CRUD 方法**

文件顶部 import 区追加：

```java
import java.util.ArrayList;
import java.util.List;
```

在 `removeFromCache` 方法之前插入以下三个方法（沿用现有异常处理风格）：

```java
    public void saveWarp(Warp warp) {
        if (connection == null) return;
        String sql = "INSERT OR REPLACE INTO warps (name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, warp.name());
            ps.setString(2, warp.world());
            ps.setDouble(3, warp.x());
            ps.setDouble(4, warp.y());
            ps.setDouble(5, warp.z());
            ps.setFloat(6, warp.yaw());
            ps.setFloat(7, warp.pitch());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save warp " + warp.name(), e);
        }
    }

    public void deleteWarp(String name) {
        if (connection == null) return;
        String sql = "DELETE FROM warps WHERE name = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete warp " + name, e);
        }
    }

    public List<Warp> listWarps() {
        List<Warp> warps = new ArrayList<>();
        if (connection == null) return warps;
        String sql = "SELECT name, world, x, y, z, yaw, pitch FROM warps ORDER BY name";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                warps.add(new Warp(rs.getString("name"), rs.getString("world"),
                        rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getFloat("yaw"), rs.getFloat("pitch")));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to list warps", e);
        }
        return warps;
    }
```

- [ ] **Step 4: Constants 新增常量**

修改 `src/main/java/team/starm/Constants.java`，在 `FIRST_JOIN_COMMAND` 行之后追加：

```java
    public static final String TELEPORT_REQUEST_TIMEOUT_SECONDS = "teleport.request-timeout-seconds";
    public static final String TELEPORT_WARMUP_SECONDS = "teleport.warmup-seconds";
    public static final String TELEPORT_CANCEL_ON_MOVE = "teleport.cancel-on-move";
```

- [ ] **Step 5: config.yml 新增 teleport 段**

修改 `src/main/resources/config.yml`，在 fly 段末尾（`restore-delay-ticks: 6` 一行之后、`# /plugins` 注释之前）插入：

```yaml

# 传送功能设置
teleport:
  # tpa/tpahere 请求超时（秒）
  request-timeout-seconds: 60
  # 接受请求后传送预热时间（秒），设为 0 立即传送；期间移动或受伤害会取消
  warmup-seconds: 3
  # 预热期间移动超过 0.5 格是否取消传送
  cancel-on-move: true
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
git add src/main/java/team/starm/Warp.java src/main/java/team/starm/DatabaseManager.java src/main/java/team/starm/Constants.java src/main/resources/config.yml
git commit -m "feat: add warp storage layer (warps table, Warp record, teleport config)"
```

---

### Task 2: TeleportManager 核心服务

**Files:**
- Create: `src/main/java/team/starm/TeleportManager.java`
- Modify: `src/main/java/team/starm/StarMTools.java`（构造 manager 并注册监听器）

**Interfaces:**
- Consumes: Task 1 的 `Warp`、`DatabaseManager.listWarps()`、`Constants.TELEPORT_*`
- Produces（后续任务全部依赖，签名不得改动）:
  - `enum TeleportManager.RequestType { TPA, TPAHERE }`
  - `boolean sendRequest(Player sender, String targetName, RequestType type)` — 发送/覆盖请求，调度超时
  - `void accept(Player target, String senderName, RequestType type)` — 接受并启动预热
  - `void deny(Player target, String senderName, RequestType type)` — 拒绝
  - `void goBack(Player player)` — /back 逻辑
  - `void teleportNow(Player player, Location destination)` — 记录 lastLoc 后立即传送（back/warp 用）
  - `boolean setWarp(Player player, String name)` / `boolean delWarp(Player player, String name)`
  - `Warp getWarp(String name)` / `List<String> warpNames()`（排序后）
  - `List<String> pendingSenderNames(Player target, RequestType type)` — accept/deny 的 tab 补全

- [ ] **Step 1: 新建 TeleportManager.java**

完整文件内容：

```java
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
        lastLocations.put(player.getUniqueId(), player.getLocation().clone());
        player.teleportAsync(destination);
        player.sendMessage("§a传送成功！");
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
```

- [ ] **Step 2: StarMTools 接线（构造 + 监听器注册）**

修改 `src/main/java/team/starm/StarMTools.java`，在 `databaseManager.init();` 一行之后插入：

```java
        TeleportManager teleportManager = new TeleportManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(teleportManager, this);
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
git add src/main/java/team/starm/TeleportManager.java src/main/java/team/starm/StarMTools.java
git commit -m "feat: add TeleportManager core (requests, warmup, back, warp cache)"
```

---

### Task 3: tpa / tpahere 命令

**Files:**
- Create: `src/main/java/team/starm/TpaCommand.java`
- Create: `src/main/java/team/starm/TpaHereCommand.java`
- Modify: `src/main/resources/plugin.yml`（commands + permissions）
- Modify: `src/main/java/team/starm/StarMTools.java`（注册两个 executor）

**Interfaces:**
- Consumes: Task 2 的 `TeleportManager` 全部公开方法
- Produces: `/tpa`、`/tpahere` 命令（含 accept/deny 子命令与 tab 补全）

- [ ] **Step 1: 新建 TpaCommand.java**

完整文件内容：

```java
package team.starm;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class TpaCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public TpaCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.tpa")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            teleportManager.accept(player, args[1], TeleportManager.RequestType.TPA);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deny")) {
            teleportManager.deny(player, args[1], TeleportManager.RequestType.TPA);
            return true;
        }
        if (args.length == 1) {
            teleportManager.sendRequest(player, args[0], TeleportManager.RequestType.TPA);
            return true;
        }
        player.sendMessage("§c用法: /tpa <玩家> 或 /tpa accept|deny <玩家>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("accept", "deny"));
            for (Player online : Bukkit.getOnlinePlayers()) options.add(online.getName());
            return filter(options, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            return filter(teleportManager.pendingSenderNames(player, TeleportManager.RequestType.TPA), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) result.add(option);
        }
        return result;
    }
}
```

- [ ] **Step 2: 新建 TpaHereCommand.java**

完整文件内容（与 TpaCommand 对称，类型改为 TPAHERE、权限改为 tpahere、提示改为 tpahere）：

```java
package team.starm;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class TpaHereCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public TpaHereCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.tpahere")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            teleportManager.accept(player, args[1], TeleportManager.RequestType.TPAHERE);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("deny")) {
            teleportManager.deny(player, args[1], TeleportManager.RequestType.TPAHERE);
            return true;
        }
        if (args.length == 1) {
            teleportManager.sendRequest(player, args[0], TeleportManager.RequestType.TPAHERE);
            return true;
        }
        player.sendMessage("§c用法: /tpahere <玩家> 或 /tpahere accept|deny <玩家>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("accept", "deny"));
            for (Player online : Bukkit.getOnlinePlayers()) options.add(online.getName());
            return filter(options, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            return filter(teleportManager.pendingSenderNames(player, TeleportManager.RequestType.TPAHERE), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) result.add(option);
        }
        return result;
    }
}
```

- [ ] **Step 3: plugin.yml 注册命令**

修改 `src/main/resources/plugin.yml`，在 `commands:` 段的 `serverinfo` 块之后追加：

```yaml
  tpa:
    description: "Request to teleport to a player"
    usage: "/tpa <player>"
  tpahere:
    description: "Request a player to teleport to you"
    usage: "/tpahere <player>"
```

- [ ] **Step 4: plugin.yml 注册权限**

修改 `src/main/resources/plugin.yml`，在 `permissions:` 段的 `starmtool.serverinfo` 块之后追加：

```yaml
  starmtool.tpa:
    description: "Allows use of /tpa command"
    default: true
  starmtool.tpahere:
    description: "Allows use of /tpahere command"
    default: true
```

- [ ] **Step 5: StarMTools 注册 executor**

修改 `src/main/java/team/starm/StarMTools.java`，在 `getCommand("serverinfo").setExecutor(new ServerInfoCommand(this));` 一行之后追加：

```java
        getCommand("tpa").setExecutor(new TpaCommand(this, teleportManager));
        getCommand("tpahere").setExecutor(new TpaHereCommand(this, teleportManager));
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
git add src/main/java/team/starm/TpaCommand.java src/main/java/team/starm/TpaHereCommand.java src/main/resources/plugin.yml src/main/java/team/starm/StarMTools.java
git commit -m "feat: add tpa and tpahere commands with clickable accept/deny"
```

---

### Task 4: back 命令

**Files:**
- Create: `src/main/java/team/starm/BackCommand.java`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/java/team/starm/StarMTools.java`

**Interfaces:**
- Consumes: Task 2 的 `TeleportManager.goBack(Player)`
- Produces: `/back` 命令

- [ ] **Step 1: 新建 BackCommand.java**

完整文件内容：

```java
package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class BackCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public BackCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.back")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        teleportManager.goBack(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }
}
```

- [ ] **Step 2: plugin.yml 注册命令与权限**

修改 `src/main/resources/plugin.yml`：

在 `commands:` 段末尾追加：

```yaml
  back:
    description: "Teleport back to death point or last location"
    usage: "/back"
```

在 `permissions:` 段末尾追加：

```yaml
  starmtool.back:
    description: "Allows use of /back command"
    default: true
```

- [ ] **Step 3: StarMTools 注册 executor**

在 `getCommand("tpahere").setExecutor(...)` 一行之后追加：

```java
        getCommand("back").setExecutor(new BackCommand(this, teleportManager));
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交**

```bash
git add src/main/java/team/starm/BackCommand.java src/main/resources/plugin.yml src/main/java/team/starm/StarMTools.java
git commit -m "feat: add back command"
```

---

### Task 5: warp / setwarp / delwarp 命令

**Files:**
- Create: `src/main/java/team/starm/WarpCommand.java`
- Create: `src/main/java/team/starm/SetWarpCommand.java`
- Create: `src/main/java/team/starm/DelWarpCommand.java`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/java/team/starm/StarMTools.java`

**Interfaces:**
- Consumes: Task 2 的 `getWarp` / `setWarp` / `delWarp` / `warpNames` / `teleportNow`
- Produces: `/warp`（别名 `/warplist`，无参数时列出点位）、`/setwarp`、`/delwarp` 命令

- [ ] **Step 1: 新建 WarpCommand.java**

完整文件内容：

```java
package team.starm;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class WarpCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public WarpCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.warp")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        if (args.length == 0) {
            List<String> names = teleportManager.warpNames();
            if (names.isEmpty()) {
                player.sendMessage("§c还没有设置任何传送点。");
            } else {
                player.sendMessage("§a可用传送点: §e" + String.join("§7, §e", names));
            }
            return true;
        }
        if (args.length == 1) {
            Warp warp = teleportManager.getWarp(args[0]);
            if (warp == null) {
                player.sendMessage("§c传送点 §e" + args[0] + " §c不存在。");
                return true;
            }
            Location location = warp.toLocation();
            if (location == null) {
                player.sendMessage("§c传送点所在世界未加载。");
                return true;
            }
            teleportManager.teleportNow(player, location);
            return true;
        }
        player.sendMessage("§c用法: /warp <名字>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) return List.of();
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String name : teleportManager.warpNames()) {
                if (name.toLowerCase().startsWith(args[0].toLowerCase())) result.add(name);
            }
            return result;
        }
        return List.of();
    }
}
```

- [ ] **Step 2: 新建 SetWarpCommand.java**

完整文件内容：

```java
package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class SetWarpCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public SetWarpCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.setwarp")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("§c用法: /setwarp <名字>");
            return true;
        }
        teleportManager.setWarp(player, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return List.of();
    }
}
```

- [ ] **Step 3: 新建 DelWarpCommand.java**

完整文件内容：

```java
package team.starm;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class DelWarpCommand implements TabExecutor {

    private final StarMTools plugin;
    private final TeleportManager teleportManager;

    public DelWarpCommand(StarMTools plugin, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c只有玩家才能使用此命令。");
            return true;
        }
        if (!player.hasPermission("starmtool.delwarp")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("§c用法: /delwarp <名字>");
            return true;
        }
        teleportManager.delWarp(player, args[0]);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) return List.of();
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String name : teleportManager.warpNames()) {
                if (name.toLowerCase().startsWith(args[0].toLowerCase())) result.add(name);
            }
            return result;
        }
        return List.of();
    }
}
```

- [ ] **Step 4: plugin.yml 注册命令与权限**

修改 `src/main/resources/plugin.yml`：

在 `commands:` 段末尾追加：

```yaml
  warp:
    description: "Teleport to a warp point"
    usage: "/warp <name>"
    aliases: [warplist]
  setwarp:
    description: "Set a warp point at your location"
    usage: "/setwarp <name>"
  delwarp:
    description: "Delete a warp point"
    usage: "/delwarp <name>"
```

在 `permissions:` 段末尾追加：

```yaml
  starmtool.warp:
    description: "Allows use of /warp command"
    default: true
  starmtool.setwarp:
    description: "Allows use of /setwarp command"
    default: op
  starmtool.delwarp:
    description: "Allows use of /delwarp command"
    default: op
```

- [ ] **Step 5: StarMTools 注册 executor**

在 `getCommand("back").setExecutor(...)` 一行之后追加：

```java
        getCommand("warp").setExecutor(new WarpCommand(this, teleportManager));
        getCommand("setwarp").setExecutor(new SetWarpCommand(this, teleportManager));
        getCommand("delwarp").setExecutor(new DelWarpCommand(this, teleportManager));
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
git add src/main/java/team/starm/WarpCommand.java src/main/java/team/starm/SetWarpCommand.java src/main/java/team/starm/DelWarpCommand.java src/main/resources/plugin.yml src/main/java/team/starm/StarMTools.java
git commit -m "feat: add warp, setwarp and delwarp commands"
```

---

### Task 6: 收尾 — 全量构建与设计对照

**Files:** 无新文件（仅检查）

- [ ] **Step 1: 全量构建**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 对照设计文档逐项检查**

打开 `docs/superpowers/specs/2026-08-14-teleport-commands-design.md`，确认：

| 设计项 | 落点 |
|---|---|
| 6 个命令全部注册 | `plugin.yml` commands 段（warp 带 warplist 别名） |
| 6 个权限节点与默认值 | `plugin.yml` permissions 段（tpa/tpahere/back/warp=true，setwarp/delwarp=op） |
| 可点击 [接受]/[拒绝] + 子命令兜底 | `TeleportManager.sendRequest` + `TpaCommand`/`TpaHereCommand` accept/deny 分支 |
| 可配置超时（默认 60 秒） | `sendRequest` 读 `teleport.request-timeout-seconds` + `expireIfPending` |
| 预热（默认 3 秒）+ 移动 0.5 格/受伤取消 | `WarmupTask` + `onPlayerMove`/`onPlayerDamage` |
| back：死亡点优先（一次性），否则上次位置 | `goBack` + `onPlayerDeath` + `teleportNow` |
| warp SQLite 持久化 + 跨世界 | `DatabaseManager` warps 表 + `Warp.toLocation` |
| 退服清理请求/预热/back 槽 | `onPlayerQuit` |
| 名字限制 `[a-zA-Z0-9_-]{1,32}` | `WARP_NAME_PATTERN` |

发现遗漏则修复并重新 `./gradlew compileJava`。

- [ ] **Step 3: 若有改动则提交**

```bash
git add -A
git commit -m "fix: teleport feature final review fixes"
```

（无改动则跳过此步。）

- [ ] **Step 4: 完成提示**

向用户报告：实现完成、已提交 dev 分支、请自行在开发服测试。提醒两点测试要点：
1. run-paper 开发服用 **25599** 端口（用户线上服占用 25565），`./gradlew runServer` 起服；
2. 开发服已有的 `run/plugins/StarMTools/config.yml` 不会自动追加 teleport 段（`saveDefaultConfig` 仅在文件不存在时生效），代码对缺失键使用默认值（60/3/true），如需改配置手动补段或删除该文件重建。
