package com.ps5translator.pro

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var config: AppConfig
    private lateinit var voiceReader: ArabicVoiceReader
    private val testTranslator = GeminiTranslator()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val gamePresets = listOf(
        "GTA 6 (مصطلحات شوارع وحوارات سريعة)",
        "ألعاب قصة وسينمائية عامة",
        "ألعاب أكشن وعالم مفتوح",
        "ألعاب خيال وتاريخي (RPG)"
    )

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svcIntent = Intent(this, ScreenTranslatorService::class.java).apply {
                action = ScreenTranslatorService.ACTION_START
                putExtra(ScreenTranslatorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslatorService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, svcIntent)
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "يجب السماح بالتقاط الشاشة لتشغيل المترجم", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestMediaProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = AppConfig(this)
        voiceReader = ArabicVoiceReader(this).apply {
            enabled = config.ttsEnabled
            speechRate = config.ttsSpeed
        }

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val rgModel = findViewById<RadioGroup>(R.id.rgModel)
        val rbFlashLite = findViewById<RadioButton>(R.id.rbFlashLite)
        val rbFlash = findViewById<RadioButton>(R.id.rbFlash)
        val rgDialect = findViewById<RadioGroup>(R.id.rgDialect)
        val rbOriginal = findViewById<RadioButton>(R.id.rbOriginalMsa)
        val rbIraqi = findViewById<RadioButton>(R.id.rbIraqi)
        val spGamePreset = findViewById<Spinner>(R.id.spGamePreset)
        val swTts = findViewById<SwitchCompat>(R.id.swTtsEnabled)
        val tvTtsSpeed = findViewById<TextView>(R.id.tvTtsSpeed)
        val sbTtsSpeed = findViewById<SeekBar>(R.id.sbTtsSpeed)
        val tvScanInterval = findViewById<TextView>(R.id.tvScanInterval)
        val sbScanInterval = findViewById<SeekBar>(R.id.sbScanInterval)
        val btnTest = findViewById<Button>(R.id.btnTest)
        val tvTestResult = findViewById<TextView>(R.id.tvTestResult)
        val btnStart = findViewById<Button>(R.id.btnStartOverlay)
        val btnStop = findViewById<Button>(R.id.btnStopOverlay)

        etApiKey.setText(config.apiKey)
        etApiKey.doAfterTextChanged { config.apiKey = it?.toString() ?: "" }

        if (config.modelName == "gemini-2.5-flash") rbFlash.isChecked = true else rbFlashLite.isChecked = true
        rgModel.setOnCheckedChangeListener { _, checkedId ->
            config.modelName = if (checkedId == R.id.rbFlash) "gemini-2.5-flash" else "gemini-2.5-flash-lite"
        }

        if (config.dialect == DialectMode.IRAQI) rbIraqi.isChecked = true else rbOriginal.isChecked = true
        rgDialect.setOnCheckedChangeListener { _, checkedId ->
            config.dialect = if (checkedId == R.id.rbIraqi) DialectMode.IRAQI else DialectMode.ORIGINAL_MSA
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, gamePresets)
        spGamePreset.adapter = adapter
        val savedIdx = gamePresets.indexOfFirst { it.startsWith(config.gamePreset) }.coerceAtLeast(0)
        spGamePreset.setSelection(savedIdx)
        spGamePreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                config.gamePreset = gamePresets[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        swTts.isChecked = config.ttsEnabled
        swTts.setOnCheckedChangeListener { _, isChecked ->
            config.ttsEnabled = isChecked
            voiceReader.enabled = isChecked
        }

        val speedProgress = ((config.ttsSpeed - 0.8f) * 10f).toInt().coerceIn(0, 10)
        sbTtsSpeed.progress = speedProgress
        tvTtsSpeed.text = String.format(Locale.US, "سرعة القراءة الصوتية: %.1fx", config.ttsSpeed)
        sbTtsSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = 0.8f + (progress * 0.1f)
                config.ttsSpeed = speed
                voiceReader.speechRate = speed
                tvTtsSpeed.text = String.format(Locale.US, "سرعة القراءة الصوتية: %.1fx", speed)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val scanProgress = ((config.scanIntervalMs - 150L) / 50L).toInt().coerceIn(0, 9)
        sbScanInterval.progress = scanProgress
        tvScanInterval.text = "سرعة فحص الشاشة: كل ${config.scanIntervalMs}ms"
        sbScanInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = 150L + (progress * 50L)
                config.scanIntervalMs = ms
                tvScanInterval.text = "سرعة فحص الشاشة: كل ${ms}ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnTest.setOnClickListener {
            if (config.apiKey.isBlank()) {
                Toast.makeText(this, "لصق مفتاح Gemini API أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = "جاري الاختبار..."
            val sampleSubtitle = "This quy knows what he's doing, keep your head down and move!"
            uiScope.launch(Dispatchers.IO) {
                testTranslator.translateStreaming(
                    apiKey = config.apiKey,
                    model = config.modelName,
                    englishText = sampleSubtitle,
                    dialect = config.dialect,
                    gamePreset = config.gamePreset,
                    onPartial = { partial ->
                        launch(Dispatchers.Main) { tvTestResult.text = partial }
                    },
                    onComplete = { finalAr, ms ->
                        launch(Dispatchers.Main) {
                            tvTestResult.text = "$finalAr\n\n⚡ السرعة: ${ms}ms (تم تصحيح خطأ quy تلقائياً)"
                            voiceReader.speak(finalAr)
                        }
                    },
                    onError = { err ->
                        launch(Dispatchers.Main) { tvTestResult.text = "❌ $err" }
                    }
                )
            }
        }

        btnStart.setOnClickListener {
            if (config.apiKey.isBlank()) {
                Toast.makeText(this, "أدخل مفتاح Gemini API أولاً", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "فعّل إذن الظهور فوق التطبيقات ثم ارجع", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestMediaProjection()
            }
        }

        btnStop.setOnClickListener {
            val stopIntent = Intent(this, ScreenTranslatorService::class.java).apply {
                action = ScreenTranslatorService.ACTION_STOP
            }
            startService(stopIntent)
            Toast.makeText(this, "تم إيقاف المترجم", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        voiceReader.release()
    }
}
