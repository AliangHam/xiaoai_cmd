# xiaoai_cmd

LSPosed 模块 — Hook 小米语音助手（超级小爱），捕获文本输入、语音识别结果和返回内容，通过静音通知显示。

## 功能

| Hook | 说明 | 通知 |
|---|---|---|
| 文本发送 | `onSendClick(String, String)` | "文本指令" |
| ASR 语音结果 | `UiManager.onAsrResult(u20.b)` | "语音指令" + "返回结果" |

- 通知渠道默认静音、无悬浮、无振动
- 通知 3 秒后自动清除

## 环境要求

- Android 8.1+ (API 27+)
- LSPosed (libxposed API 102)
- 目标应用：`com.miui.voiceassist`（超级小爱）

## 构建

```bash
./gradlew assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`

## 安装

1. 安装 APK
2. 在 LSPosed 管理器中启用模块，勾选作用域 `com.miui.voiceassist`
3. 强制停止超级小爱后重新打开
4. 查看日志：`adb logcat -s XiaoAiCmd`

## Hook 策略

基于 `超级小爱 v7.13.32.0016` 静态分析，采用多策略回退应对混淆变化：

- **文本发送**：候选类名列表逐个尝试（`gf0.d0` → `ConversationFragment$d`）
- **ASR 结果**：锚定稳定类 `UiManager`（不混淆），通过反射读取 `u20.b` 字段（`query`/`toDisplay`/`answer`）
- **Context 获取**：三级回退（对象字段 → `ActivityThread.currentApplication()`）

## Hot Reload

支持 API 102 Hot Reload。模块 APK 更新后，已运行的目标进程会自动热重载 Hook，无需强停重启。

## 技术栈

- [libxposed API 102](https://github.com/libxposed/api) — Modern Xposed API
- [LSPosed](https://github.com/LSPosed/LSPosed) — Xposed 框架
- [mt-mcp](https://github.com/nickchubb/mt-mcp) — APK 逆向分析（用于定位 Hook 点）
- [LSPosed-Mod-Dev.skill](https://github.com/hujiayucc/LSPosed-Mod-Dev.skill) — LSPosed 模块开发 Skill

## 项目结构

```
app/src/main/
├── java/com/ham/xiaoai_cmd/
│   ├── ModuleMain.java          # 入口类（生命周期 + Hot Reload）
│   └── XiaoAiHookInstaller.java # Hook 安装器（通知 + 反射）
├── res/
│   └── mipmap-*/ic_launcher.png # 应用图标
└── resources/META-INF/xposed/
    ├── java_init.list            # 入口类声明
    ├── module.prop               # 模块配置（API 102, autoHotReload）
    └── scope.list                # 作用域（com.miui.voiceassist）
```

## License

MIT
