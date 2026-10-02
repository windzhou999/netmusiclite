#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 R8 的 mapping.txt 生成 **方法级** baseline profile。

为什么需要它
------------
`pm.dexopt.install` 在 Android 12+ 默认是 `speed-profile`：安装时系统按
APK 里的 `assets/dexopt/baseline.prof` **只 AOT 编译 profile 里列出的方法**。
只写类级条目（`Lcom/foo/Bar;`）时 profile 里一个方法都没有 —— 装完等于没 AOT，
冷启动全靠解释执行 + JIT，就是「没 AOT 好卡」的根因。
（只有 `cmd package compile -m speed -f` 那种全量编译才不看 profile。）

怎么生成
--------
不能拿 debug dex 直接枚举：debug 变体里大量方法在 release 会被 R8 内联掉，
写进 profile 后过不了「输出 dex 校验」被整批丢弃。
正解是用 **release 的 mapping.txt** 反查：它左边是原始类/方法签名（profile 要写这个），
右边是输出名。只在「方法名不带 `.`」时取（带 `.` 的是内联帧，属于别的类，
输出 dex 里没有独立方法体，写进去必然被丢）。

用法
----
    # 先出一次 release（生成 app/build/outputs/mapping/release/mapping.txt）
    gradle :app:assembleRelease
    python tools/gen_baseline_profile.py            # 覆盖写 app/src/main/baseline-prof.txt
    gradle :app:assembleRelease                     # 再出包，profile 就进 APK 了

可选参数：
    --mapping PATH   指定 mapping.txt
    --out PATH       指定输出 profile
    --only-app       只写应用自身的类（profile 最小）
"""

import argparse
import os
import re
import sys

PRIM = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}
CLS_RE = re.compile(r"^(\S+) -> (\S+):$")
# [outStart:outEnd:]<ret> <name>(<args>)[:<srcStart>:<srcEnd>] -> <obf>
METH_RE = re.compile(r"^(?:\d+:\d+:)?([^\s(]+) ([^\s(.]+)\(([^)]*)\)(?::\d+:\d+)? -> \S+$")


def type_desc(t: str) -> str:
    t = t.strip()
    if t.endswith("..."):
        t = t[:-3] + "[]"
    if t.endswith("[]"):
        return "[" + type_desc(t[:-2])
    if t in PRIM:
        return PRIM[t]
    return "L" + t.replace(".", "/") + ";"


def args_desc(args: str) -> str:
    args = args.strip()
    if not args:
        return ""
    return "".join(type_desc(a) for a in args.split(","))


def gen(mapping_path: str, only_app: bool):
    class_entries = set()
    method_entries = set()
    cur = None
    app_methods = 0
    with open(mapping_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("#"):
                continue
            raw = line.rstrip("\n")
            m = CLS_RE.match(raw)
            if m:
                cur = m.group(1)
                continue
            if not (cur and raw.startswith("    ")):
                continue
            if "R8$$REMOVED$$CLASS$$" in cur:
                continue
            d = METH_RE.match(raw.strip())
            if not d:
                continue
            ret, name, args = d.group(1), d.group(2), d.group(3)
            if "." in name:
                # 内联帧：属于别的类，输出 dex 里没有独立方法体 —— 写进去必被校验丢掉
                continue
            is_app = cur.startswith("com.ncm.watch")
            if only_app and not is_app:
                continue
            try:
                # ★ 方法级条目**必须**带 H/S/P 标志位，否则 AGP 的
                #   expandReleaseArtProfileWildcards 直接报
                #   "At least one of flags 'H', 'S', 'P' must be specified for a method rule"。
                #   HSPL = 热方法 + 启动期 + 启动后，三段都算命中（官方 profile 同款写法）。
                method_entries.add(
                    "HSPL%s;->%s(%s)%s"
                    % (cur.replace(".", "/"), name, args_desc(args), type_desc(ret))
                )
            except Exception:
                continue
            if is_app:
                app_methods += 1
    return method_entries, app_methods


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    ap = argparse.ArgumentParser()
    ap.add_argument("--mapping", default=os.path.join(
        root, "app/build/outputs/mapping/release/mapping.txt"))
    ap.add_argument("--out", default=os.path.join(root, "app/src/main/baseline-prof.txt"))
    ap.add_argument("--only-app", action="store_true")
    a = ap.parse_args()

    if not os.path.exists(a.mapping):
        sys.exit("找不到 mapping.txt：%s\n先跑一次 :app:assembleRelease" % a.mapping)

    methods, app_methods = gen(a.mapping, a.only_app)

    # 保留原有类级条目（类预加载/校验前移仍有用），只补方法级
    keep = []
    if os.path.exists(a.out):
        with open(a.out, encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s and not s.startswith("#"):
                    keep.append(s)
    classes = sorted({s for s in keep if "->" not in s})

    header = [
        "# Project baseline profile",
        "# 本文件由 tools/gen_baseline_profile.py 从 release 的 mapping.txt 生成，勿手改。",
        "#",
        "# 组成：",
        "#   1) 类级条目（下面 classes 段）：类预加载 + 校验前移；",
        "#   2) 方法级条目（methods 段）：**安装时 speed-profile 真正 AOT 编译的对象**。",
        "#",
        "# 为什么方法级条目必须从 mapping.txt 反查：debug dex 枚举出的方法在 release 里",
        "# 大量被 R8 内联，过不了输出 dex 校验会被整批丢弃（历史上就是这样退回类级的）。",
        "# mapping.txt 左边是原始签名（profile 写这个，AGP 会按 mapping 重写成混淆名），",
        "# 只取「方法名不带 .」的行 = 输出 dex 里真实存在的独立方法。",
        "#",
        "# 重新生成：gradle :app:assembleRelease && python tools/gen_baseline_profile.py && gradle :app:assembleRelease",
        "",
    ]

    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(header))
        for c in classes:
            f.write(c + "\n")
        for m in sorted(methods):
            f.write(m + "\n")

    print("classes kept : %d" % len(classes))
    print("methods      : %d (app-own %d)" % (len(methods), app_methods))
    print("written to   : %s" % a.out)


if __name__ == "__main__":
    main()
