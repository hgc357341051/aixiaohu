# 小胡 · 语音控制（Android 自用测试版）

端侧语音控制原型工程：两级唤醒 + 单人声纹门控 + 控制词 C0–C4 + 14 类换装指令 + 无障碍/Shizuku 双执行链路。
**仅限自有设备与测试账号、学习研究使用，不发布市场、不牟利，风险自担。**

## 功能说明

- **15 个页面/状态**：引导、权限、主页 7 态、设置、声纹、手动面板、ADB、指令映射、调试页（dev flavor），底部 TabBar 与通知栏覆盖层、C1 退出确认、声纹删除确认。
- **可插拔推理**：`InferenceFactory` 统一产出唤醒/指令/控制头/声纹四个引擎；`Demo*` 实现内置可运行，`Tflite*` 实现读取 `assets/models/` 模型，缺文件自动降级 Demo，业务代码零改动。
- **delegate 降级**：AUTO/GPU/NNAPI/CPU，TFLite 侧按 GPU → NNAPI → CPU 逐档 try/catch 降级，设置页可手动切换，调试页显示实际生效档位。
- **状态机 C1–C4**：C1 真退出（卸载模型 + 停麦克风 + 仅手动恢复 + 首次二次确认）；C2 退出识别保留唤醒；C3 暂停 30s 自动恢复；C4 恢复。
- **执行层**：无障碍手势为主、Shizuku UserService + AIDL 强类型输入为备、最后只读提示；禁止注入/Hook/读内存。坐标按 1080×2340 基准换算。
- **持久化**：Preferences DataStore 保存开关、模式、灵敏度、delegate、声纹开关、反馈与 Shizuku 曾授权标记。
- **安全**：C1 后无常驻拾音；`MANUAL_SILENT` 只认 `ManualResume` / `ExitGame`；埋点只记录事件名与模式维度，不记录音频或声纹向量。

## 构建方式

环境：JDK 17 + Android SDK 35 + Gradle Wrapper 8.7（AGP 8.5.2 / Kotlin 2.0.20 / Compose Compiler 2.0.20）。

```bash
./gradlew :app:assembleDevDebug      # 生成 app/build/outputs/apk/dev/debug/app-dev-debug.apk
./gradlew :app:testDevDebugUnitTest  # JVM 单测：状态机 / 坐标 / 词表
```

flavor：`dev`（调试页可见）、`gray`、`release`（均隐藏调试页）。
CI：任意分支 push 触发 `.github/workflows/build.yml`，上传带 commit sha 的 devDebug APK。

## 模型替换说明

1. 将 `wake_l1.tflite`、`wake_l2.tflite`、`cmd.tflite`、`spk.tflite`、`owner.spk` 放入 `app/src/main/assets/models/`。
2. 按真实张量规格修改 `manifest.json`（输入长度、输出维度、dtype），并把 `demo` 改为 `false`。
3. 输出张量顺序约定见各 `Tflite*Classifier.kt` 注释：指令 23 维 = ACTION(3) + OBJECT(14) + CONTROL(5) + REJECT(1)，控制头取第 17–21 维。
4. `owner.spk` 默认格式：`int32 count + int32 dim + count*dim 个 float32`（后续可替换为加密容器）。模板缺失自动走非声纹路径。
5. 若模型加载失败（文件缺失/不兼容/delegate 异常），自动回落到 Demo 引擎，不会崩溃。

## Shizuku 输入链路

App 通过 Shizuku `bindUserService()` 绑定运行在 shell 身份进程中的本 App `UserService`，
再经自定义 AIDL 强类型方法下发输入命令。无线调试（Android 14+）只是 Shizuku 的启动方式，
客户端始终只有这一套 Binder + 授权 + UserService，不存在第二套协议。

| AIDL 方法 | 参数（全部 int） | 说明 |
|---|---|---|
| `tap` | `x, y` | 屏幕物理坐标点击 |
| `swipe` | `x1, y1, x2, y2, durationMs` | 滑动，时长上限 10000ms |
| `keyEvent` | `keyCode` | 仅 HOME(3) / BACK(4) / ENTER(66) |
| `probe` | — | 探测 `/system/bin/input` 是否可执行 |

安全边界：无字符串命令入口；坐标、时长、keyCode 在 App 进程与 shell 进程双重校验；
shell 进程只调用 `/system/bin/input` 的固定子命令，不使用 `sh -c`，不读 `/data/data`，
不 Hook、不注入代码、不反射隐藏 API。状态分六态（未安装 / 服务未运行 / 未授权 /
已授权 / 授权失效 / 不支持），执行前重查授权，失效即中止剩余步骤并引导重新授权。

## 安全与合规声明

- 模拟点击可能违反游戏协议导致封号；仅限自有设备、测试账号、学习研究，风险自担。
- 不读内存、不注入、不 Hook、不绕反作弊；不模拟透视/自瞄/走位。
- 退出语音（`C1`）为真实退出：模型卸载、麦克风释放，任何语音都无法恢复；只能通过悬浮球/通知栏/设置页手动恢复。
- 声纹模板与语音数据仅存本机、不上传；删除模板即退回非声纹路径。
