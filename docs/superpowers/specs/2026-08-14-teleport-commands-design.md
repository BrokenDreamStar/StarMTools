# 传送命令组设计（tpa / tpahere / back / setwarp / warp）

- 日期：2026-08-14
- 状态：已确认
- 项目：StarMTools（Paper 26.1.2 插件）

## 目标

新增 6 个传送相关命令及其完整功能：

| 命令 | 功能 |
|---|---|
| `/tpa <玩家>` | 请求传送到目标玩家；`/tpa accept|deny` 响应请求 |
| `/tpahere <玩家>` | 请求目标玩家传送到自己；`/tpahere accept|deny` 响应请求 |
| `/back` | 回到死亡点（优先），无死亡点则回到上次传送前的位置 |
| `/setwarp <名字>` | 在自己当前位置设置 warp 点位（含世界，支持跨世界） |
| `/warp <名字>` | 传送到指定 warp；无参数时列出所有点位 |
| `/delwarp <名字>` | 删除指定 warp 点位 |

## 实现方案

采用方案 A：延续现有原生 `TabExecutor` 模式，每命令一个类，共享 `TeleportManager` 服务类。零新依赖。

## 架构与组件

新增类（均在 `team.starm` 包）：

| 类 | 职责 |
|---|---|
| `TeleportManager` | 核心服务：待处理请求 Map、预热倒计时任务、back 位置槽（死亡点/上次传送点）、warp 内存缓存；监听死亡与传送事件 |
| `TpaCommand` | `/tpa` 及其 accept/deny 子命令，TabExecutor |
| `TpaHereCommand` | `/tpahere` 及其 accept/deny 子命令，TabExecutor |
| `BackCommand` | `/back`，TabExecutor |
| `WarpCommand` | `/warp <名字>` 与 `/warplist`（plugin.yml 中注册为 warp 的别名；两者无参数时均列出全部点位），TabExecutor |
| `SetWarpCommand` | `/setwarp <名字>`，TabExecutor |
| `DelWarpCommand` | `/delwarp <名字>`，TabExecutor |

改动现有类：

- `DatabaseManager`：新增 `warps` 表（`name` 主键、`world`、`x/y/z/yaw/pitch`）及 warp CRUD 方法，沿用现有 SQLite + 缓存模式
- `StarMTools`：注册 6 个新命令与 `TeleportManager`（含死亡记录监听器、退服清理）
- `plugin.yml`：新增命令与权限节点
- `config.yml`：新增 `teleport:` 配置段
- `Constants.java`：新增配置路径常量

### 接受/拒绝交互

目标玩家收到带 `[接受] [拒绝]` 可点击文字的聊天消息（`run_command` 触发 `/tpa accept <发送者>` 等），同时保留子命令作为兜底。

## 数据流

### 待处理请求模型

- 每个发送者每种类型（tpa / tpahere）最多一条待处理请求；重复发送覆盖旧请求并通知双方
- 一个目标可同时收到多个不同发送者的请求
- 预热开始后请求即被清除，此时发送者可以再发新请求

### tpa 请求生命周期

```
A 执行 /tpa B
 → 校验（A 在线玩家、B 在线、非自己、权限）
 → 写入待处理请求 {目标:B, 发送者:A, 类型:tpa, 截止时间}
 → B 收到可点击消息；调度 runTaskLater(超时) 到期自动过期并通知双方
B 点击[接受] 或 /tpa accept A
 → 校验请求存在且未过期 → 清除请求 → 进入预热
 → 若被传送方（tpa 为 A）已有预热中的传送 → 提示并忽略
 → 倒计时 runTaskTimer（每秒提示剩余秒数）
 → 期间被传送方移动超过 0.5 格（三维距离）或受到伤害 → 取消并提示
 → 倒计时结束：传送 A → B（跨世界自动切换）
```

- `tpahere` 对称（接受后被传送方为 B，传送方向 B → A）
- 超时时间可配置（默认 60 秒）

### back 生命周期（每玩家两个位置槽，内存态，不持久化）

- `deathLoc`：死亡时记录死亡点（覆盖旧值）
- `lastLoc`：每次插件成功传送（tpa/tpahere/warp/back）前记录当前位置
- `/back` 逻辑：`deathLoc` 存在 → 传送到死亡点并**清空** `deathLoc`（一次性）；否则用 `lastLoc`

### warp 数据流

- `setwarp <名字>`：写入 SQLite `warps` 表 + 内存缓存（记录世界名）
- `warp <名字>`：查缓存/DB → 校验世界已加载 → 直接传送（无预热）
- `delwarp` / `warplist`：对应删改查

### 清理

玩家退服 → 清空其待处理请求（无论作为发送者还是目标）、取消其预热任务、移除 back 位置槽。

## 配置

`config.yml` 新增：

```yaml
# 传送功能设置
teleport:
  # tpa/tpahere 请求超时（秒）
  request-timeout-seconds: 60
  # 接受请求后传送预热时间（秒），期间移动或受伤害会取消
  warmup-seconds: 3
  # 预热期间移动超过 0.5 格是否取消
  cancel-on-move: true
```

## 权限

| 权限节点 | 默认值 |
|---|---|
| `starmtool.tpa` | true |
| `starmtool.tpahere` | true |
| `starmtool.back` | true |
| `starmtool.warp` | true |
| `starmtool.setwarp` | op |
| `starmtool.delwarp` | op |

`/warp` 无参数时列出点位（warplist 行为）同样要求 `starmtool.warp`，无单独权限节点。

## 错误处理与消息

- 消息沿用现有风格：中文、`§` 颜色码硬编码（与 `FlyCommand` 一致）
- 覆盖场景：
  - 无权限提示
  - 控制台使用提示（全部为玩家命令）
  - 目标玩家不存在/离线
  - 请求自己
  - 无待处理请求 / 请求已过期
  - 目标已有预热中的传送
  - warp 不存在 / 名字重名 / 名字非法（限制 `[a-zA-Z0-9_-]{1,32}`）
  - warp 所在世界未加载
  - 预热因移动/受伤取消的双方提示
  - 请求超时的双方提示

## 测试

由用户自行在开发服测试（双账号验证 tpa/tpahere 全流程、back 死亡点与出发地、warp 全套流程、权限矩阵）。提交到 `dev` 分支（main 用于发布）。

## 范围外（本次不做）

- `tpacancel`（用户未选择）
- 传送粒子/音效
- 请求队列（一次只保留一个待处理请求，重复发送覆盖）
- 经济消耗（Vault 集成）
