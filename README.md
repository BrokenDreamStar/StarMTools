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

## 配置文件

首次启动后会在 `plugins/StarMTools/config.yml` 生成完整配置，可随时修改后执行 `/starmtools reload` 热重载。

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

### 自定义 /plugins 显示

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
  messages:
    header: "&a服务器当前加载了 &e{count} &a个插件"
    among: "&a其中"
    others-line: "&a其它:"
    no-permission: "&c你没有权限执行此命令。"
```

不在任何分组中的插件会显示在「其它」中。

## 数据存储

飞行状态与首登记录存储在 SQLite 数据库：

```
plugins/StarMTools/starmtools.db
```

## 技术栈

- [Paper API](https://jd.papermc.io/paper/26.1.2/) 26.1.2
- SQLite (sqlite-jdbc)
- Gradle + [run-paper](https://github.com/jpenilla/run-paper) 本地测试
- Java 25
