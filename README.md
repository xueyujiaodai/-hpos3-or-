# LspSidebarSwitch — 侧边栏场景快速切换（LSPosed 模块）

让手机管家（com.miui.securitycenter）侧边工具箱的**视频场景 / 游戏场景**一键互切。
典型场景：某个应用现在是"视频场景"（呼出视频工具箱），一键把它切到"游戏场景"（呼出游戏工具箱）。

**切换顺序严格遵循系统的要求：先取消当前场景，再进入目标场景**（先取消视频场景 → 再切换到游戏场景）。

---

## 一、机制分析（基于 手机管家 12.1.3-251205.0.1 / HyperOS 3，已反编译验证）

### 1. 场景由"应用名单 + 前台应用"共同决定

| 场景 | 应用名单存放位置 | 名单键 / URI | 总开关 | 工具箱窗口控制器 |
|---|---|---|---|---|
| 视频场景 | securitycenter 内部偏好（remoteprovider） | `pref_video_box_app_list`（删除名单 `pref_video_box_app_del_list`） | `Settings.Secure.pref_videobox_switch_status` | `I3.w`（`m()` 进入 / `n()` 退出） |
| 游戏场景 | ContentProvider | `content://com.miui.securitycenter.gamebooster/gamebooster`（`flag_white=0` 行） | `Settings.Secure.pref_open_game_booster` | `com.miui.gamebooster.service.I`（`e0()` 进入 / `g0()` 退出） |

- 前台应用落在**视频名单** → `VideoToolBoxService` 拉起视频工具箱；
- 前台应用落在**游戏名单** → `GameBoosterService` 拉起游戏工具箱；
- 名单变更后，系统通过**进程内广播** `gb.action.update_video_list` / `gb.action.update_game_list` 通知服务重载（发送器是混淆类 `P.a`，即包 `P` 下的类 `a`，实为本地广播封装）。

### 2. 关键类与方法（本版本混淆名，已验证）

```
VideoToolBoxService.onForegroundInfoChanged()     视频场景判定（名单= q4.d.C() 读 pref_video_box_app_list）
  ├─ 进入视频场景: I3.w.e(ctx).m()   （startVideoBox）
  └─ 退出视频场景: I3.w.e(ctx).n()   （exitVideoBox）

GameBoosterService.onForegroundInfoChanged()      游戏场景判定（名单= N.h() 查 gamebooster provider）
  ├─ 进入游戏模式: I.B(ctx).R(pkg); a0(uid); e0() → f0()  （startGameMode, gb_boosting=1）
  └─ 退出游戏模式: I.B(ctx).g0()                    （stopGameMode, gb_boosting=0）

名单读写：
  q4.d.C()/F0()   ↔ pref_video_box_app_list（remoteprovider, TYPE=5 StringArrayList）
  q4.d.B()/E0()   ↔ pref_video_box_app_del_list
  N.g(ctx,label,pkg,uid,0)  游戏名单 insert（flag_white=0）
  N.c(ctx,pkg,uid,false,0)  游戏名单 delete（URI .../gamebooster/1）
  N.v(pkg,true/false)       pref_manually_delete_game_list（防云同步回填）

进程内广播：P.a.b(ctx).d(intent)  →  "gb.action.update_video_list" / "gb.action.update_game_list"
```

### 3. 为什么必须先"取消视频场景"再"切游戏场景"

视频工具箱与游戏工具箱**共用同一个 Dock 窗口体系**，且各自维护独立的 boosting 标志
（`vtb_boosting` / `gb_boosting`，`Settings.Secure`）。不先退出视频场景就直接加游戏名单，
会造成：
- 视频工具箱窗口仍挂在屏幕上，与游戏工具箱窗口冲突；
- `pref_video_booster_status` 仍为 true，下次前台变化会再次拉起视频工具箱。

所以正确顺序是：**退出视频工具箱（`I3.w.n()`）→ 从视频名单移除 → 刷新 → 写入游戏名单 → 刷新 → 拉起游戏工具箱（`I.B.e0()`）**。
本模块的 `SceneSwitcher.switchToGameScene()` 严格按此顺序执行。

---

