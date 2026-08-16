# StarMTools

一个基于 [Paper](https://papermc.io/) 的多功能服务器工具插件，集成了飞行管理、右键物品执行命令、服务器状态监控、首登命令以及自定义 `/plugins` 显示等功能。

- **作者**: [BrokenDream_Star](https://github.com/BrokenDreamStar)、DeepSeek
- **网站**: https://starm.team/

## 功能特性

- ✈️ **飞行管理** (`/fly`)
  - 开启/关闭飞行，状态通过 SQLite 持久化
  - 切换世界、死亡重生后自动恢复飞行状态（可在配置中开关并调整恢复延迟）
- 🖱️ **右键物品执行命令**
  - 配置指定物品右键后自动执行对应命令
  - 每个玩家独立冷却时间，防止误触/滥用
- 📊 **服务器信息** (`/serverinfo`)
  - 展示操作系统、CPU 型号与占用率、内存、磁盘、世界存档大小、服务端与 Java 版本
  - 信息采集在异步线程完成，不会卡顿主线程
- 👋 **首登命令**
  - 玩家首次进入服务器时自动执行一条命令（支持 PlaceholderAPI 占位符）
  - 可通过前缀选择由玩家还是控制台执行
- 📋 **自定义 `/plugins` 显示**
  - 替换原版插件列表，按配置的分组展示插件
  - 插件名按启用状态着色，支持 `&` 颜色代码
- 🏠 **家** (`/sethome`、`/home`、`/delhome`)
  - 每个玩家可设置多个家，数量上限可在配置中修改
  - `/home` 无参数时传送到默认家，传名字传送到对应家

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| 服务端 | Paper 26.1.2+ |
| Java | 25+ |
| PlaceholderAPI | 2.11.6+（可选，软依赖） |

## 构建与安装

```bash
# 构建
./gradlew build

# 产物位于
# build/libs/StarMTools-<version>.jar
```

将构建出的 jar 放入服务端的 `plugins/` 文件夹，重启服务器即可。

本地启动测试服务器：

```bash
./gradlew runServer
```

## 命令与权限

| 命令 | 描述 | 权限 | 默认 |
| --- | --- | --- | --- |
| `/starmtools reload` | 重载配置文件 | 无（OP） | OP |
| `/fly` | 切换飞行模式 | `starmtool.fly` | OP |
| `/serverinfo` | 查看服务器系统信息 | `starmtool.serverinfo` | OP |
| `/sethome [名字]` | 在当前位置设置一个家（默认名 `home`） | `starmtool.sethome` | true |
| `/home [名字]` | 传送到你的家（无参数传送到默认家或列出所有家） | `starmtool.home` | true |
| `/delhome [名字]` | 删除一个家（默认删除 `home`） | `starmtool.delhome` | true |

## 配置文件

首次启动后会在 `plugins/StarMTools/config.yml` 生成完整配置，可随时修改后执行 `/starmtools reload` 热重载。若结构性配置项缺失（如 `fly.*`、`teleport.*` 等误删/漏掉），重载或启动时会自动回填其默认值（连同对应注释）并保存；`right-click-items.*` 与 `plugins-display.groups.*` 属于用户自定义内容，其中的默认示例仅为参考，**绝不会**被重新写回覆盖你的配置。

### 消息文件 message.yml

玩家看到的所有提示文本都在 `plugins/StarMTools/message.yml` 中（首次启动自动生成），可自定义文字与颜色。支持 `&` 颜色代码与 `{占位符}`，不同消息支持的占位符见文件内注释。修改后执行 `/starmtools reload` 生效；缺失的消息键会自动回填默认值。

```yaml
error:
  only-player: "&c只有玩家才能使用此命令。"
  no-permission: "&c你没有权限使用此命令。"
teleport:
  request-prompt: "&e{sender} &a请求{verb}，点击响应：&r  "
  warmup-countdown: "&e传送将在 &f{seconds} &e秒后开始，请勿移动"
```

### 右键物品执行命令

```yaml
right-click-items:
  DIAMOND: "say hello"
```

按物品材质（Material 枚举名）配置，玩家右键持有对应物品时执行命令。完整材质列表见 [Paper Javadoc](https://jd.papermc.io/paper/26.1.2/org/bukkit/Material.html)。

```yaml
cooldown-millis: 1000   # 右键冷却时间（毫秒），0 表示禁用
```

### 首登命令

```yaml
first-join-command: ""
# 格式：
#   "command"              - 由玩家执行（默认）
#   "server command"       - 由控制台执行
#   "player command"       - 由玩家执行
# %player% 占位符需要 PlaceholderAPI
```

### 飞行恢复

```yaml
fly:
  restore-on-world-change: true   # 切换世界时恢复飞行
  restore-on-respawn: true        # 重生后恢复飞行
  restore-delay-ticks: 6          # 恢复延迟（tick，20 tick = 1 秒）
```

### 家（home）设置

```yaml
homes:
  max-per-player: 5   # 每个玩家最多可设置多少个家
```

### 传送功能设置

```yaml
teleport:
  request-timeout-seconds: 60   # tpa/tpahere 请求超时（秒）
  warmup-seconds: 3             # 传送预热时间（秒）
  cancel-on-move: true          # 预热期间移动是否取消传送
  cancel-move-distance: 0.5     # 预热期间移动超过该欧几里得距离（格）即取消传送
```

### 自定义 /plugins 显示

`config.yml` 中配置启用状态、权限、着色与分组：

```yaml
plugins-display:
  enabled: true                    # 是否启用
  permission: "bukkit.command.plugins"   # 查看权限，留空表示所有人
  color-by-status: true            # 启用=绿色，禁用=红色
  groups:
    "由xxx制作的为":
      - StarMTools
    "由yyy制作的为":
      - PlaceholderAPI
```

显示面板的文字（表头、`其它`、无权限提示等）已移至 `message.yml` 的 `plugins-display.messages` 下配置：

```yaml
plugins-display:
  messages:
    header: "&a服务器当前加载了 &e{count} &a个插件"
    among: "&a其中"
    others-line: "&a其它:"
    no-permission: "&c你没有权限执行此命令。"
```

不在任何分组中的插件会显示在「其它」中。

## 数据存储

飞行状态、首登记录、warp 与家(home) 均存储在 SQLite 数据库：

```
plugins/StarMTools/starmtools.db
```

## 技术栈

- [Paper API](https://jd.papermc.io/paper/26.1.2/) 26.1.2
- SQLite (sqlite-jdbc)
- Gradle + [run-paper](https://github.com/jpenilla/run-paper) 本地测试
- Java 25
