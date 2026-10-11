<!-- 翻译自 README.md @ docs/readme-template-v2。只翻 "For AI agents" 以上的章节，英文版更新时一起改。 -->

<div align="center">

<h1>serverscope</h1>

<p><strong>在玩家告诉你之前，就知道模组 Minecraft 服务器崩了，以及为什么。</strong></p>

[![Build](https://img.shields.io/github/actions/workflow/status/zyx1121/serverscope/build.yml?branch=main&style=flat-square&label=build)](https://github.com/zyx1121/serverscope/actions)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-DF6E1E?style=flat-square)](gradle.properties)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-OTLP-425CC7?style=flat-square&logo=opentelemetry&logoColor=white)](src/main/java/tw/zyx/serverscope/core/Telemetry.java)
[![License](https://img.shields.io/github/license/zyx1121/serverscope?style=flat-square)](LICENSE)

[English](README.md) | [繁體中文](README.zh-TW.md) | **简体中文**

</div>

小型模组服务器出问题，第一个征兆常常是一条聊天消息：服务器怎么这么卡，或怎么挂了？serverscope 把每个 tick、加入、死亡、指令与崩溃都变成 OpenTelemetry trace 与 metric。这样在有人开口之前，仪表盘就先给出答案。它运行在一台真实的 NeoForge 1.21.1「All the Mons」服务器上。

## 功能

- **OpenTelemetry 信号**：生命周期、玩家、聊天、指令、成就与死亡事件用 span。高频事件用 counter，TPS、MSPT、玩家、实体与 chunk 用 gauge。
- **JVM 健康状态**：从 `java.lang.management` 获取 heap 与 GC 数据，内存压力在 OOM 之前就看得到。
- **非正常重启警报**：启动时 mod 比对状态文件与上次运行，发出 `server.unclean_restart`。有崩溃报告时会附上它的描述。
- **OTLP over HTTP，不用 autoconfigure**：手动创建 SDK provider，没有 ServiceLoader 的意外。
- **模块化**：与平台无关的 `core`，加上 NeoForge `adapter`。

## 快速开始

```sh
./gradlew build
cp build/libs/serverscope-1.0.0-all.jar path/to/server/mods/
```

在 `http://127.0.0.1:4318` 运行一个带 `otlp` receiver 的 OpenTelemetry Collector。启动服务器。遥测在 `ServerStartingEvent` 开始，collector 就会收到 span 与 metric。

## 使用方式

| 层级 | 信号 | 事件 |
|------|------|------|
| 离散、低频 | span | 服务器生命周期、非正常重启、玩家加入、离开、重生与换维度、聊天、指令、成就、死亡 |
| 高频 | counter | 方块破坏与放置、生物生成与死亡、伤害、互动、原版统计 |
| 每秒采样 | gauge | TPS、MSPT、在线玩家、已加载实体、已加载 chunk、JVM heap、GC 次数与时间 |

高基数的值(玩家名称、坐标、崩溃原因、聊天与指令内容)只放在 span 上。counter 与 gauge 的标签保持低基数：事件名称加上有限的 `type`。

## 配置

| 需要 | 用途 |
|------|------|
| JDK 21 | 构建 |
| Minecraft 1.21.1 的 NeoForge 21.1.x 服务器 | 运行 mod |
| 位于 `127.0.0.1:4318` 的 OTLP/HTTP receiver | 接收数据 |

endpoint 是 `core/Telemetry.java` 里的常量 `base`。要改就编辑常量并重新构建。

## 工作原理

- 在 `ServerStartingEvent`，adapter 读取上次启动留下的 `serverscope-state.txt`。如果内容还是 `running`，说明上次没有正常关机(watchdog 卡死、内存不足或 `SIGKILL`)。mod 就发出 `server.unclean_restart`，附上 `crash-reports/` 里最新文件的 `Description:` 那一行。
- 传输用 JDK HTTP sender，不用 okhttp。okhttp 会带进 `kotlin-stdlib`，在 NeoForge 的模块系统上会和 Kotlin mod 冲突。
- Shadow 通过 `shadowMe` 配置只打包 OpenTelemetry 与它的依赖，从不打包 Minecraft 或 NeoForge。

## 限制

- 没有预构建版本，每次安装都要从源码构建。
- endpoint 写在代码里，不在配置文件。

## 其他

给 AI agent 的结构化信息与开发说明在[英文版](README.md#for-ai-agents)。

## 许可证

[Apache-2.0](LICENSE)
