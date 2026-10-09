<div align="center">

# otoDone
**一款以 FreeRDP 为内核的 Android 远程桌面（RDP）客户端**

[![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-red.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose-BOM%202024.12.01-green.svg)](https://developer.android.com/jetpack/compose)
[![Android](https://img.shields.io/badge/Android-26%2B%20(arm64--v8a)-blue.svg)](https://www.android.com/)
[![FreeRDP](https://img.shields.io/badge/FreeRDP-3.x-orange.svg)](https://www.freerdp.com/)
[![Status](https://img.shields.io/badge/Status-Active-brightgreen.svg)]()

</div>

## 总览

otoDone 是一个面向手机的 RDP 远程桌面客户端：用 Kotlin + Jetpack Compose + Material 3 重写界面，底层通过 vendored 的 **FreeRDP 3.x** 原生库（arm64-v8a）连接 Windows / Linux 远端，把远端画面直接绘制到 Android 的 `SurfaceView` 上，并回传触摸、鼠标、键盘与剪贴板事件。

设计取向是**沉浸、低干扰**：会话页不设应用栏，整屏铺满远端画面；连接/失败信息用一张不透明卡片 + 一行文案浮在中央；自动重连采取保守策略，宁可停手让用户点「重试」，也不高频重建 native 会话。

## 目录结构

| 目录 | 角色 | 内容 |
|------|------|------|
| `aosp-app/` | **应用代码**（独立 Git 仓库，唯一代码源） | Kotlin + Compose + Material3；含 vendored FreeRDP 的 Java/JNI 桥（`com.freerdpcore`）与 arm64 `jniLibs` |
| `main/` | **仓库入口文档** | 本 README，项目总览与技术说明 |
| `_guide/` | **非代码资料** | 各阶段验证报告与截图（`verify-*`）、agent 指南；不进构建、不参与发布 |
| `aosp-app/third_party/` | **原生库源码与构建** | FreeRDP 3.x 源码（421M）+ arm64 交叉编译脚本与产物（约 400M）；Gradle 构建**不依赖**它，删除后只是失去重新交叉编译 native 的能力，已编译好的 `.so` 已内置在 `aosp-app/app/src/main/jniLibs/` |

> 根目录保持「代码（aosp-app）/ 文档（main）/ 资料（_guide）/ 原生（third_party）」四块，验证截图等非代码数据一律放 `_guide/`。

## 功能特性

### 设备管理
- 电脑列表（名称、地址、端口、用户名、域、密码），进入会话、编辑、删除。
- 数据使用 **DataStore Preferences** 持久化（`files/datastore/rdp_hosts`）。

### RDP 会话
| 能力 | 说明 |
|------|------|
| 全屏沉浸 | 隐藏系统栏，返回手势退出（连按两次确认） |
| 异形屏 | 画面延伸到刘海/挖孔区域（`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`） |
| 屏幕方向 | 自适应 / 强制横屏 / 强制竖屏，退出时还原 |
| 显示与手势 | fit-center 渲染；双指缩放、双指滚动、缩放后单指平移 |
| 鼠标 | 单指点击/拖动；左下「粘性右键」浮标：点一下进入右键待发，再点画面发右键并自动解除 |
| 键盘 | 右下键盘浮标拉起软键盘；物理键盘与 IME 提交均支持 |
| 中文输入 | 非 ASCII 文本走「写系统剪贴板 → cliprdr 推送 → 收到 `OnClipboardDataSent` 后发粘贴快捷键」，避免逐字符丢失 |
| 连接浮层 | **不透明**卡片 + 自绘 Material 3 Expressive 风格「变形多边形」加载动画 + 一行文案；失败态给「重试」按钮 |
| 自动重连 | 保守策略：失败后首次约 1.5s 重试，之后间隔 ≥5s，连续 3 次后停手（详见「会话生命周期」） |

### 设置
- 主题：跟随系统 / 浅色 / 深色；动态取色（Android 12+ 莫奈取色）。
- 粘贴快捷键：`Ctrl + Shift + V`（Linux 终端，默认）/ `Ctrl + V`（Windows）/ `Shift + Insert`（经典 X11）。
- 屏幕方向：自适应 / 横屏 / 竖屏（进入会话时生效）。
- 清除缓存。

### 关于
版本、构建版本、目标 API、开发者信息、项目主页、开源许可与隐私入口。

## 技术栈

| 层 | 选型 | 版本 |
|----|------|------|
| 语言 / 构建 | Kotlin / AGP / Gradle | Kotlin 2.0.21 / AGP 8.9.1 |
| UI | Jetpack Compose + Material 3 | Compose BOM `2024.12.01`（material3 1.3.x） |
| 导航 | androidx.navigation-compose | 2.8.5 |
| 存储 | androidx.datastore-preferences | 1.1.1 |
| 异步 | kotlinx-coroutines-android | 1.9.0 |
| 生命周期 | lifecycle-runtime/viewmodel-compose | 2.8.7 |
| Android | minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| 原生 | FreeRDP + OpenSSL + OpenH264 + uriparser + cJSON | FreeRDP 3.x（源码 `b2a1214`），NDK `29.0.13113456`，arm64-v8a，API 26 |

应用版本：`1.26.10.Alpha1`（versionCode `2610060`），包名 `com.otodone.app`。

## 架构

`@
┌──────────────────────────── MainActivity (Compose) ────────────────────────────┐
│  OtoDoneTheme ── NavHost                                                       │
│    hosts ──► HostListScreen ──► session/{hostId} ──► SessionScreen             │
│    settings / about                                                            │
└───────────────┬────────────────────────────────────────────────────────────────┘
                │ viewModel()
                ▼
   RdpSessionViewModel ── ReconnectPolicy（纯逻辑，可单测）
                │  state / bitmap / frameTick（StateFlow）
                ▼
   RdpSession ──► GlobalApp ──► LibFreeRDP (JNI)
                │                   │
                │                   ▼
                │        libfreerdp-android.so（arm64）── 远端 RDP
                ▼
   RdpSurfaceView（AndroidView，绘制 Bitmap + 采集触摸/键盘）
`@

## 关键模块

| 模块 | 路径 | 职责 |
|------|------|------|
| 入口 / 导航 | `aosp-app/app/src/main/java/com/otodone/app/MainActivity.kt` | 主题、NavHost、启动遮罩 |
| 会话页 | `.../ui/session/SessionScreen.kt` | 沉浸/异形屏/方向、画面承载、浮层与自绘加载动画、右键浮标 |
| 会话 ViewModel | `.../ui/session/RdpSessionViewModel.kt` | 状态订阅、失败事件驱动的保守自动重连、手动重试 |
| 重连策略 | `.../ui/session/ReconnectPolicy.kt` | 纯逻辑退避（1.5s / ≥5s / 最多 3 次），JVM 单测覆盖 |
| 会话核心 | `.../rdp/RdpSession.kt` | 连接生命周期串行化、native 实例登记/释放、按实例转发 native 回调 |
| 画面 View | `.../rdp/RdpSurfaceView.kt` | Bitmap 绘制、坐标映射、缩放/滚动/平移、IME 输入 |
| FreeRDP 桥 | `.../com/freerdpcore/services/LibFreeRDP.java` | JNI 封装；实例存活登记与线程安全的 `freeInstance` |
| 会话注册表 | `.../com/freerdpcore/application/GlobalApp.java` | 实例 → SessionState 映射、连接事件主线程派发 |
| 主题 | `.../ui/theme/Theme.kt` | 浅/深色方案、动态取色 |

## 会话生命周期与重连安全

自动重连由「失败是事件」驱动：`RdpSession.state` 变成 `Failed` / `Disconnected` 才排一次重连；回前台**不**按时间阈值主动重建。退避常量：

| 常量 | 取值 | 含义 |
|------|------|------|
| `AUTO_RETRY_DELAY_MS` | 1500ms | 首次失败后的重连延迟 |
| `MIN_RECONNECT_INTERVAL_MS` | 5000ms | 两次重连发起之间的最小间隔 |
| `MAX_AUTO_RETRIES` | 3 | 连续自动重试上限，达到后停手，只留「重试」；手动重试清零 |

native 侧的安全性（避免重连时 use-after-free）：

1. **连接前登记**：`LibFreeRDP.connect` 在启动 native 连接线程之前就把实例登记为「存活」，因此 `freeInstance` 任何阶段都会先 `freerdp_disconnect` 并阻塞等待连接线程报告终态，之后才 `freerdp_free`；超时（15s）选择泄漏而不 free。
2. **标记作废 + 串行释放**：`RdpSession.reconnect` 先清空当前实例、按实例号丢弃旧回调，再把旧实例交给去重 + 串行的释放队列。
3. **按实例转发回调**：每个 native 实例一个转发器，实例号不匹配即丢弃，杜绝旧线程回调套到新实例（例如对尚未 `gdi_init` 的新 context 调 `updateGraphics`）。

## 构建与运行

### Android 应用
`@bash
cd aosp-app
./gradlew :app:assembleDebug            # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
`@

- 调试签名复用 `aosp-app/keystore/keystore.jks`（配置见 `keystore.properties`）。
- SDK 路径写在 `aosp-app/local.properties`。

### 单元测试
`@bash
cd aosp-app
./gradlew :app:testDebugUnitTest        # ReconnectPolicy 退避/上限/清零
`@

仪器测试（多点触控按键状态机）在 `app/src/androidTest/`。

### 原生库（arm64-v8a）
Gradle 构建直接使用已内置的 `aosp-app/app/src/main/jniLibs/arm64-v8a/*.so`，**不需要** `aosp-app/third_party/`。需要重新交叉编译时：

`@bash
third_party/build-freerdp-android.sh
`@

脚本会从 Gitee 镜像拉取并 vendored OpenSSL 4.0.1 / uriparser 1.0.2 / cJSON v1.7.19 / OpenH264 v2.6.0，用 NDK 编译 FreeRDP 3.x，最后把 `.so` 同步进 `jniLibs/`。

## 文档索引

| 主题 | 位置 |
|------|------|
| 会话浮层重做 + 闪退/重连修复报告 | `_guide/verify-session-overlay/REPORT.md` |
| 自动重连修复 + 剪贴板调研 | `_guide/verify-reconnect/REPORT.md` |
| 设置/方向/浮标验证截图 | `_guide/verify-feature-session/` |
| 多点触控按键验证截图 | `_guide/verify-multitouch/` |
| 系统栏/异形屏 insets 验证 | `_guide/verify-insets/` |

## 已知限制 / 待办

- 密码目前**明文**存于 DataStore（仅打通链路），计划迁移 Keystore 或改为连接时输入。
- 设置项 `theme_oled_black` 已存储但主题侧 `withOledBlack()` 暂未接线，纯黑背景当前不会生效。
- 原生库只提供 **arm64-v8a**；x86_64 模拟器进入会话会报 `UnsatisfiedLinkError: libfreerdp-android.so not found`（预期，非缺陷）。
- 中文输入依赖远端剪贴板通道；若远端不接受 unicode 注入，则只能走「系统剪贴板 + 粘贴快捷键」。

## 许可证

- 应用本体：**All Rights Reserved**（仅供学习与研究）。
- 第三方：FreeRDP（Apache-2.0）、OpenSSL、OpenH264、uriparser（MIT）、cJSON（MIT）等，详见「关于 → 开源许可」。

## 更新日志

### v1.26.10.Alpha1

- 会话页加载浮层重做：不透明卡片 + 自绘 Material 3 Expressive 风格「变形多边形」加载动画 + 单行文案。
- 自动重连改保守策略：移除回前台时间阈值，首次 1.5s、间隔 ≥5s、最多 3 次后停手。
- 修复重连期间 native 实例释放与回调的竞态（use-after-free / 跨实例回调），加固连接线程生命周期。
- 全屏沉浸、异形屏、会话方向、粘性右键、双指缩放滚动平移、中文粘贴等能力保持。
