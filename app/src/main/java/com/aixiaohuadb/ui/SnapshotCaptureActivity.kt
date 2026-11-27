package com.aixiaohuadb.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * MediaProjection 单帧截屏：授权后立刻抓一帧并 stop，不做录屏。
 * 授权被拒时提供「从相册导入截图」降级路径。
 */
class SnapshotCaptureActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SNAPSHOT_URI = "snapshot_uri"
        const val ACTION_ANNOTATE = "com.aixiaohuadb.action.ANNOTATE"

        /** 单飞闸门：重复点击「截屏标注」只允许一个抓屏实例，防止并发 MediaProjection */
        private val startGate = java.util.concurrent.atomic.AtomicBoolean(false)

        fun start(context: Context) {
            if (!startGate.compareAndSet(false, true)) return
            runCatching {
                context.startActivity(
                    Intent(context, SnapshotCaptureActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { startGate.set(false) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    /** 授权弹窗是否仍在显示；超时后必须给出相册降级 UI，不能停在空白页 */
    private var authorizationPending = false
    private var frameDelivered = false
    private val timeoutRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        release()
        if (!frameDelivered) {
            showFallback("截屏授权超时或未返回图像，可从相册导入一张截图")
        }
    }
    /** 已授权后无帧超时；独立引用，帧到达或降级时必须能单独移除 */
    private val noFrameRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        release()
        if (!frameDelivered) showFallback("未能获取截屏画面，可从相册导入一张截图")
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        authorizationPending = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(noFrameRunnable)
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            showFallback("系统截屏授权被拒绝，可从相册导入一张截图")
            return@registerForActivityResult
        }
        // data 已智能转换为非空 Intent，不使用 !!
        val manager = getSystemService(MediaProjectionManager::class.java)
        val acquired = runCatching { manager?.getMediaProjection(result.resultCode, data) }
        projection = acquired.getOrNull()
        if (projection == null) {
            showFallback("截屏服务未就绪，可从相册导入一张截图")
            return@registerForActivityResult
        }
        runCatching { captureSingleFrame() }.onFailure { t ->
            release()
            showFallback("截屏失败：${t.message ?: "未知错误"}")
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(::openAnnotate) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startGate.set(true)
        // 单帧截屏不需要 RECORD_AUDIO 权限，不要申请麦克风
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            showFallback("当前系统不支持 MediaProjection，可从相册导入")
            return
        }
        // 授权弹窗本身也要超时兜底（1.5s 只覆盖已授权后无帧）；必须先注册再 launch
        authorizationPending = true
        handler.postDelayed(timeoutRunnable, 8_000L)
        runCatching { projectionLauncher.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                handler.removeCallbacks(timeoutRunnable)
                showFallback("无法发起截屏授权，可从相册导入一张截图")
            }
    }

    private fun captureSingleFrame() {
        if (isFinishing || isDestroyed) return
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val active = projection ?: run { showFallback("截屏服务未就绪"); return }
        virtualDisplay = active.createVirtualDisplay(
            "voice_ctl_snapshot",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (frameDelivered || isFinishing || isDestroyed) {
                runCatching { image.close() }
                return@setOnImageAvailableListener
            }
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                val dir = File(cacheDir, "snapshot").apply { mkdirs() }
                val file = File(dir, "shot_${System.currentTimeMillis()}.png")
                cropped.compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                frameDelivered = true
                handler.removeCallbacks(timeoutRunnable)
                handler.removeCallbacks(noFrameRunnable)
                handler.post { openAnnotate(uri) }
            } catch (t: Throwable) {
                handler.post { showFallback("截屏失败：${t.message ?: "未知错误"}") }
            } finally {
                runCatching { image.close() }
                runCatching { release() }
            }
        }, handler)
        // 已授权后 1.5s 内没有图像也释放，并走相册导入降级，绝不持续投屏/停在空白页
        handler.postDelayed(noFrameRunnable, 1_500L)
    }

    private fun openAnnotate(uri: Uri) {
        if (isFinishing || isDestroyed) return
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(noFrameRunnable)
        release()
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_ANNOTATE)
                    .putExtra(EXTRA_SNAPSHOT_URI, uri.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
        finish()
    }

    private fun showFallback(message: String) {
        if (isFinishing || isDestroyed) return
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(noFrameRunnable)
        release()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 120, 32, 32) }
        root.addView(Button(this).apply {
            text = "从相册导入截图"
            setOnClickListener { galleryLauncher.launch("image/*") }
        })
        root.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }

    /** 每个资源单独兜底：单个释放失败不能阻断其余资源，避免泄漏与二次崩溃 */
    private fun release() {
        runCatching { imageReader?.setOnImageAvailableListener(null, handler) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(noFrameRunnable)
        release()
        startGate.set(false)
        super.onDestroy()
    }
}
