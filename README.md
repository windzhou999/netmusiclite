# WMusic

一块圆屏手表上的网易云音乐第三方客户端。Kotlin + Jetpack Compose 单模块 Android 工程，为 466×466 圆形表盘（Wear OS）深度适配，也可以在普通手机上运行。

> 本项目为非官方第三方客户端，与网易公司无任何隶属或合作关系，仅供个人学习、研究与技术服务商接口协议验证使用。使用前请阅读下方[免责声明](#免责声明)。

## 功能特性

- **播放**：在线播放、歌单/每日推荐/私人漫游、收藏与歌单管理、最近播放与听歌排行、本地音乐扫描、离线下载、播放队列、睡眠定时、歌曲响度均衡（逐曲响度对齐）
- **歌词**：LRC 逐行同步与逐字高亮、翻译与罗马音同显、聚焦行提亮与白色扫光动效（可在设置中开关）、行距回弹、边缘高斯模糊遮挡
- **一起听**：创建/加入听歌房间、房内消息、歌单随机点歌、长按操作、续播
- **社区**：歌曲评论与回复点赞、歌手页（专辑/粉丝/动态）、歌曲百科、热搜搜索、私信与好友
- **主题**：深色/浅色模式、跟随壁纸（Android 12+）或自定义背景图自动取色、Material 3 种子色板、玻璃卡片材质与模糊强度、歌词扫光开关，全部改动即时生效
- **圆屏适配**：表冠滚动、圆形侧边进度环、按压缩放与双向回弹手势、边缘模糊遮挡带、异形屏安全区

## 系统要求

| 项目 | 要求 |
|---|---|
| 最低支持 | Android 8.0（API 26） |
| 目标平台 | API 35，圆形表盘优先适配 |
| 构建工具 | Android Studio（任意近期版本）+ JDK 17 |
| Gradle | 8.10.2（已带 wrapper，无需手动安装） |

## 构建

```bash
# 克隆后直接构建 debug 包
./gradlew assembleDebug

# 或构建 release 包（仓库不含签名文件，会自动回退 debug 签名并给出警告）
./gradlew assembleRelease

# 产物位置
# app/build/outputs/apk/
```

用 Android Studio 打开工程根目录即可导入。若需要正式签名，自行创建 keystore 并在工程根目录提供 `keystore.properties`（含 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项），构建脚本会自动读取。

## 工程结构

```
app/src/main/java/com/ncm/watch/
├── data/        # 播放引擎、网易云接口与加密、会话/缓存/偏好存储
├── ui/
│   ├── components/   # 通用组件（列表页、图标、表冠滚动、环境色背景等）
│   ├── nav/          # 导航与转场
│   ├── screens/      # 各页面（播放/歌词/一起听/评论/设置…）
│   └── theme/        # Material 3 主题、明暗色板、自动取色
└── MainActivity.kt
```

接口层基于公开可得的网络协议实现，加密常量均为社区公开的算法常量；不包含任何账号凭据或用户数据。

## 免责声明

本软件是由音乐爱好者出于个人学习、研究、验证移动端技术与网络协议等非商业目的，利用业余时间独立开发的非官方第三方客户端。本软件基于公开可得的网络接口协议实现，目的在于让用户在智能手表等可穿戴设备上访问其本人已经依法享有的音乐服务权益。本软件并非网易云音乐官方客户端，与网易杭州网络游戏有限公司及网易公司各关联主体（以下统称"权利方"）之间不存在任何隶属、合作、授权、许可、代理、联营或投资关系。开发者亦非权利方的员工、代理人或者受托人。本软件的名称、界面与文档中出现的"网易云""NCM""网易云音乐"等字样与标识，仅为向用户客观描述本软件所适配的服务对象而作描述性使用，相关名称与标识的一切权利均归权利方所有，权利方随时可以要求开发者调整或停止此类使用。

## 开源协议

本项目以 [GPL-3.0](LICENSE) 协议开源。

```
WMusic — Wear OS 网易云音乐第三方客户端
Copyright (C) 2026 昼小风

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

二次分发或修改后的版本必须同样以 GPL-3.0 协议开源。
