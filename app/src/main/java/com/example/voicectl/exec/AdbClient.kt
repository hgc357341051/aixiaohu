package com.example.voicectl.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC 端 bridge.py 的 HTTP 客户端。仅发送配对/连接/心跳/点击请求，
 * 不在 App 内执行任何 adb shell 命令；地址由用户手动填写并保存在本地。
 */
class AdbClient(private val baseUrl: String = "http://192.168.1.23:8080") {
    data class Size(val width: Int, val height: Int)

    suspend fun pair(ip: String, pairPort: Int, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("ip", ip).put("pair_port", pairPort).put("code", code)
            request("/pair", "POST", body)
            Unit
        }
    }

    suspend fun connect(ip: String, connectPort: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("ip", ip).put("port", connectPort)
            request("/connect", "POST", body)
            Unit
        }
    }

    suspend fun heartbeat(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = JSONObject(request("/heartbeat", "GET", null))
            response.optBoolean("connected", false) || response.optString("state") == "device"
        }.getOrDefault(false)
    }

    suspend fun wmSize(): Result<Size> = withContext(Dispatchers.IO) {
        runCatching {
            val response = JSONObject(request("/wm_size", "GET", null))
            Size(response.optInt("width", 1080), response.optInt("height", 2340))
        }
    }

    suspend fun tap(x: Int, y: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("x", x).put("y", y)
            request("/tap", "POST", body)
            Unit
        }
    }

    private fun request(path: String, method: String, body: JSONObject?): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val out: OutputStream = connection.outputStream
            out.use { it.write(bytes) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "{}"
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("桥接服务返回 $code：$text")
        return text
    }
}
