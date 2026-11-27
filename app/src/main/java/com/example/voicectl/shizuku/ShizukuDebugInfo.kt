package com.example.voicectl.shizuku

/** 调试页展示用的真机快照，字段全部派生自最近一次 [ShizukuStatus]。 */
data class ShizukuDebugInfo(
    val phase: ShizukuPhase,
    val sdkInt: Int,
    val shizukuInstalled: Boolean,
    val binderAlive: Boolean,
    val shizukuApiVersion: Int?,
    val uid: Int?,
    val selinuxContext: String?,
    val privilege: Privilege,
    /** 来自快照中的 displayMetrics 真实值，横竖屏切换时自然交换 */
    val screenWidth: Int,
    val screenHeight: Int,
    val reason: String?,
    val checkedAtMs: Long,
) {
    companion object {
        fun from(status: ShizukuStatus): ShizukuDebugInfo = ShizukuDebugInfo(
            phase = status.phase,
            sdkInt = status.env.sdkInt,
            shizukuInstalled = status.env.shizukuInstalled,
            binderAlive = status.env.binderAlive,
            shizukuApiVersion = status.env.shizukuApiVersion,
            uid = status.env.uid,
            selinuxContext = status.env.selinuxContext,
            privilege = status.env.privilege,
            screenWidth = status.env.screenWidth,
            screenHeight = status.env.screenHeight,
            reason = status.reason,
            checkedAtMs = status.checkedAtMs,
        )
    }
}