## 二、工程结构

```
LspSidebarSwitch/
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle              # compileSdk 35, minSdk 28
    ├── libs/                     # ← 放入 LSPosed api-93.jar（见下）
    └── src/main/
        ├── AndroidManifest.xml   # 仅声明应用名（无组件）
        ├── assets/xposed_init    # com.lsp.sidebar.scene.XposedEntry
        └── java/com/lsp/sidebar/scene/
            ├── XposedEntry.java      # 模块入口：hook 服务 onCreate，仅在 remote 进程初始化
            ├── Module.java           # 初始化：注册触发广播 + 挂悬浮按钮
            ├── SceneSwitcher.java    # 核心：先取消当前场景 → 再切目标场景
            ├── ForegroundHelper.java # 当前前台 pkg/uid（E.e()，含 uid 归一化 L0.o）
            ├── LocalBc.java          # 进程内广播发送（P.a → androidx 回退）
            ├── SwitchReceiver.java   # 广播触发入口（com.lsp.sidebar.scene.SWITCH）
            ├── FloatingSwitchView.java # 可拖拽悬浮切换按钮
            └── Prefs.java            # 模块配置
```

## 三、编译

1. 从 [LSPosed releases](https://github.com/LSPosed/LSPosed/releases) 下载 `api-93.jar`（或对应版本），
   放到 `app/libs/` 目录；
2. Android Studio 打开工程直接 Build，或命令行：
   ```bash
   ./gradlew assembleRelease
   ```
   产物：`app/build/outputs/apk/release/app-release.apk`

## 四、安装与启用

1. 手机安装并激活 LSPosed（需 root / KernelSU 环境）；
2. 安装模块 APK；
3. LSPosed 中勾选模块，作用域勾选 **手机管家（com.miui.securitycenter）**；
4. 重启或强制停止手机管家，让模块注入 remote 子进程。

## 五、使用

**方式 A：悬浮按钮（默认开启）**
屏幕右侧出现半透明圆形 `⇄` 按钮，可长按拖动。点击即对**当前前台应用**执行切换：
- 应用在视频场景 → 切成游戏场景；
- 应用在游戏场景 → 切成视频场景；
- 都不在 → 推入游戏场景（即"让任意应用进入游戏工具箱"）。

**方式 B：广播触发（可配 Tasker / 快捷方式 / 其他 App）**
```bash
# 切换当前前台应用
adb shell am broadcast -a com.lsp.sidebar.scene.SWITCH

# 指定包名
adb shell am broadcast -a com.lsp.sidebar.scene.SWITCH --es pkg com.tencent.tmgp.sgame
```

**方式 C：直接调用核心 API（供二次开发）**
`SceneSwitcher.switchToGameScene(ctx, pkg, uid)` / `switchToVideoScene(ctx, pkg, uid)`。

## 六、配置项（Prefs，默认值）

| 键 | 默认 | 说明 |
|---|---|---|
| `enable_floating` | true | 是否显示悬浮按钮 |
| `auto_enable_game_booster` | true | 切到游戏场景时自动打开"游戏工具箱"总开关 |
| `btn_x / btn_y` | 屏幕右侧 | 悬浮按钮位置（拖动自动记忆） |

## 七、适配与风险

- 类名（`I3.w`、`com.miui.gamebooster.service.I`、`P.a` 等）为**当前 APK 版本的混淆名**；
  手机管家升级后若混淆名变化，需重新反编译核对并更新 `SceneSwitcher` 中的常量；
- 名单读写走的是系统自己的 ContentProvider / remoteprovider 通道，与设置页操作完全一致，不破坏系统数据；
- 需要系统权限写入 `Settings.Secure`（`gb_boosting` 等），模块注入的是系统应用进程，具备该权限；
- 切换属于"临时把应用挪进另一场景"，下次系统云同步/默认名单逻辑可能再次调整名单；
  模块已同步维护 `pref_video_box_app_del_list` 与 `pref_manually_delete_game_list` 以尽量保持状态。

## 八、日志

```bash
adb logcat -s LspSidebarSwitch LspSidebarSwitch.Scene LspSidebarSwitch.LocalBc
```
