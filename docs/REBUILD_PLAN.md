# 重构长期目标（2026-11）

## 0. 事实校正

- 指定参考仓库 `Ultrevo/Ultrebo2` 在 GitHub 不存在（404）。实际仓库为 `Ultrevo/Ultrebo`，本计划以此为准。
- 参考实现已完整克隆到本地：
  - `D:\Downloads\apk\refs\SoloPi`（alipay/SoloPi，主线参考）
  - `D:\Downloads\apk\refs\Ultrebo`（Ultrevo/Ultrebo，辅助参考）

## 1. 目标

把当前"语音控制 + 悬浮宏"混合体，收敛成一个 **游戏宏录制/回放工具**：

1. 录制不依赖 ADB、不依赖 root、不依赖被录设备是否支持 Type B 触摸协议。
2. ADB 作为**增强通道**保留：高保真多指、跨应用点击、Type B 设备的时间轴录制。
3. 悬浮 UI 只保留一个可拖动球 + 一个录制胶囊，不再出现横贯屏幕底部的标注条。
4. 宏是可编辑的强类型文档（点击 / 滑动 / 长按 / 等待），不再用截图坐标凑步骤。
5. 失败必须可见：手势被拒、注入失败、坐标越界都要有回执，不允许静默跳过。

## 2. 两条录制路径（核心决策）

| | 主路径：无障碍转发录制 | 增强路径：ADB 被动录制 |
|---|---|---|
| 原理 | 全屏 `TYPE_ACCESSIBILITY_OVERLAY` 接管触摸，抬手时用同一套 `dispatchGesture` 转发给游戏 | `getevent` 读内核触摸事件 |
| 依赖 | 无障碍服务 | ADB 连接 + Type B 协议 |
| 多指 | 记录 pointer 事件，可扩展 | 原生支持 |
| 时序 | 抬手间隔直接成为步骤间隔 | 内核时间戳 |
| 覆盖设备 | 所有 Android 7+ | 仅 Type B（现代真机） |
| 定位 | **默认** | 可选，失败不影响录制 |

这套决策来自 Ultrebo 的"拦截 + 记录 + 立即转发"，解决了当前 Type A 设备直接报错的问题。
SoloPi 的 `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` 被否决：它会让被测 App 收不到 hover/scroll，游戏内表现异常。

## 3. 数据模型（强类型，替代 Map/裸列表）

```text
MacroDocument
  schemaVersion: Int
  id / name / createdAt / updatedAt
  screen: { width, height, rotation }      // 录制时物理像素，仅用于诊断与兜底
  steps: List<MacroStep>

MacroStep (sealed)
  Tap       { x, y, durationMs }           // x/y 归一化 0..1
  Swipe     { points: List<{x,y}>, durationMs }
  LongPress { x, y, durationMs }
  Wait      { durationMs }
```

- 坐标统一归一化：回放时按**当前**显示尺寸还原，旋转/换分辨率不失效。
- 每个步骤带 `delayBeforeMs`，不额外引入"全局间隔"。
- `schemaVersion` 显式存在，迁移函数按版本链执行，禁止"靠默认值猜"。

## 4. 阶段划分

### P0：录制链路替换（本次）
- 新增领域模型与 codec。
- 新增无障碍转发录制器，接管录制入口。
- 删除底部"手动标注"条与截图标注录制路径。
- 悬浮面板改为：`录制 / 运行 / 宏列表`。
- ADB Type A 不再直接拒绝。

验收：
- 未连 ADB 的设备可以录出点击与滑动。
- 录制中不存在底部横条。
- 不再出现"被动录制不可用"阻塞提示。

### P1：宏编辑器
- 宏列表：名称、步数、时长、最后修改时间。
- 编辑：拖动排序、单步改坐标/时长、单步测试、删除 + 撤销。
- 存储：单文件 JSON，写入前备份，解析失败保留原文件。

### P2：识别与规则
- 模板截图匹配（对应 Ultrebo CROP）：从当前屏幕框选区域作为模板。
- 等待条件 / 循环 / 分组规则。
- 图像匹配只在模板附近搜索，禁止全屏暴力匹配。

### P3：稳定性与分发
- 注入回执统计：每步成功/失败/超时。
- 失败取证：截图 + 当前步骤 + 前后 3 步。
- 导出/导入宏包（含白名单与体积上限）。

## 5. 明确删除的代码

| 目标 | 原因 |
|---|---|
| `OverlayViews.createRecordBar` / `updateRecordBar` | 底部横条交互不合理，遮挡游戏 |
| `OverlayControlService.startSnapshotCapture` 及调用链 | 截图标注冒充录制 |
| `SnapshotCaptureActivity` / `SnapshotAnnotationScreen` / `MacroDraft` | 由真实录制 + 编辑器替代 |
| `OverlayViews.createRecordCapsule` | 与新胶囊重复 |
| "全部移除"式批量清空 | 改为单步删除 + 撤销 |

保留：
- `AdbClient` / `AdbBridgeController` / `AdbInjectSession` / `InjectServer`（ADB 能力）
- `AdbTouchRecorder`（增强路径，补 Type A）
- `VoiceAccessibilityService`（手势 + 前台监听）

## 6. 风险与边界

1. 转发录制有抬手延迟：点击在抬手后才到达游戏。这是"录制保真"换来的代价，Ultrebo 同样如此。
2. 部分游戏会检测无障碍注入；此时 ADB 通道是唯一选择，失败会明确提示。
3. 多指录制在 P0 只记录、不转发；转发多指放 P1，需要按 pointer 时间轴重建 stroke。
