package com.nagibatorz.nixi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/**
 * Капсула Никси: маленькое окно поверх любых приложений.
 * Тап — слушаю, долгое нажатие — свернуть/раскрыть панель ответа.
 */
class CapsuleService : Service() {

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var capsuleText: TextView? = null
    private var dot: View? = null
    private var panel: LinearLayout? = null
    private var answer: TextView? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val history = mutableListOf<Pair<String, String>>()
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false
    private var listening = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startAsForeground()
        initTts()
        ui.post {
            createRecognizer()
            buildOverlay()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (e: Exception) {
        }
        val view = root
        if (view != null) {
            try {
                wm?.removeView(view)
            } catch (e: Exception) {
            }
        }
        root = null
        super.onDestroy()
    }

    // ---------- уведомление и голос ----------

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                stopSelf()
            }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Никси", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Капсула Никси на экране"
            nm.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Никси на связи")
            .setContentText("Тапни капсулу сверху и говори")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun initTts() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    engine?.language = Locale("ru", "RU")
                } catch (e: Exception) {
                }
            }
        }
        tts = engine
    }

    private fun speak(text: String) {
        if (!Prefs.voice(this)) return
        val engine = tts ?: return
        val clean = text
            .replace(Regex("[*_#`>~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(700)
        if (clean.isBlank()) return
        try {
            engine.language = Locale("ru", "RU")
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "nixi")
        } catch (e: Exception) {
        }
    }

    // ---------- окно поверх экрана ----------

    private fun buildOverlay() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val cap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#0B0B0C"))
                setStroke(dp(1), Color.parseColor("#2A2A2E"))
            }
        }

        val dotView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#8A8A90"))
            }
        }
        cap.addView(dotView, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })

        val label = TextView(this).apply {
            text = "Никси"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        cap.addView(label)

        val panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#121215"))
                setStroke(dp(1), Color.parseColor("#2A2A2E"))
            }
        }

        val answerView = TextView(this).apply {
            width = dp(250)
            setTextColor(Color.parseColor("#EDEDF0"))
            textSize = 13f
            maxLines = 14
            text = ""
        }
        panelView.addView(answerView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("🎙 Слушать") { startListening() }, chipLp())
        row.addView(chip("🖥 Экран") { analyzeScreen() }, chipLp())
        row.addView(chip("⚙") { openSettings() }, chipLp())
        row.addView(chip("✕") { stopSelf() }, chipLp())
        panelView.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        container.addView(cap)
        container.addView(
            panelView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        cap.setOnClickListener { startListening() }
        cap.setOnLongClickListener {
            togglePanel()
            true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }

        try {
            manager.addView(container, params)
        } catch (e: Exception) {
            stopSelf()
            return
        }

        root = container
        capsuleText = label
        dot = dotView
        panel = panelView
        answer = answerView
    }

    private fun chip(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.setPadding(dp(12), dp(6), dp(12), dp(6))
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#1F1F24"))
            setStroke(dp(1), Color.parseColor("#35353C"))
        }
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun chipLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { rightMargin = dp(8) }

    private fun refreshLayout() {
        val view = root ?: return
        val manager = wm ?: return
        try {
            manager.updateViewLayout(view, view.layoutParams)
        } catch (e: Exception) {
        }
    }

    private fun openPanel() {
        panel?.visibility = View.VISIBLE
        refreshLayout()
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        refreshLayout()
    }

    private fun setStatus(text: String, colorHex: String) {
        ui.post {
            capsuleText?.text = if (text.length > 26) text.take(26) + "…" else text
            dot?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
        }
    }

    private fun showAnswer(text: String) {
        ui.post {
            answer?.text = text
            openPanel()
        }
    }

    private fun reply(text: String) {
        showAnswer(text)
        speak(text)
    }

    private fun openSettings() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            showAnswer("Не смог открыть настройки — открой приложение Никси вручную.")
        }
    }

    // ---------- распознавание речи ----------

    private fun createRecognizer() {
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(recognitionListener)
            }
        } catch (e: Exception) {
            recognizer = null
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            setStatus("Слушаю…", "#FF7A18")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listening = false
            setStatus("Думаю…", "#FFB347")
        }

        override fun onError(error: Int) {
            listening = false
            busy = false
            setStatus("Никси", "#8A8A90")
            if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                error != SpeechRecognizer.ERROR_CLIENT
            ) {
                showAnswer("Не расслышал. Нажми на капсулу и скажи ещё раз.")
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isBlank()) {
                setStatus("Никси", "#8A8A90")
                return
            }
            handleCommand(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) setStatus(text, "#FF7A18")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun startListening() {
        if (busy) return
        try {
            tts?.stop()
        } catch (e: Exception) {
        }
        if (!hasMic()) {
            showAnswer("Нужен доступ к микрофону — включи его в приложении Никси.")
            return
        }
        if (recognizer == null) createRecognizer()
        val r = recognizer
        if (r == null) {
            showAnswer("Микрофон недоступен на этом телефоне.")
            return
        }
        if (listening) {
            try {
                r.stopListening()
            } catch (e: Exception) {
            }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            r.startListening(intent)
            openPanel()
            setStatus("Слушаю…", "#FF7A18")
        } catch (e: Exception) {
            showAnswer("Не удалось включить микрофон: ${e.message}")
        }
    }

    // ---------- команды ----------

    private fun handleCommand(raw: String) {
        val t = raw.lowercase(Locale("ru", "RU")).trim()
        when {
            t.contains("замолчи") || t.contains("стоп") -> {
                try {
                    tts?.stop()
                } catch (e: Exception) {
                }
                setStatus("Молчу", "#8A8A90")
            }

            t.contains("привет") || t.contains("здравствуй") || t == "хай" ->
                reply("Привет, Илюха! Я на связи. Что нужно?")

            (t.contains("котор") && t.contains("час")) || t.contains("сколько времени") ->
                reply(nowText())

            t.contains("экран") -> analyzeScreen()

            t.contains("открой") -> openApp(t)

            else -> askAi(raw, null)
        }
    }

    private fun nowText(): String {
        val cal = Calendar.getInstance()
        val hh = String.format(Locale.US, "%02d", cal.get(Calendar.HOUR_OF_DAY))
        val mm = String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))
        return "Сейчас $hh:$mm"
    }

    private fun openApp(t: String) {
        val targets = listOf(
            Triple(listOf("ютуб", "youtube"), "com.google.android.youtube", "Ютуб"),
            Triple(listOf("рутуб", "rutube"), "ru.rutube.app", "Рутуб"),
            Triple(listOf("вконтакте", "вк"), "com.vk.android.mobile", "ВКонтакте"),
            Triple(listOf("телеграм", "telegram"), "org.telegram.messenger", "Телеграм")
        )
        for ((words, pkg, name) in targets) {
            if (words.any { t.contains(it) }) {
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null) {
                    reply("$name не установлен на телефоне.")
                    return
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(intent)
                    reply("Открываю $name.")
                } catch (e: Exception) {
                    reply("Не смог открыть $name.")
                }
                return
            }
        }
        reply("Не понял, какое приложение открыть.")
    }

    private fun analyzeScreen() {
        val service = NixiAccessibilityService.instance
        if (service == null) {
            reply("Чтобы я видел экран, включи в настройках телефона службу «Nixi — анализ экрана». Это кнопка «Анализ экрана» в приложении Никси.")
            return
        }
        setStatus("Смотрю экран…", "#FFB347")
        service.captureScreen { bitmap ->
            if (bitmap == null) {
                reply("Не получилось снять экран. Проверь, что служба «Nixi — анализ экрана» включена.")
                return@captureScreen
            }
            val base64 = bitmap.scaledTo(1024).toJpegBase64(60)
            askAi(
                "Проанализируй, что на экране. Скажи коротко: что это за экран и что тут можно сделать.",
                base64
            )
        }
    }

    private fun askAi(text: String, imageBase64: String?) {
        val key = Prefs.apiKey(this)
        if (key.isBlank()) {
            reply("Сначала открой приложение Никси и вставь ключ OpenRouter.")
            return
        }
        val model = if (imageBase64 != null) Prefs.visionModel(this) else Prefs.textModel(this)
        val snapshot = ArrayList(history)
        snapshot.add("user" to text)
        busy = true
        setStatus("Думаю…", "#FFB347")

        Ai.ask(
            apiKey = key,
            model = model,
            system = Ai.SYSTEM_PROMPT,
            history = snapshot,
            imageBase64 = imageBase64,
            onResult = { answerText ->
                ui.post {
                    history.add("user" to text)
                    history.add("assistant" to answerText)
                    while (history.size > 12) {
                        history.removeAt(0)
                    }
                    busy = false
                    setStatus("Никси", "#8A8A90")
                    showAnswer(answerText)
                    speak(answerText)
                }
            },
            onError = { error ->
                ui.post {
                    busy = false
                    setStatus("Ошибка", "#FF5A5A")
                    showAnswer("Ошибка: $error")
                }
            }
        )
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "nixi_capsule"
        private const val NOTIF_ID = 101
    }
}
