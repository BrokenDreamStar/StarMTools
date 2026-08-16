# 更新日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.2] - 2026-08-16

### 新增

- 新增 `/sethome`、`/home`、`/delhome` 家命令：
  - 每个玩家可设置多个家，数量上限通过 `homes.max-per-player` 配置。
  - 支持默认家 `home` 与自定义家名，家数据持久化到 SQLite。
- 新增集中式消息文件 `message.yml`：
  - 玩家可见提示文本全部可自定义，支持 `&` 颜色代码与 `{占位符}`。
  - 重载或启动时自动回填缺失的消息键。
- 新增配置自动补全机制：
  - 结构性配置项（`fly.*`、`teleport.*`、`homes.*` 等）缺失时自动回填默认值与注释。
  - 用户自定义内容（`right-click-items.*`、`plugins-display.groups.*`）不会被覆盖。
- `/back` 现在会记录其它插件触发的插件传送：
  - 支持在 StarMSkyblock 的 `/is`、`/is spawn` 等命令传送后返回原位置。
- `/back` 增加虚空安全检测：
  - 目标位于虚空时提示玩家不安全，不直接传送。
  - 使用 `/back confirm` 可强制传送到虚空目标。
- `/tpa`、`/tpahere` 接受请求时双方都会收到提示。
- `/tpa`、`/tpahere` 拒绝请求时双方都会收到提示。

### 变更

- 插件内提示文本由硬编码统一迁移到 `message.yml`。
- `/plugins` 显示面板文字从 `config.yml` 迁移到 `message.yml`。
- `/serverinfo` 输出文本迁移到 `message.yml`。
- `/back` 用法更新为 `/back [confirm]`。
- 默认 `message.yml` 采用 StarMSkyblock 服务器现网配置。
- 发布 jar 内现在会附带本更新日志（jar 根目录下的 `CHANGELOG.md`）。
- 版本号由 `1.0.1` 升级为 `1.0.2`。

### 修复

- 修复 `/back` 无法返回 StarMSkyblock `/is`、`/is spawn` 传送前坐标的问题。
- 修复 `/back` 可能将玩家直接传送回虚空的问题。
- 修复 `/plugins` 显示文本散落在 `config.yml` 中、不便于自定义的问题。
- 修复重载后提示文案仍为硬编码、无法按服务器需求修改的问题。
