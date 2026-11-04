package com.nagibatorz.nixi

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Настройки приложения: ключ и модели хранятся только в памяти телефона. */
object Prefs {
    private const val FILE = "nixi_prefs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("apiKey", "").orEmpty()
    fun setApiKey(c: Context, v: String) {
        sp(c).edit().putString("apiKey", v.trim()).apply()
    }

    fun textModel(c: Context): String =
        sp(c).getString("textModel", DEFAULT_TEXT_MODEL).orEmpty()

    fun setTextModel(c: Context, v: String) {
        sp(c).edit().putString("textModel", if (v.isBlank()) DEFAULT_TEXT_MODEL else v.trim()).apply()
    }

    fun visionModel(c: Context): String =
        sp(c).getString("visionModel", DEFAULT_VISION_MODEL).orEmpty()

    fun setVisionModel(c: Context, v: String) {
        sp(c).edit().putString("visionModel", if (v.isBlank()) DEFAULT_VISION_MODEL else v.trim()).apply()
    }

    fun voice(c: Context): Boolean = sp(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("voice", v).apply()
    }

    const val DEFAULT_TEXT_MODEL = "meta-llama/llama-3.3-70b-instruct:free"
    const val DEFAULT_VISION_MODEL = "google/gemini-2.0-flash-exp:free"
}

/** Запросы к OpenRouter. Ключ в код приложения не зашит — берётся из настроек. */
object Ai {

    private val pool = Executors.newSingleThreadExecutor()

    const val SYSTEM_PROMPT =
        "Ты Никси — голосовой ассистент, который живёт в телефоне Илюхи. " +
            "Твой ответ читается вслух, поэтому отвечай коротко: одно–три предложения, " +
            "живым разговорным русским языком, без markdown, без звёздочек, без списков и без эмодзи. " +
            "Сразу по делу, дружелюбно, без вступлений и без воды. " +
            "Если просят совет — дай один конкретный совет."

    fun ask(
        apiKey: String,
        model: String,
        system: String,
        history: List<Pair<String, String>>,
        imageBase64: String?,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        pool.execute {
            try {
                val messages = JSONArray()
                if (system.isNotBlank()) {
                    messages.put(JSONObject().put("role", "system").put("content", system))
                }
                for (i in history.indices) {
                    val role = history[i].first
                    val text = history[i].second
                    val isLast = i == history.lastIndex
                    if (isLast && imageBase64 != null && role == "user") {
                        val content = JSONArray()
                        content.put(JSONObject().put("type", "text").put("text", text))
                        content.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")
                                )
                        )
                        messages.put(JSONObject().put("role", role).put("content", content))
                    } else {
                        messages.put(JSONObject().put("role", role).put("content", text))
                    }
                }

                val body = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("max_tokens", 600)
                    .put("temperature", 0.7)

                val conn = URL("https://openrouter.ai/api/v1/chat/completions")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 30000
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("HTTP-Referer", "https://playerok.com/profile/Nagibatorz")
                conn.setRequestProperty("X-Title", "Nixi Android")

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    onError("HTTP $code: " + raw.take(300))
                    return@execute
                }

                val json = JSONObject(raw)
                val choices = json.optJSONArray("choices")
                val text = choices
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

                if (text.isBlank()) onError("Пустой ответ модели") else onResult(text.trim())
            } catch (e: Exception) {
                onError(e.message ?: "Ошибка сети")
            }
        }
    }
}

fun Bitmap.scaledTo(maxSide: Int): Bitmap {
    val maxDim = maxOf(width, height)
    if (maxDim <= maxSide) return this
    val k = maxSide.toFloat() / maxDim
    return Bitmap.createScaledBitmap(this, (width * k).toInt(), (height * k).toInt(), true)
}

fun Bitmap.toJpegBase64(quality: Int = 60): String {
    val bos = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, bos)
    return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
}

/**
 * Служба специальных возможностей: даёт приложению снимок экрана по команде,
 * без диалога разрешения каждый раз. Пользователь включает её один раз.
 */
class NixiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun captureScreen(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        val executor = Executor { r -> Handler(Looper.getMainLooper()).post(r) }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var copy: Bitmap? = null
                        try {
                            val buffer = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            copy = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                        } catch (e: Exception) {
                            copy = null
                        }
                        callback(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            callback(null)
        }
    }

    companion object {
        @Volatile
        var instance: NixiAccessibilityService? = null
            private set
    }
}
