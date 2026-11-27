# 实现摘要（Android 工程师）

## 落地范围

- **工程基线**：Gradle 8.7 Wrapper（jar/properties/gradlew 已入库）+ Version Catalog；AGP 8.5.2 / Kotlin 2.4.20 / Compose Compiler 2.4.20 / Compose BOM 2024.09.03 / Navigation 2.8.3 / DataStore 1.1.1 / Kadb 1.3.0 / TFLite 2.16.1。namespace 与 applicationId 均为 `com.aixiaohuadb`，minSdk 29 / targetSdk 35 / compileSdk 35，Java 17 toolchain。
- **flavor**：dev / gray / release；`BuildConfig.DEBUG_PAGE_VISIBLE` 仅 dev 为 true，用于隐藏调试页入口与路由。
- **分层**：`config → inference(api/demo/tflite) → audio → machine → domain → exec → service → ui`，手写 `AppContainer` 依赖容器，无 Hilt/Koin。
- **内嵌 ADB**：`adb/` 包直接实现 ADB 客户端核心；`ui/adb/` 承载连接引导；项目不再包含任何外部辅助应用的依赖、权限、 provider、AIDL 或包名查询。

## 核心实现

1. **可插拔推理**：`InferenceEngine` / `WakeDetector` / `CommandClassifier` / `ControlClassifier` / `SpeakerVerifier` / `InferenceFactory` + `DelegateChain`。`Demo*` 全部可运行且分数确定性；`Tflite*` 从 `assets/models/manifest.json` 读张量规格，按 GPU → NNAPI → CPU try/catch 降级，模型缺失时 `loadAll()` 返回明确错误，`AppContainer` 自动回落 Demo。
2. **状态机**：`VoiceStateMachine` 覆盖 10 态 + UNLOAD_WAKE 过渡态，实现完整迁移表、C1 真退出（UnloadModels + StopCapture + 仅 ManualResume 可恢复 + 首次二次确认）、C2/C3/C4、ERROR 连续 3 次真退出、30s 暂停超时与 3s 指令窗自动超时；UiStateMapping 逐字映射原型 states 的 chip/title/desc/bottom/notif 文案。
3. **执行层**：`CommandLibrary` 完整落地 COORDS（6+6+7）与 CMD_LIST（14 指令 + 切换位置），`CoordinateMapper` 按 1080×2340 换算，`AccessibilityExecutor` 派发手势点击；备用链路为 `AdbInputExecutor`：强类型 `InputCommand` 经 `CommandValidator` 校验后由 `AdbClient` 调用 Kadb shell。执行链按 A11y → 内嵌 ADB → 只读提示降级；无字符串命令入口、不注入/Hook；某执行器已派发过点击后失败则禁止降级重放，避免重复操作。
4. **ADB 客户端**：`AdbClient` 封装 Kadb 的 `pair/create/shell/connectionCheck`，证书与主机私钥持久化在 `filesDir/adb_host_cert.der` 和 `filesDir/adb_host_key.der`。配对页只收集配对端口与 6 位配对码，不保存配对码；连接时使用 `echo __adb_ok__` 验证 shell。`AdbNetwork` 取活动网络 `LinkProperties` 的 IPv4 作为地址默认值，失败时遍历网卡、最后降级 `127.0.0.1`；`AdbErrors` 把 refused/timeout 转为可操作提示（端口每次开关无线调试都会变），并把 TLS 握手的 `SSLV3_ALERT_CERTIFICATE_UNKNOWN` / `AdbPairAuthException("Pairing required or trust missing")` 映射为“重新配对 / 区分配对端口与连接端口”的指引（配对路径 raw SSL 异常不经 TlsErrorMapper，失败 Result 也返回友好消息而非 BoringSSL 原文）。状态分为 NOT_READY / DISCONNECTED / PAIRED / CONNECTED / ERROR / UNSUPPORTED；`AdbStatus` 把「当前连接端点」与「已配对端点」分离，连接失败会清理 `kadb` 与端点，`execute()` 必须 shell 探活通过才执行，杜绝“显示已连接但自检报未连接”的矛盾。
5. **数据层**：Preferences DataStore 持久化开关、模式、灵敏度、delegate、阈值、声纹与反馈；`LabelBook` 完整定义 6 唤醒标签、15 指令标签、ACTION×OBJECT、C0–C4；assets 内置 manifest.json / labels.json。
6. **服务**：`VoiceForegroundService`（常驻通知 + 暂停/退出/恢复 action）、`VoiceAccessibilityService`（手势点击 + XML 配置）、`FloatingBallService`（悬浮窗拖拽/单击/双击/长按，无权限自动不可用）。
7. **UI**：15 个导航项全部实现（9 screens，home 参数化 7 态）；7 态深色状态卡含呼吸/脉冲/波形/倒计时动画并遵循 reduce-motion；底部 TabBar、通知栏覆盖层、C1 退出确认框、声纹删除确认框；颜色 token 与原型 CSS 一一对应。
8. **测试**：`VoiceStateMachineTest`、`CoordinateMapperTest`、`LabelBookTest`、`FeatureWindowTest`、`ExecutorChainFallbackTest`、`CommandValidatorTest`、`AdbInputExecutorTest`、`AdbNetworkTest`、`AdbErrorsTest`、`AdbStatusTest`（10 个测试类 / 47 个用例，全部通过）；CI 配置 `testDevDebugUnitTest`。
9. **CI/文档**：`.github/workflows/build.yml` 任意 push 触发，Temurin JDK 17 + setup-android + assembleDevDebug + testDevDebugUnitTest，上传带 commit sha 的 APK 并发布 prerelease Release；`README.md` 含构建、模型替换、内嵌 ADB 连接引导与安全声明。

## 文件统计

- Kotlin 源文件与工程文件以工作区实际状态为准；旧的外部辅助执行链路相关源码、AIDL、依赖与文档描述已删除。

## 构建命令

```bash
./gradlew :app:assembleDevDebug
./gradlew :app:testDevDebugUnitTest
```

## 已知风险点

- CI 需能下载 Gradle 8.7 发行版与 Maven 依赖；若网络受限，构建会在依赖解析阶段失败。
- TFLite GPU delegate 在部分机型不兼容，已在 `TfliteEngine` 内 try/catch 降级；实际生效档位需真机调试页确认。
- `owner.spk` 真实加密格式未定，当前按自定义 float32 头格式读取；替换加密容器时需同步调整 `TfliteSpeakerVerifier.loadTemplateIfExists()`。
- 无线 ADB 配对要求目标设备 Android 11+ 且系统提供 TLS 1.3/TLS exporter；本工程 minSdk 29，配对能力按真机环境实时判断，不支持时页面显示 UNSUPPORTED。
- 命令下发使用 `input tap/swipe/keyevent`，不同 ROM 的设备端实现可能有差异；页内自检只验证 shell 可用，完整点击序列需在目标设备实测。
