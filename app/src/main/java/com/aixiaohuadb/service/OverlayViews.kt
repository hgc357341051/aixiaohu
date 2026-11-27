package com.aixiaohuadb.service

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Switch

/** 悬浮控件视图工厂与样式；参数调整通过回调交回服务处理 */
internal object OverlayViews {
    private const val PANEL_BG = 0xF2121A2A.toInt()
    private const val PANEL_STROKE = 0x8C5EEAD4.toInt()
    private const val PANEL_STROKE_BRIGHT = 0xFF5EEAD4.toInt()
    private const val BALL_CENTER = 0xFF22D3EE.toInt()
    private const val BALL_EDGE = 0xFF0E7490.toInt()
    private const val BALL_MODE_MANUAL = 0xFFF59E0B.toInt()
    private const val BALL_MODE_VOICE = 0xFF5EEAD4.toInt()
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
    private fun ballDrawable(context: Context, mode: ControlMode): GradientDrawable {
        val stroke = if (mode == ControlMode.MANUAL) BALL_MODE_MANUAL else BALL_MODE_VOICE
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setColors(intArrayOf(BALL_CENTER, BALL_EDGE))
            gradientRadius = dp(context, 60f).toFloat()
            setStroke(dp(context, 2f), stroke)
        }
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
        mode: ControlMode = ControlMode.VOICE,
    ) {
        val sizePx = dp(context, cfg.sizeDp.toFloat())
        view.text = if (mode == ControlMode.MANUAL) "手" else "胡"
        view.textSize = cfg.sizeDp * 0.34f
        view.alpha = cfg.alphaPercent / 100f
        view.background = ballDrawable(context, mode)
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
        val modeBadge = text(mode.label, 11f, if (mode == ControlMode.MANUAL) BALL_MODE_MANUAL else PANEL_STROKE_BRIGHT, bold = true).apply {
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

        // 模式切换：语音识别 / 手动点触
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 12f), 0, 0)
        }
        fun modeButton(label: String, target: ControlMode): TextView = text(label, 12f,
            if (mode == target) Color.WHITE else MUTED, bold = mode == target).apply {
            background = rounded(
                context, 10f,
                if (mode == target) 0xFF0E7490.toInt() else 0xFF1A2536.toInt(),
                if (mode == target) PANEL_STROKE_BRIGHT else PANEL_STROKE,
            )
            gravity = Gravity.CENTER
            setPadding(dp(context, 8f), dp(context, 7f), dp(context, 8f), dp(context, 7f))
            setOnClickListener { if (mode != target) cbs.onModeChange(target) }
        }
        modeRow.addView(modeButton("语音识别", ControlMode.VOICE).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        modeRow.addView(modeButton("手动点触", ControlMode.MANUAL).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(context, 8f), 0, 0, 0)
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

    // ---------------- 录制捕获层 ----------------

    class CaptureCallbacks(
        val onPoint: (Int, Int) -> Unit,
        val onFinish: () -> Unit,
        val onCancel: () -> Unit,
    )

    /** 全屏逐点标记捕获层：触摸事件被消费不透传，每次落点画标记圆 */
    fun createCaptureLayer(context: Context, pointCount: Int, cbs: CaptureCallbacks): FrameLayout {
        val root = FrameLayout(context).apply {
            setBackgroundColor(CAPTURE_BG)
        }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PANEL_BG)
            setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
        }
        topBar.addView(
            TextView(context).apply {
                text = "录制中（已标 $pointCount 点）：依次点击要录制的位置，点击不会传给游戏"
                textSize = 12f
                setTextColor(INK)
                tag = "capture_hint"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        fun barButton(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(context, 10f, 0xFF0E7490.toInt(), PANEL_STROKE)
            setPadding(dp(context, 12f), dp(context, 5f), dp(context, 12f), dp(context, 5f))
            setOnClickListener { onClick() }
        }
        topBar.addView(barButton("取消", cbs.onCancel).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(context, 8f), 0, 0, 0) }
        })
        topBar.addView(barButton("完成", cbs.onFinish).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(context, 8f), 0, 0, 0) }
        })
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
            ),
        )
        // 触摸捕获：仅响应 DOWN 记录落点，全部事件消费不透传
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                cbs.onPoint(e.rawX.toInt(), e.rawY.toInt())
            }
            true
        }
        return root
    }

    /** 在捕获层内画一个落点标记 */
    fun addMarker(context: Context, layer: FrameLayout, x: Int, y: Int) {
        val marker = View(context).apply {
            background = rounded(context, 9f, 0x66121A2A, MARKER_BG)
        }
        val size = dp(context, 18f)
        layer.addView(
            marker,
            FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
                setMargins(x - size / 2, y - size / 2, 0, 0)
            },
        )
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
}
