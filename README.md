# xiaoai_cmd

LSPosed 模块 — Hook 小米语音助手（超级小爱），捕获文本输入、语音识别结果和返回内容，通过静音通知显示。

## 功能

| Hook | 说明 | 通知 |
|---|---|---|
| 文本发送 | `onSendClick` / `w` / `V3.onAction` 三条链路 | "文本指令" |
| ASR 语音结果 | `UiManager.onAsrResult` | "语音指令" + "返回结果" |

- 通知渠道默认静音、无悬浮、无振动
- 通知 3 秒后自动清除
- 按通知类型 5 秒去重，避免重复刷屏

## 环境要求

- Android 15+ (API 35+)
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

## Hook 策略

基于 `超级小爱 v7.13.32.0016` 静态分析，采用多策略回退应对混淆变化：

- **文本发送**（三条链路）：
  - `gf0.d0.onSendClick` — 混淆类，当前版本可用
  - `gf0.d0.w` — final 汇聚点，子类不可覆盖，最可靠
  - `InputModuleViewModelV3.onAction` — 未混淆稳定类，终极兜底
- **ASR 结果**：锚定稳定类 `UiManager`（不混淆），优先 getter 方法、回退字段反射
- **Context 获取**：三级回退（对象字段 → `ActivityThread.currentApplication()`）
- **类缓存**：静态 `ConcurrentHashMap` 缓存已解析的 Class/Method，优化冷启动性能

## 技术栈

- [libxposed API 102](https://github.com/libxposed/api) — Modern Xposed API
- [LSPosed](https://github.com/LSPosed/LSPosed) — Xposed 框架
- [mt-mcp](https://github.com/nickchubb/mt-mcp) — APK 逆向分析（用于定位 Hook 点）
- [LSPosed-Mod-Dev.skill](https://github.com/hujiayucc/LSPosed-Mod-Dev.skill) — LSPosed 模块开发 Skill

## 项目结构

```
app/src/main/
├── java/com/ham/xiaoai_cmd/
│   ├── ModuleMain.java          # 入口类（生命周期）
│   └── XiaoAiHookInstaller.java # Hook 安装器（通知 + 反射 + 去重）
├── res/
│   └── mipmap-*/ic_launcher.png # 应用图标
└── resources/META-INF/xposed/
    ├── java_init.list            # 入口类声明
    ├── module.prop               # 模块配置（API 102）
    └── scope.list                # 作用域（com.miui.voiceassist）
```

## License

MIT
