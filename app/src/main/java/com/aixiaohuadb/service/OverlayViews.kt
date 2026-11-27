package com.aixiaohuadb.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Switch
import androidx.core.content.ContextCompat
import com.aixiaohuadb.R

/** 悬浮控件视图工厂与样式；参数调整通过回调交回服务处理 */
internal object OverlayViews {
    private const val PANEL_BG = 0xF2121A2A.toInt()
    private const val PANEL_STROKE = 0x8C5EEAD4.toInt()
    private const val PANEL_STROKE_BRIGHT = 0xFF5EEAD4.toInt()
    private const val BALL_CENTER = 0xFF22D3EE.toInt()
    private const val BALL_EDGE = 0xFF0E7490.toInt()
    private const val BALL_MODE_ACCESSIBILITY = 0xFF5EEAD4.toInt()
    private const val BALL_MODE_VOICE = 0xFF22D3EE.toInt()
    private const val BALL_MODE_ADB = 0xFF34D399.toInt()
    private const val BALL_MODE_DISABLED = 0xFF94A3B8.toInt()
    private const val CAPTURE_BG = 0x66121A2A.toInt()
    private const val MARKER_BG = 0xFF5EEAD4.toInt()
    private const val OK_FLASH = 0xFF22C55E.toInt()
    private const val FAIL_FLASH = 0xFFEF4444.toInt()
    private const val INK = 0xFFE6EDF5.toInt()
    private const val MUTED = 0xFFA9B8CA.toInt()

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private fun rounded(context: Context, radiusDp: Float, bg: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(bg)
            setStroke(dp(context, 1.2f), stroke)
        }

