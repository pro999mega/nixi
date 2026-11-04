package com.nagibatorz.nixi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var keyEt: EditText
    private lateinit var textModelEt: EditText
    private lateinit var visionModelEt: EditText
    private lateinit var voiceCb: CheckBox
    private lateinit var statusTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- интерфейс ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(headline("Никси"))
        root.addView(teaser())

        root.addView(section("1. Ключ и модели"))
        root.addView(small("Ключ OpenRouter — хранится только в телефоне"))
        keyEt = input(Prefs.apiKey(this), "sk-or-v1-…")
        root.addView(keyEt, topGap(6))

        root.addView(small("Модель для разговора"))
        textModelEt = input(Prefs.textModel(this), Prefs.DEFAULT_TEXT_MODEL)
        root.addView(textModelEt, topGap(6))

        root.addView(small("Модель для анализа экрана — нужна со зрением"))
        visionModelEt = input(Prefs.visionModel(this), Prefs.DEFAULT_VISION_MODEL)
        root.addView(visionModelEt, topGap(6))

        voiceCb = CheckBox(this).apply {
            text = "Отвечать голосом"
            setTextColor(Color.WHITE)
            isChecked = Prefs.voice(this@MainActivity)
        }
        root.addView(voiceCb, topGap(10))

        root.addView(action("Сохранить") { save() }, topGap(12))

        root.addView(section("2. Разрешения"))
        statusTv = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B9B9C0"))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        root.addView(statusTv, topGap(8))
        root.addView(action("Поверх других приложений") { openOverlaySettings() }, topGap(10))
        root.addView(action("Доступ к микрофону") { askMic() }, topGap(8))
        root.addView(action("Анализ экрана (спец. возможности)") { openAccessibility() }, topGap(8))

        root.addView(section("3. Запуск"))
        root.addView(action("▶  Запустить капсулу") { launchCapsule() }, topGap(10))
        root.addView(action("■  Остановить") { stopCapsule() }, topGap(8))
        root.addView(small("После запуска приложение свернётся, а сверху появится чёрная капсула «Никси». Тап по капсуле — слушаю. Долгое нажатие — свернуть/раскрыть панель ответа."), topGap(12))
    }

    private fun headline(t: String) = TextView(this).apply {
        text = t
        textSize = 28f
        setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun teaser() = TextView(this).apply {
        text = "Голосовой ассистент-капсула: висит поверх любых приложений, слушает, отвечает голосом и смотрит на экран."
        textSize = 13f
        setTextColor(Color.parseColor("#9A9AA2"))
        setPadding(0, dp(6), 0, 0)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTextColor(Color.parseColor("#FF7A18"))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun small(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Color.parseColor("#9A9AA2"))
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun input(value: String, hintText: String) = EditText(this).apply {
        setText(value)
        hint = hintText
        textSize = 14f
        setSingleLine(true)
    }

    private fun action(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun topGap(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    // ---------- действия ----------

    private fun save() {
        Prefs.setApiKey(this, keyEt.text.toString())
        Prefs.setTextModel(this, textModelEt.text.toString())
        Prefs.setVisionModel(this, visionModelEt.text.toString())
        Prefs.setVoice(this, voiceCb.isChecked)
        toast("Сохранено")
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "✅ разрешено" else "❌ нет"
        val mic = if (hasMic()) "✅ разрешён" else "❌ нет"
        val access = if (NixiAccessibilityService.instance != null) "✅ включён" else "❌ выключен"
        statusTv.text = "Поверх других приложений: $overlay\n" +
            "Микрофон: $mic\n" +
            "Анализ экрана: $access\n" +
            "Ключ: " + if (Prefs.apiKey(this).isBlank()) "❌ не введён" else "✅ введён"
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            toast("Не удалось открыть настройки: ${e.message}")
        }
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Найди в списке «Nixi — анализ экрана» и включи")
        } catch (e: Exception) {
            toast("Не удалось открыть настройки: ${e.message}")
        }
    }

    private fun askMic() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 21)
        }
    }

    private fun launchCapsule() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала разреши «Поверх других приложений»")
            openOverlaySettings()
            return
        }
        if (!hasMic()) {
            toast("Сначала разреши микрофон")
            askMic()
            return
        }
        if (Prefs.apiKey(this).isBlank()) {
            toast("Введи ключ OpenRouter и нажми «Сохранить»")
            return
        }
        try {
            startForegroundService(Intent(this, CapsuleService::class.java))
            toast("Капсула запущена — смотри вверх экрана")
            moveTaskToBack(true)
        } catch (e: Exception) {
            toast("Не удалось запустить: ${e.message}")
        }
    }

    private fun stopCapsule() {
        try {
            stopService(Intent(this, CapsuleService::class.java))
            toast("Капсула остановлена")
        } catch (e: Exception) {
            toast("Не удалось остановить: ${e.message}")
        }
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }
}
