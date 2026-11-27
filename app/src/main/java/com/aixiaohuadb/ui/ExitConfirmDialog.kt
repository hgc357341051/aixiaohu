package com.aixiaohuadb.ui

import androidx.compose.runtime.Composable
import com.aixiaohuadb.ui.common.ConfirmDialog

/** C1 首次触发的二次确认；文案与原型逐字一致 */
@Composable
fun ExitConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "确认退出语音唤醒？",
        text = "退出后唤醒模型将被真实卸载、麦克风退出常驻，任何语音（包括「恢复语音」）都无法被听见。" +
            "恢复只能通过悬浮球 / 实体键 / 通知栏 / 设置页手动触发。",
        confirmLabel = "确认退出",
        onCancel = onCancel,
        onConfirm = onConfirm,
    )
}