    /** 悬浮球：径向渐变球体 + 模式色描边，比纯色方块更精致 */
    private fun ballDrawable(context: Context, mode: ControlMode, enabled: Boolean): android.graphics.drawable.Drawable {
        val icon = androidx.core.content.ContextCompat.getDrawable(
            context,
            if (enabled) R.drawable.ic_overlay_controller else R.drawable.ic_overlay_controller_disabled,
        )
        val base = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x33121A2A.toInt())
            setStroke(dp(context, 2f), when {
                !enabled -> BALL_MODE_DISABLED
                mode == ControlMode.VOICE -> BALL_MODE_VOICE
                mode == ControlMode.ADB -> BALL_MODE_ADB
                else -> BALL_MODE_ACCESSIBILITY
            })
        }
        return icon?.let { LayerDrawable(arrayOf(base, it)) } ?: base
    }

    // ---------------- 悬浮球 ----------------

    fun createBall(context: Context): TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    fun applyBallStyle(
        context: Context,
        view: TextView,
        params: WindowManager.LayoutParams,
        cfg: BallConfig,
        mode: ControlMode = ControlMode.DEFAULT,
        voiceEnabled: Boolean = true,
    ) {
        val sizePx = dp(context, cfg.sizeDp.toFloat())
        view.text = when (mode) {
            ControlMode.VOICE -> if (voiceEnabled) "胡" else "启"
            ControlMode.ADB -> "ADB"
            ControlMode.ACCESSIBILITY -> "手"
        }
        view.textSize = cfg.sizeDp * 0.22f
        view.alpha = cfg.alphaPercent / 100f
        view.background = ballDrawable(context, mode, voiceEnabled)
        params.width = sizePx
        params.height = sizePx
    }

    // ---------------- 指令/宏按钮 ----------------

    fun createButton(context: Context): TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    fun applyButtonStyle(context: Context, view: TextView, params: WindowManager.LayoutParams, w: OverlayWidget) {
        val sizePx = dp(context, w.sizeDp.toFloat())
        val isCircle = w.label.length <= 2
        view.text = w.label
        view.textSize = if (isCircle) w.sizeDp * 0.30f else w.sizeDp * 0.20f
        view.alpha = w.alphaPercent / 100f
        val pad = dp(context, w.sizeDp * 0.08f)
        view.setPadding(pad, pad, pad, pad)
        view.background = rounded(context, if (isCircle) w.sizeDp / 2f else 16f, PANEL_BG, PANEL_STROKE)
        params.width = sizePx
        params.height = sizePx
    }

    /** 执行结果闪色；restore 由服务在 600ms 后回调还原正常样式 */
    fun flash(view: TextView, ok: Boolean, context: Context, labelLen: Int, radiusForSize: Int) {
        val isCircle = labelLen <= 2
        view.background = rounded(
            context,
            if (isCircle) radiusForSize / 2f else 16f,
            PANEL_BG,
            if (ok) OK_FLASH else FAIL_FLASH,
        )
    }

    // ---------------- 调节面板 ----------------

    class PanelCallbacks(
        val onSelectTarget: (String?) -> Unit,
        val onSizeChange: (Int) -> Unit,
        val onAlphaChange: (Int) -> Unit,
        val onLockChange: (Boolean) -> Unit,
        val onBallVisibleChange: (Boolean) -> Unit,
        val onModeChange: (ControlMode) -> Unit,
        val onVoiceBlocked: () -> Unit,
        val onPauseVoice: () -> Unit,
        val onResumeVoice: () -> Unit,
        val onStartRecord: () -> Unit,
        val onOpenSettings: () -> Unit,
        val onClose: () -> Unit,
    )

    /** 面板句柄：滑条文案在拖动中实时更新，不重建面板 */
    class PanelHolder(
        val root: LinearLayout,
        val sizeLabel: TextView,
        val alphaLabel: TextView,
        val statusText: TextView,
        val voiceRow: LinearLayout,
    )

    /**
     * 游戏内调节面板。targets: (widgetId, label)，widgetId=null 表示悬浮球。
     * current 值用于初始化滑条/开关位置。
     */
    fun createPanel(
        context: Context,
        targets: List<Pair<String?, String>>,
        selectedId: String?,
        sizeDp: Int,
        alphaPercent: Int,
        locked: Boolean,
        ballVisible: Boolean,
        mode: ControlMode,
        statusText: String,
        voicePaused: Boolean,
        voiceEnabled: Boolean,
        cbs: PanelCallbacks,
    ): PanelHolder {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, 16f, PANEL_BG, PANEL_STROKE)
            setPadding(dp(context, 16f), dp(context, 14f), dp(context, 16f), dp(context, 14f))
        }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        // 头部：标题 + 模式徽标 + 关闭
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(text("悬浮控制", 15f, INK, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val modeBadge = text(mode.label, 11f, when (mode) {
            ControlMode.ADB -> BALL_MODE_ADB
            ControlMode.VOICE -> BALL_MODE_VOICE
            ControlMode.ACCESSIBILITY -> BALL_MODE_ACCESSIBILITY
        }, bold = true).apply {
            background = rounded(context, 10f, 0xFF1A2536.toInt(), PANEL_STROKE)
            setPadding(dp(context, 8f), dp(context, 3f), dp(context, 8f), dp(context, 3f))
        }
        header.addView(modeBadge)
        header.addView(text("✕", 15f, MUTED, bold = true).apply {
            setPadding(dp(context, 12f), 0, 0, 0)
            setOnClickListener { cbs.onClose() }
        })
        root.addView(header)

        // 状态行：语音 / 游戏 / 执行链真实状态
        val statusView = text(statusText, 11f, MUTED).apply {
            setPadding(0, dp(context, 8f), 0, 0)
        }
        root.addView(statusView)

        // 模式切换：语音识别 / 无障碍 / ADB 三值并列；语音未开启时只作为开启入口
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 12f), 0, 0)
        }
        fun modeButton(label: String, target: ControlMode, enabled: Boolean = true): TextView = text(label, 11f,
            if (mode == target) Color.WHITE else if (enabled) MUTED else BALL_MODE_DISABLED, bold = mode == target).apply {
            background = rounded(
                context, 10f,
                if (mode == target) 0xFF0E7490.toInt() else 0xFF1A2536.toInt(),
                if (mode == target) PANEL_STROKE_BRIGHT else PANEL_STROKE,
            )
            gravity = Gravity.CENTER
            setPadding(dp(context, 4f), dp(context, 7f), dp(context, 4f), dp(context, 7f))
            alpha = if (enabled) 1f else 0.7f
            setOnClickListener {
                if (mode == target) return@setOnClickListener
                if (target == ControlMode.VOICE && !voiceEnabled) cbs.onVoiceBlocked()
                else cbs.onModeChange(target)
            }
        }
        modeRow.addView(modeButton(if (voiceEnabled) "语音" else "语音·未开", ControlMode.VOICE, voiceEnabled).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        modeRow.addView(modeButton("无障碍", ControlMode.ACCESSIBILITY).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 6f), 0, 0, 0)
            }
        })
        modeRow.addView(modeButton("ADB", ControlMode.ADB).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 6f), 0, 0, 0)
            }
        })
        root.addView(modeRow)

        // 目标 chips
        val chips = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(context, 10f), 0, 0) }
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        targets.forEach { (id, label) ->
            val selected = id == selectedId
            val chip = text(label, 12f, if (selected) Color.WHITE else MUTED).apply {
                background = rounded(context, 14f, if (selected) 0xFF0E7490.toInt() else 0xFF1A2536.toInt(), PANEL_STROKE)
                setPadding(dp(context, 10f), dp(context, 4f), dp(context, 10f), dp(context, 4f))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, dp(context, 6f), 0) }
                layoutParams = lp
                setOnClickListener { cbs.onSelectTarget(id) }
            }
            chipRow.addView(chip)
        }
        chips.addView(chipRow)
        root.addView(chips)

        // 大小滑条
        val sizeLabel = text("大小 ${sizeDp}dp", 12f, MUTED).apply {
            setPadding(0, dp(context, 10f), 0, 0)
        }
        root.addView(sizeLabel)
        root.addView(SeekBar(context).apply {
            max = OverlayWidget.MAX_SIZE_DP - OverlayWidget.MIN_SIZE_DP
            progress = sizeDp - OverlayWidget.MIN_SIZE_DP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) cbs.onSizeChange(progress + OverlayWidget.MIN_SIZE_DP)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })

        // 透明度滑条
        val alphaLabel = text("不透明度 $alphaPercent%", 12f, MUTED).apply {
            setPadding(0, dp(context, 8f), 0, 0)
        }
        root.addView(alphaLabel)
        root.addView(SeekBar(context).apply {
            max = OverlayWidget.MAX_ALPHA - OverlayWidget.MIN_ALPHA
            progress = alphaPercent - OverlayWidget.MIN_ALPHA
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) cbs.onAlphaChange(progress + OverlayWidget.MIN_ALPHA)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        })

        // 位置锁定
        val lockRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 10f), 0, 0)
        }
        lockRow.addView(text("锁定位置（游戏内防误拖）", 12f, INK).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        lockRow.addView(Switch(context).apply { isChecked = locked; setOnCheckedChangeListener { _, c -> cbs.onLockChange(c) } })
        root.addView(lockRow)

        // 球显隐
        val visRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 6f), 0, 0)
        }
        visRow.addView(text("显示悬浮球", 12f, INK).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        visRow.addView(Switch(context).apply { isChecked = ballVisible; setOnCheckedChangeListener { _, c -> cbs.onBallVisibleChange(c) } })
        root.addView(visRow)

        // 语音控制行（暂停/恢复只在语音模式有意义）
        val voiceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 10f), 0, 0)
        }
        fun actionButton(label: String, onClick: () -> Unit): TextView =
            text(label, 12f, INK).apply {
                background = rounded(context, 10f, 0xFF1A2536.toInt(), PANEL_STROKE)
                setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
                setOnClickListener { onClick() }
            }
        voiceRow.addView(actionButton(if (voicePaused) "恢复语音" else "暂停语音",
            if (voicePaused) cbs.onResumeVoice else cbs.onPauseVoice).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        voiceRow.addView(actionButton("打开设置", cbs.onOpenSettings).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 8f), 0, 0, 0)
            }
        })
        voiceRow.visibility = if (mode == ControlMode.VOICE) View.VISIBLE else View.GONE
        root.addView(voiceRow)

        // 操作行：录制（两种模式都可用）+ 关闭
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 8f), 0, 0)
        }
        actionRow.addView(actionButton("开始录制", cbs.onStartRecord).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        actionRow.addView(actionButton("关闭面板", cbs.onClose).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 8f), 0, 0, 0)
            }
        })
        root.addView(actionRow)
        return PanelHolder(root, sizeLabel, alphaLabel, statusView, voiceRow)
    }

    // ---------------- 命名窗（focusable） ----------------

    fun createNamingWindow(
        context: Context,
        initialName: String,
        onConfirm: (String) -> Unit,
        onCancel: () -> Unit,
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, 14f, PANEL_BG, PANEL_STROKE)
            setPadding(dp(context, 16f), dp(context, 14f), dp(context, 16f), dp(context, 14f))
        }
        root.addView(
            TextView(context).apply {
                text = "为录制的宏命名"
                textSize = 14f
                setTextColor(INK)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        val input = EditText(context).apply {
            setText(initialName)
            textSize = 13f
            setTextColor(Color.WHITE)
            hint = "如：连招三"
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(context, 10f), 0, 0) }
        }
        root.addView(input)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 10f), 0, 0)
        }
        fun btn(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(if (label == "保存") Color.WHITE else MUTED)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(context, 10f, if (label == "保存") 0xFF0E7490.toInt() else 0xFF1A2536.toInt(), PANEL_STROKE)
            gravity = Gravity.CENTER
            setPadding(dp(context, 12f), dp(context, 7f), dp(context, 12f), dp(context, 7f))
            setOnClickListener { onClick() }
        }
        row.addView(btn("取消", onCancel).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(btn("保存", { onConfirm(input.text.toString()) }).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 10f), 0, 0, 0)
            }
        })
        root.addView(row)
        return root
    }

    // ---------------- 底部录制条（不遮挡顶部 25%） ----------------

    class RecordBarCallbacks(
        val onToggleRecord: () -> Unit,
        val onUndo: () -> Unit,
        val onFinish: () -> Unit,
        val onCancel: () -> Unit,
        /** 收起/展开时由服务调整悬浮窗宽高，避免宽条长期挡住游戏操作区 */
        val onCollapsedChange: (Boolean) -> Unit,
    )

    /** 录制条自身的可变状态；挂在 root.tag 上，避免每次 update 重建整个视图。 */
    private class RecordBarHolder(
        var count: Int = 0,
        var confirmingCancel: Boolean = false,
        var collapsed: Boolean = false,
    )

    /**
     * 游戏内手动标注条：不透明底、明确的主次按钮、无重复入口。
     *
     * 旧版把 5 个控件塞进 360dp：两个相机入口、三个无语义符号（↶ ✓ ✕）、
     * 半透明底还会透出下层导航文字。这里改为：
     * 左侧状态（手动标注 / 已标 N 步），右侧 4 个带文字的 40dp 按钮；
     * 0 步时只突出「标注」，有步骤后才突出「完成」，撤销/完成在 0 步时禁用。
     */
    fun createRecordBar(context: Context, cbs: RecordBarCallbacks): LinearLayout {
        val holder = RecordBarHolder()

        fun button(label: String, tag: String, primary: Boolean, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = dp(context, 46f)
                minHeight = dp(context, 40f)
                setPadding(dp(context, 9f), 0, dp(context, 9f), 0)
                background = recordButtonBackground(context, primary)
                this.tag = tag
                setOnClickListener { onClick() }
            }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 必须完全不透明：F2 alpha 会透出下层设置/调试文字，观感像残影
            background = rounded(context, 16f, 0xFF0B1220.toInt(), 0xFF263449.toInt())
            setPadding(dp(context, 10f), dp(context, 8f), dp(context, 8f), dp(context, 8f))
            elevation = dp(context, 6f).toFloat()
            tag = holder
        }

        val state = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 2f), 0, dp(context, 4f), 0)
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "手动标注"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(INK)
                })
                addView(TextView(context).apply {
                    text = "收起"
                    textSize = 10f
                    setTextColor(0xFF7DD3C7.toInt())
                    tag = "record_collapse"
                    setPadding(dp(context, 6f), 0, 0, 0)
                })
            })
            addView(TextView(context).apply {
                text = "已标 0 步"
                textSize = 10f
                setTextColor(MUTED)
                tag = "record_count"
            })
        }
        root.addView(
            state,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val spacer = View(context).apply { tag = "record_spacer" }
        root.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val capture = button("截屏", "record_action_capture", primary = true) { cbs.onToggleRecord() }
        // 可见文案保持紧凑，但无障碍语义必须是明确的“截屏标注”，避免被理解成游戏区落点采集
        capture.contentDescription = "截屏标注"
        val undo = button("撤销", "record_action_undo", primary = false) { cbs.onUndo() }
        val finish = button("完成", "record_action_finish", primary = false) { cbs.onFinish() }
        val cancel = button("退出", "record_action_cancel", primary = false) {}
        cancel.setOnClickListener {
            when {
                holder.count <= 0 -> cbs.onCancel()
                holder.confirmingCancel -> cbs.onCancel()
                else -> {
                    // 有步骤时二次确认，避免误触丢宏
                    holder.confirmingCancel = true
                    cancel.text = "确认"
                    cancel.setTextColor(0xFFFF7A7A.toInt())
                    cancel.postDelayed({
                        if (holder.confirmingCancel) {
                            holder.confirmingCancel = false
                            cancel.text = "退出"
                            cancel.setTextColor(Color.WHITE)
                        }
                    }, 2_500L)
                }
            }
        }
        listOf(capture, undo, finish, cancel).forEach { buttonView ->
            root.addView(
                buttonView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(context, 40f),
                ).apply { setMargins(dp(context, 4f), 0, 0, 0) },
            )
        }

        fun setCollapsed(collapsed: Boolean) {
            holder.collapsed = collapsed
            val visibility = if (collapsed) View.GONE else View.VISIBLE
            listOf(capture, undo, finish, cancel).forEach { it.visibility = visibility }
            spacer.visibility = visibility
            (state.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = if (collapsed) {
                    ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
                state.layoutParams = params
            }
            root.findViewWithTag<TextView>("record_collapse")?.text =
                if (collapsed) "展开" else "收起"
            cbs.onCollapsedChange(collapsed)
        }
        state.setOnClickListener { setCollapsed(!holder.collapsed) }

        applyRecordBarState(root, holder)
        return root
    }

    private fun recordButtonBackground(
        context: Context,
        primary: Boolean,
        enabled: Boolean = true,
    ): GradientDrawable = rounded(
        context,
        10f,
        when {
            !enabled -> 0xFF141C29.toInt()
            primary -> 0xFF0F766E.toInt()
            else -> 0xFF16202E.toInt()
        },
        when {
            !enabled -> 0xFF243041.toInt()
            primary -> 0xFF2DD4BF.toInt()
            else -> 0xFF334155.toInt()
        },
    )

    private fun applyRecordBarState(root: LinearLayout, holder: RecordBarHolder) {
        val context = root.context
        val hasSteps = holder.count > 0
        root.findViewWithTag<TextView>("record_count")?.text = "已标 ${holder.count} 步"

        root.findViewWithTag<TextView>("record_action_capture")?.apply {
            text = if (hasSteps) "补一帧" else "截屏"
            background = recordButtonBackground(context, primary = !hasSteps)
        }
        root.findViewWithTag<TextView>("record_action_undo")?.apply {
            isEnabled = hasSteps
            alpha = if (hasSteps) 1f else 0.35f
        }
        root.findViewWithTag<TextView>("record_action_finish")?.apply {
            isEnabled = hasSteps
            alpha = if (hasSteps) 1f else 0.35f
            background = recordButtonBackground(context, primary = hasSteps, enabled = hasSteps)
        }
        root.findViewWithTag<TextView>("record_action_cancel")?.apply {
            holder.confirmingCancel = false
            text = "退出"
            setTextColor(Color.WHITE)
        }
    }

    fun updateRecordBar(view: View, count: Int, recording: Boolean) {
        val holder = view.tag as? RecordBarHolder ?: return
        holder.count = count.coerceAtLeast(0)
        if (view is LinearLayout) applyRecordBarState(view, holder)
    }

    // ---------------- ADB 被动录制的屏幕控制胶囊 ----------------

    class AdbRecordChipCallbacks(
        val onFinish: () -> Unit,
        val onCancel: () -> Unit,
    )

    /**
     * 被动录制期间通知被关闭时的唯一可见控制：顶部小胶囊，显示步数并提供完成/取消。
     * 它不覆盖游戏触摸区（仅自身区域可点），也不依赖通知权限。
     */
    fun createAdbRecordChip(context: Context, cbs: AdbRecordChipCallbacks): LinearLayout {
        fun chipButton(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(context, 42f)
            minHeight = dp(context, 32f)
            setPadding(dp(context, 8f), 0, dp(context, 8f), 0)
            background = recordButtonBackground(context, primary)
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(context, 14f, 0xFF0B1220.toInt(), 0xFF263449.toInt())
            setPadding(dp(context, 10f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
            elevation = dp(context, 4f).toFloat()
        }
        root.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFEF4444.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(context, 7f), dp(context, 7f)).apply {
                rightMargin = dp(context, 7f)
            }
        })
        root.addView(TextView(context).apply {
            text = "录制中 · 0 步"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            tag = "adb_record_count"
        })
        root.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        root.addView(chipButton("完成", true, cbs.onFinish).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 32f),
            ).apply { leftMargin = dp(context, 10f) }
        })
        root.addView(chipButton("取消", false, cbs.onCancel).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 32f),
            ).apply { leftMargin = dp(context, 4f) }
        })
        return root
    }

    fun updateAdbRecordChip(view: View, count: Int) {
        view.findViewWithTag<TextView>("adb_record_count")?.text = "录制中 · ${count.coerceAtLeast(0)} 步"
    }

    /** 面板切到语音模式时的二次确认：说明麦克风/模型/常驻通知 */
    fun createVoiceConfirmWindow(
        context: Context,
        onConfirm: () -> Unit,
        onCancel: () -> Unit,
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, 14f, PANEL_BG, PANEL_STROKE)
            setPadding(dp(context, 18f), dp(context, 16f), dp(context, 18f), dp(context, 16f))
        }
        root.addView(TextView(context).apply {
            text = "开启语音识别？"
            textSize = 15f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(context).apply {
            text = "将启动麦克风拾音、加载语音模型并显示常驻通知。关闭后物理释放，不会后台监听。"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(context, 10f), 0, 0)
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 14f), 0, 0)
        }
        fun btn(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(
                context, 10f,
                if (primary) 0xFF0E7490.toInt() else 0xFF1A2536.toInt(), PANEL_STROKE,
            )
            gravity = Gravity.CENTER
            setPadding(dp(context, 12f), dp(context, 7f), dp(context, 12f), dp(context, 7f))
            setOnClickListener { onClick() }
        }
        row.addView(btn("取消", false, onCancel).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(btn("确认开启", true, onConfirm).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 10f), 0, 0, 0)
            }
        })
        root.addView(row)
        return root
    }
}
