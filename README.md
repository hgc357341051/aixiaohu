# 小胡 · 语音控制（Android 自用测试版）

端侧语音控制原型工程：两级唤醒 + 单人声纹门控 + 控制词 C0–C4 + 14 类换装指令 + 无障碍/内嵌 ADB 双执行链路。
**仅限自有设备与测试账号、学习研究使用，不发布市场、不牟利，风险自担。**

## 功能说明

- **15 个页面/状态**：引导、权限、主页 7 态、设置、声纹、手动面板、无线 ADB 连接、指令映射、调试页（dev flavor），底部 TabBar 与通知栏覆盖层、C1 退出确认、声纹删除确认。
- **可插拔推理**：`InferenceFactory` 统一产出唤醒/指令/控制头/声纹四个引擎；`Demo*` 实现内置可运行，`Tflite*` 实现读取 `assets/models/` 模型，缺文件自动降级 Demo，业务代码零改动。
- **delegate 降级**：AUTO/GPU/NNAPI/CPU，TFLite 侧按 GPU → NNAPI → CPU 逐档 try/catch 降级，设置页可手动切换，调试页显示实际生效档位。
- **状态机 C1–C4**：C1 真退出（卸载模型 + 停麦克风 + 仅手动恢复 + 首次二次确认）；C2 退出识别保留唤醒；C3 暂停 30s 自动恢复；C4 恢复。
- **执行层**：无障碍手势为主、内嵌 ADB 强类型输入为备、最后只读提示；禁止注入/Hook/读内存。坐标按 1080×2340 基准换算。
- **持久化**：Preferences DataStore 保存开关、模式、灵敏度、delegate、声纹开关与反馈；ADB 主机私钥保存在应用私有目录。
- **安全**：C1 后无常驻拾音；`MANUAL_SILENT` 只认 `ManualResume` / `ExitGame`；埋点只记录事件名与模式维度，不记录音频或声纹向量。

## 构建方式

环境：JDK 17 + Android SDK 35 + Gradle Wrapper 8.7（AGP 8.5.2 / Kotlin 2.4.20 / Compose Compiler 2.4.20）。

```bash
./gradlew :app:assembleDevDebug      # 生成 app/build/outputs/apk/dev/debug/app-dev-debug.apk
./gradlew :app:testDevDebugUnitTest  # JVM 单测：状态机 / 坐标 / 词表 / ADB 命令边界
```

flavor：`dev`（调试页可见）、`gray`、`release`（均隐藏调试页）。
CI：任意分支 push 触发 `.github/workflows/build.yml`，上传带 commit sha 的 devDebug APK，并发布 prerelease Release。

## 模型替换说明

1. 将 `wake_l1.tflite`、`wake_l2.tflite`、`cmd.tflite`、`spk.tflite`、`owner.spk` 放入 `app/src/main/assets/models/`。
2. 按真实张量规格修改 `manifest.json`（输入长度、输出维度、dtype），并把 `demo` 改为 `false`。
3. 输出张量顺序约定见各 `Tflite*Classifier.kt` 注释：指令 23 维 = ACTION(3) + OBJECT(14) + CONTROL(5) + REJECT(1)，控制头取第 17–21 维。
4. `owner.spk` 默认格式：`int32 count + int32 dim + count*dim 个 float32`（后续可替换为加密容器）。模板缺失自动走非声纹路径。
5. 若模型加载失败（文件缺失/不兼容/delegate 异常），自动回落到 Demo 引擎，不会崩溃。

## 内嵌 ADB 输入链路

应用使用 `com.flyfishxu:kadb:1.3.0` 直接实现 ADB 客户端协议，**不依赖任何外部辅助应用，也不依赖 PC 上的 adb**。

1. 在目标设备开启「开发者选项 → 无线调试」。
2. 通过「使用配对码配对设备」取得**配对端口**与 6 位配对码，在本应用「设置 → 无线 ADB 连接」中发起配对。
3. 配对成功后回到无线调试主页，读取「IP 地址:端口」中的**连接端口**（与配对端口是两个不同端口；每次开关无线调试或重连 WiFi 后都会变化），填入后点「连接」。连接时用 `echo __adb_ok__` 验证 shell 可用，再点「自检」发送一次 HOME 键确认。
4. 配对/连接地址默认取本机局域网 IPv4（通过 `ConnectivityManager` 的 `LinkProperties` 获取，失败时遍历网卡，最后才降级 `127.0.0.1`），可手动修改。
5. 应用把 ADB 证书与主机私钥持久化在 `filesDir/adb_host_cert.der` 和 `filesDir/adb_host_key.der`，配对成功后跨重启仍可重连；配对码本身不保存。
6. 连接失败会清理连接对象与端点，页面「当前连接端点」只在 shell 探活通过时显示；「已配对端点」单独展示，不代表链路可用。

| 强类型命令 | 下发模板 | 约束 |
|---|---|---|
| `Tap(x, y)` | `input tap x y` | x/y 必须在真实屏幕范围内 |
| `Swipe(..., durationMs)` | `input swipe x1 y1 x2 y2 durationMs` | 时长 0–10000ms |
| `KeyEvent(keyCode)` | `input keyevent keyCode` | 仅 HOME(3) / BACK(4) / ENTER(66) |

安全边界：没有字符串命令入口；命令动词由固定模板决定，参数均为校验后的整数；不提供 `sh -c`、不 Hook、不注入代码、不反射隐藏 API、不读取设备私有数据。执行器一旦真正派发过点击，失败时标记 `attempted=true`，执行链停止降级重放，避免重复点击。

## 安全与合规声明

- 模拟点击可能违反游戏协议导致封号；仅限自有设备、测试账号、学习研究，风险自担。
- 不读内存、不注入、不 Hook、不绕反作弊；不模拟透视/自瞄/走位。
- 退出语音（`C1`）为真实退出：模型卸载、麦克风释放，任何语音都无法恢复；只能通过悬浮球/通知栏/设置页手动恢复。
- 声纹模板与语音数据仅存本机、不上传；删除模板即退回非声纹路径。
