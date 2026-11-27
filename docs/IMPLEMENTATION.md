# 实现摘要（寇豆码 · Android 工程师）

## 落地范围

- **工程基线**：Gradle 8.7 Wrapper（jar/properties/gradlew 已入库）+ Version Catalog；AGP 8.5.2 / Kotlin 2.0.20 / Compose Compiler 2.0.20 / Compose BOM 2024.09.03 / Navigation 2.8.3 / DataStore 1.1.1 / Shizuku 13.1.5 / TFLite 2.16.1。namespace 与 applicationId 均为 `com.example.voicectl`，minSdk 29 / targetSdk 35 / compileSdk 35，Java 17 toolchain。
- **flavor**：dev / gray / release；`BuildConfig.DEBUG_PAGE_VISIBLE` 仅 dev 为 true，用于隐藏调试页入口与路由。
- **分层**：`config → inference(api/demo/tflite) → audio → machine → domain → exec → service → ui`，手写 `AppContainer` 依赖容器，无 Hilt/Koin。

## 核心实现

1. **可插拔推理**：`InferenceEngine` / `WakeDetector` / `CommandClassifier` / `ControlClassifier` / `SpeakerVerifier` / `InferenceFactory` + `DelegateChain`。`Demo*` 全部可运行且分数确定性；`Tflite*` 从 `assets/models/manifest.json` 读张量规格，按 GPU → NNAPI → CPU try/catch 降级，模型缺失时 `loadAll()` 返回明确错误，`AppContainer` 自动回落 Demo。
2. **状态机**：`VoiceStateMachine` 覆盖 10 态 + UNLOAD_WAKE 过渡态，实现完整迁移表、C1 真退出（UnloadModels + StopCapture + 仅 ManualResume 可恢复 + 首次二次确认）、C2/C3/C4、ERROR 连续 3 次真退出、30s 暂停超时与 3s 指令窗自动超时；`UiStateMapping` 逐字映射原型 states 的 chip/title/desc/bottom/notif 文案。
3. **执行层**：`CommandLibrary` 完整落地 COORDS（6+6+7）与 CMD_LIST（14 指令 + 切换位置），`CoordinateMapper` 按 1080×2340 换算，`AccessibilityExecutor` 派发手势点击，备用链路改为 `ShizukuInputExecutor`：`Shizuku.bindUserService()` 绑定运行在 shell 进程的本 App Binder 服务，经强类型 AIDL 调用 `/system/bin/input` 固定子命令；`ExecutorChain` 按 A11y → Shizuku → 只读提示降级。无字符串命令入口、不注入/Hook；某执行器已派发过点击后失败则禁止降级重放，避免重复注入。
4. **数据层**：Preferences DataStore 持久化 all 开关、模式、灵敏度、delegate、阈值、声纹与 Shizuku 曾授权标记；`LabelBook` 完整定义 6 唤醒标签、15 指令标签、ACTION×OBJECT、C0–C4；assets 内置 manifest.json / labels.json。
5. **服务**：`VoiceForegroundService`（常驻通知 + 暂停/退出/恢复 action）、`VoiceAccessibilityService`（手势点击 + XML 配置）、`FloatingBallService`（悬浮窗拖拽/单击/双击/长按，无权限自动不可用）。
6. **UI**：15 个导航项全部实现（9 screens，home 参数化 7 态）；7 态深色状态卡含呼吸/脉冲/波形/倒计时动画并遵循 reduce-motion；底部 TabBar、通知栏覆盖层、C1 退出确认框、声纹删除确认框；颜色 token 与原型 CSS 一一对应。
7. **测试**：`VoiceStateMachineTest`（主流程、C1 真退出与二次确认、关闭确认、C2/C3/C4、连续异常、声纹拒绝/离前台）、`CoordinateMapperTest`、`LabelBookTest`；CI 配置 `testDevDebugUnitTest`。
8. **CI/文档**：`.github/workflows/build.yml` 任意 push 触发，Temurin JDK 17 + setup-android + assembleDevDebug，上传带 commit sha 的 APK；`README.md` 含构建、模型替换、Shizuku 激活引导与安全声明。

## 文件统计

- Kotlin 源文件：99（main 85 + test 14）
- 工程文件总数：124（含 Gradle Wrapper jar 与脚本、AIDL、资源、assets、CI、文档）

## 构建命令

```bash
./gradlew :app:assembleDevDebug
./gradlew :app:testDevDebugUnitTest
```

## 已知风险点

- CI 需能下载 Gradle 8.7 发行版与 Maven 依赖；若网络受限，构建会在依赖解析阶段失败。
- TFLite GPU delegate 在部分机型不兼容，已在 `TfliteEngine` 内 try/catch 降级；实际生效档位需真机调试页确认。
- `owner.spk` 真实加密格式未定，当前按自定义 float32 头格式读取；替换加密容器时需同步调整 `TfliteSpeakerVerifier.loadTemplateIfExists()`。
- Shizuku 激活与 `/system/bin/input` 路径需真机验证：API 34+ 走无线调试引导、29–33 走安装引导；目标机路径缺失时自测区 probe 返回非 0，真实退出码会直接展示。
