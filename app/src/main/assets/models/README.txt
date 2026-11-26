模型占位目录说明
================
本目录随工程内置 manifest.json / labels.json。
真实模型 wake_l1.tflite、wake_l2.tflite、cmd.tflite、spk.tflite 与 owner.spk 暂未提供。
AppContainer 启动时检测文件是否齐全；缺失时自动使用 Demo 推理实现，不会崩溃。
补齐模型文件后将 manifest.json 的 demo 改为 false 即切换到 TFLite 实现。
