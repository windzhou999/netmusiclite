package com.ncm.watch

import android.app.Application
import androidx.profileinstaller.ProfileInstaller
import java.util.concurrent.Executors

/**
 * 应用入口：**尽早触发 baseline profile 安装**，让侧载安装也能快速变流畅。
 *
 * ## 背景（2026-10-01 在 OWW261 上实测）
 *
 * 侧载安装的 APK，系统 `pm.dexopt.install = speed-profile`，但**安装那一刻系统 profile 目录
 * 是空的** ⇒ dexopt 退化成「几乎不编译」，首次运行全部走 JIT / 解释执行。同一台表冷启动
 * 10 秒的 `dumpsys gfxinfo` 对比：
 * ```
 *   verify（未编译）      janky 52.5%   50th 17ms  90th 36ms  99th 117ms
 *   speed-profile（引导） janky 19.6%   50th 12ms  90th 21ms  99th  48ms
 *   speed（全量 AOT）     janky 20.8%   50th 11ms  90th 22ms  99th  34ms
 * ```
 * 关键结论：**speed-profile 已经几乎等于全量 AOT**。所以「侧载没有 AOT 会卡」这个问题的
 * 正解不是去强行 AOT，而是**让 profile 尽早进到系统目录**，系统随后会用
 * `REASON_PROFILE_INSTALLED` 立即安排一次编译（不必等设备空闲）。
 *
 * ## 这个类做什么
 *
 * androidx.profileinstaller 自带的 `ProfileInstallerInitializer` 已经能做这件事，但它排在
 * `androidx.startup` 队列里、并额外延迟数秒才执行。这里在 `Application.onCreate` 里**立即**
 * 再触发一次 —— 接口是幂等的（已装过会直接跳过），重复调用没有任何副作用，
 * 只是把「装完到变流畅」的窗口期尽量压短。
 *
 * ## 为什么要显式引用
 *
 * R8 会把「没有任何引用」的类整个裁掉。项目原本只是通过 manifest merger 从 AAR 里合并了
 * `ProfileInstallerInitializer` 的 provider 声明，dex 里虽然还有类，但**这是靠 AAR 的
 * 组件声明兜住的**；显式引用后这条链路不再依赖合并结果，更稳。
 */
class WmusicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 幂等 + 只做后台 IO，失败不影响启动（profile 缺失最多只是首次稍慢）。
        //
        // ⚠ 必须用**三参数**重载：单参数的 writeProfile(Context) 内部用 Runnable::run
        //   （同步执行），在 onCreate 里会阻塞主线程。
        // ⚠ DiagnosticsCallback 有**两个**抽象方法（onDiagnosticReceived / onResultReceived），
        //   **不是 SAM 接口** ⇒ lambda 无法转换，只能写 object 表达式。
        //   （签名已用 javap 核准：public static void writeProfile(Context, Executor, DiagnosticsCallback)）
        runCatching {
            ProfileInstaller.writeProfile(
                this,
                Executors.newSingleThreadExecutor(),
                object : ProfileInstaller.DiagnosticsCallback {
                    override fun onDiagnosticReceived(code: Int, data: Any?) = Unit
                    override fun onResultReceived(code: Int, data: Any?) = Unit
                },
            )
        }
    }
}
