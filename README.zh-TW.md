<!-- 翻譯自 README.md @ docs/readme-template-v2。只翻 "For AI agents" 以上的章節，英文版更新時一起改。 -->

<div align="center">

<h1>serverscope</h1>

<p><strong>在玩家告訴你之前，就知道模組 Minecraft 伺服器當了，以及為什麼。</strong></p>

[![Build](https://img.shields.io/github/actions/workflow/status/zyx1121/serverscope/build.yml?branch=main&style=flat-square&label=build)](https://github.com/zyx1121/serverscope/actions)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-DF6E1E?style=flat-square)](gradle.properties)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-OTLP-425CC7?style=flat-square&logo=opentelemetry&logoColor=white)](src/main/java/tw/zyx/serverscope/core/Telemetry.java)
[![License](https://img.shields.io/github/license/zyx1121/serverscope?style=flat-square)](LICENSE)

[English](README.md) | **繁體中文** | [简体中文](README.zh-CN.md)

</div>

小型模組伺服器出問題，第一個徵兆常常是一則聊天訊息：伺服器怎麼這麼卡，或怎麼掛了？serverscope 把每個 tick、加入、死亡、指令與當機都變成 OpenTelemetry trace 與 metric。這樣在有人開口之前，儀表板就先給出答案。它跑在一台真實的 NeoForge 1.21.1「All the Mons」伺服器上。

## 功能

- **OpenTelemetry 訊號**：生命週期、玩家、聊天、指令、成就與死亡事件用 span。高頻事件用 counter，TPS、MSPT、玩家、實體與 chunk 用 gauge。
- **JVM 健康狀態**：從 `java.lang.management` 取得 heap 與 GC 數據，記憶體壓力在 OOM 之前就看得到。
- **非正常重啟警報**：開機時 mod 比對狀態檔與上次執行，發出 `server.unclean_restart`。有當機報告時會附上它的描述。
- **OTLP over HTTP，不用 autoconfigure**：手動建立 SDK provider，沒有 ServiceLoader 的意外。
- **模組化**：與平台無關的 `core`，加上 NeoForge `adapter`。

## 快速開始

```sh
./gradlew build
cp build/libs/serverscope-1.0.0-all.jar path/to/server/mods/
```

在 `http://127.0.0.1:4318` 跑一個帶 `otlp` receiver 的 OpenTelemetry Collector。啟動伺服器。遙測在 `ServerStartingEvent` 開始，collector 就會收到 span 與 metric。

## 使用方式

| 層級 | 訊號 | 事件 |
|------|------|------|
| 離散、低頻 | span | 伺服器生命週期、非正常重啟、玩家加入、離開、重生與換維度、聊天、指令、成就、死亡 |
| 高頻 | counter | 方塊破壞與放置、生物生成與死亡、傷害、互動、原版統計 |
| 每秒取樣 | gauge | TPS、MSPT、線上玩家、已載入實體、已載入 chunk、JVM heap、GC 次數與時間 |

高基數的值(玩家名稱、座標、當機原因、聊天與指令內容)只放在 span 上。counter 與 gauge 的標籤維持低基數：事件名稱加上有限的 `type`。

## 設定

| 需要 | 用途 |
|------|------|
| JDK 21 | 建置 |
| Minecraft 1.21.1 的 NeoForge 21.1.x 伺服器 | 執行 mod |
| 位於 `127.0.0.1:4318` 的 OTLP/HTTP receiver | 接收資料 |

endpoint 是 `core/Telemetry.java` 裡的常數 `base`。要改就編輯常數並重新建置。

## 運作方式

- 在 `ServerStartingEvent`，adapter 讀取上次開機留下的 `serverscope-state.txt`。如果內容還是 `running`，代表上次沒有正常關機(watchdog 卡死、記憶體不足或 `SIGKILL`)。mod 就發出 `server.unclean_restart`，附上 `crash-reports/` 裡最新檔案的 `Description:` 那一行。
- 傳輸用 JDK HTTP sender，不用 okhttp。okhttp 會帶進 `kotlin-stdlib`，在 NeoForge 的模組系統上會和 Kotlin mod 衝突。
- Shadow 透過 `shadowMe` 設定只打包 OpenTelemetry 與它的相依套件，從不打包 Minecraft 或 NeoForge。

## 限制

- 沒有預先建置的版本，每次安裝都要從原始碼建置。
- endpoint 寫在程式碼裡，不在設定檔。

## 其他

給 AI agent 的結構化資訊與開發說明在[英文版](README.md#for-ai-agents)。

## 授權

[Apache-2.0](LICENSE)
