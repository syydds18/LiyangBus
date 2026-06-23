<div align="center">

# 🚌 溧阳公交

**一款简洁实用的溧阳市实时公交查询 Android 应用**

[![Android](https://img.shields.io/badge/Android-8.0%2B-green?logo=android)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue?logo=kotlin)](https://kotlinlang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## ✨ 功能特性

| 功能 | 说明 |
|------|------|
| 🔍 **实时公交查询** | 查看公交线路实时到站信息，车辆位置、车厢拥挤度一目了然 |
| 🚏 **站点搜索** | 支持按站点名称或线路编号模糊搜索，300ms 防抖即时响应 |
| 📍 **附近站点** | 基于 GPS 定位自动推荐周边公交站点 |
| ⏰ **到站提醒** | 设置悬浮窗提醒，车辆即将到站时弹出通知，不错过班车 |
| 🔄 **换乘查询** | 智能规划换乘方案，支持最多两次换乘 |
| 📅 **时刻表** | 查看各线路首末班时间、发车间隔及票价信息 |
| ⭐ **收藏夹** | 收藏常用线路，主页卡片实时显示车辆位置，支持排序和默认方向设置 |
| 🏛️ **枢纽站标识** | 搜索结果中自动标注换乘枢纽站，方便规划出行 |
| 🚗 **车牌号查询** | 实时显示运营车辆的车牌号信息 |
| 🌙 **深色模式** | 完整适配系统深色主题 |
| 🗺️ **地图模式** | 在地图上查看线路站点和车辆位置 |

## 📸 应用截图

<div align="center">

| 主页 | 线路详情 | 换乘查询 | 到站提醒 |
|:---:|:---:|:---:|:---:|
| ![主页](screenshots/home.png) | ![线路详情](screenshots/detail.png) | ![换乘查询](screenshots/transfer.png) | ![到站提醒](screenshots/alert.png) |

</div>

> 📌 截图待补充，欢迎提交 PR

## 🛠️ 技术栈

- **语言** — Kotlin
- **最低版本** — Android 8.0 (API 26)
- **架构** — Activity + Coroutines + Lifecycle
- **网络** — OkHttp 4.x（协程挂起封装）
- **UI** — Material Design 3 + RecyclerView + SwipeRefreshLayout
- **构建** — Gradle (Kotlin DSL) + View Binding

## 📡 数据来源

本应用的公交数据来自 **溧阳行** 公共交通信息平台：

> 🔗 [https://www.ly-xing.com](https://www.ly-xing.com)

所有线路、站点、实时位置等数据均由该平台提供接口服务。

## 📥 下载安装

### 方式一：下载 APK

前往 [Releases](https://github.com/syydds18/LiyangBus/releases) 页面下载最新版本 APK 安装包。

### 方式二：源码编译

```bash
# 克隆仓库
git clone https://github.com/syydds18/LiyangBus.git
cd LiyangBus

# 使用 Android Studio 打开项目，或通过命令行构建
./gradlew assembleDebug

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

**环境要求：** Android Studio Hedgehog (2023.1) 或更高版本，JDK 17

## 📋 版本信息

- **当前版本：** v1.8.1
- **包名：** `com.liyang.bus`
- **目标 SDK：** 34 (Android 14)

## 🤝 参与贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/your-feature`)
3. 提交更改 (`git commit -m 'Add your feature'`)
4. 推送到分支 (`git push origin feature/your-feature`)
5. 提交 Pull Request

## 📄 开源许可

本项目基于 [MIT License](LICENSE) 开源。

```
MIT License

Copyright (c) 2025 LiyangBus

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
