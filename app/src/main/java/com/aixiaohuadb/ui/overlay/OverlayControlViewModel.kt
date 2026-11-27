package com.aixiaohuadb.ui.overlay

import androidx.lifecycle.ViewModel
import com.aixiaohuadb.service.BallConfig
import com.aixiaohuadb.service.ControlMode
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.service.OverlayStore
import com.aixiaohuadb.service.OverlayWidget
import kotlinx.coroutines.flow.StateFlow

/** 悬浮控制设置页：球配置 + 按钮列表 + 宏列表 + 待命名宏 + 添加入口，与悬浮服务共享 OverlayStore */
class OverlayControlViewModel : ViewModel() {
    val widgets: StateFlow<List<OverlayWidget>> = OverlayStore.widgets
    val ball: StateFlow<BallConfig> = OverlayStore.ball
    val macros: StateFlow<List<MacroConfig>> = OverlayStore.macros
    val pendingMacro: StateFlow<MacroConfig?> = OverlayStore.pendingMacro
    val controlMode: StateFlow<ControlMode> = OverlayStore.controlMode

    fun setControlMode(mode: ControlMode) = OverlayStore.setControlMode(mode)

    fun addPreset(commandId: Int, label: String) = OverlayStore.addPreset(commandId, label)

    fun removeWidget(id: String) = OverlayStore.removeWidget(id)

    fun updateWidgetLive(id: String, sizeDp: Int, alphaPercent: Int) =
        OverlayStore.updateWidgetLive(id, sizeDp, alphaPercent)

    fun setWidgetLocked(id: String, locked: Boolean) = OverlayStore.setWidgetLocked(id, locked)

    fun updateBallLive(sizeDp: Int, alphaPercent: Int) = OverlayStore.updateBallLive(sizeDp, alphaPercent)

    fun setBallVisible(visible: Boolean) = OverlayStore.setBallVisible(visible)

    fun setBallLocked(locked: Boolean) = OverlayStore.setBallLocked(locked)

    fun removeMacro(macroId: Long) = OverlayStore.removeMacro(macroId)

    fun renameMacro(macroId: Long, name: String) = OverlayStore.renameMacro(macroId, name)

    fun savePendingMacro(name: String) {
        val steps = OverlayStore.pendingMacro.value?.steps ?: return
        OverlayStore.saveMacro(name, steps)
    }

    fun discardPendingMacro() = OverlayStore.discardPendingMacro()
}
