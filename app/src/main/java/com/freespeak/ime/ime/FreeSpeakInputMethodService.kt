package com.freespeak.ime.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.freespeak.ime.FreeSpeakApplication
import com.freespeak.ime.R
import com.freespeak.ime.audio.AudioRecorder
import com.freespeak.ime.data.RecordInteractionMode
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.AsrInferenceOptions
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Pure-voice offline Android InputMethodService.
 * Strictly zero network requests inside the IME service flow — all audio stays in local RAM.
 */
class FreeSpeakInputMethodService : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var audioRecorder: AudioRecorder
    private var currentSettings = UserSettings()

    // Views
    private var rootContainer: View? = null
    private var btnModelSwitch: TextView? = null
    private var btnLangSwitch: TextView? = null
    private var tvStatus: TextView? = null
    private var waveformView: VoiceWaveformView? = null
    private var btnMic: ImageButton? = null
    private var btnEnter: TextView? = null

    // Touch / Recording state
    private var isTapSessionRecording = false
    private var isHoldSessionRecording = false
    private var isSlideCancelArmed = false
    private var touchDownTimeMs = 0L
    private var touchDownY = 0f
    private var isTranscribingNow = false

    private val holdTriggerRunnable = Runnable {
        if (!isTapSessionRecording && !isHoldSessionRecording && !isTranscribingNow) {
            if (currentSettings.interactionMode != RecordInteractionMode.TAP_ONLY) {
                isHoldSessionRecording = true
                startVoiceCapture(isHoldGesture = true)
            }
        }
    }

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            performBackspaceOnce()
            mainHandler.postDelayed(this, BACKSPACE_REPEAT_INTERVAL_MS)
        }
    }

    private val app: FreeSpeakApplication
        get() = application as FreeSpeakApplication

    override fun onCreate() {
        super.onCreate()
        audioRecorder = AudioRecorder(this)

        serviceScope.launch {
            app.preferencesRepository.settingsFlow.collect { settings ->
                currentSettings = settings
                updateToolbarLabels()
                if (!isTapSessionRecording && !isHoldSessionRecording && !isTranscribingNow) {
                    tvStatus?.text = defaultIdleHint(settings.interactionMode)
                }
            }
        }

        serviceScope.launch {
            combine(
                audioRecorder.rmsLevel,
                audioRecorder.isSpeechDetected,
            ) { rms, speech -> rms to speech }.collect { (rms, speech) ->
                waveformView?.pushAudioFrame(rms, speech)
            }
        }

        serviceScope.launch {
            app.engineManager.status.collect { status ->
                updateEngineStatusUi(status)
            }
        }

        serviceScope.launch {
            app.modelRepository.models.collect {
                updateToolbarLabels()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_panel, null)
        rootContainer = view.findViewById(R.id.imeRootContainer)
        btnModelSwitch = view.findViewById(R.id.btnModelSwitch)
        btnLangSwitch = view.findViewById(R.id.btnLangSwitch)
        tvStatus = view.findViewById(R.id.tvImeStatus)
        waveformView = view.findViewById(R.id.waveformView)
        btnMic = view.findViewById(R.id.btnMic)
        btnEnter = view.findViewById(R.id.btnEnter)

        val baseBottomPad = (10 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, baseBottomPad + navBottom)
            insets
        }

        btnModelSwitch?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cycleInstalledModelOrOpenApp()
        }

        btnLangSwitch?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cycleLanguage()
        }

        view.findViewById<View>(R.id.btnOpenSettings)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openMainApp()
        }

        // Quick punctuation buttons
        bindPunctuationKey(view, R.id.btnPunctComma, "，")
        bindPunctuationKey(view, R.id.btnPunctPeriod, "。")
        bindPunctuationKey(view, R.id.btnPunctQuestion, "？")
        bindPunctuationKey(view, R.id.btnPunctExclaim, "！")
        bindPunctuationKey(view, R.id.btnPunctEnum, "、")
        bindPunctuationKey(view, R.id.btnPunctColon, "：")

        // Switch keyboard button
        view.findViewById<View>(R.id.btnSwitchIme)?.apply {
            setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                switchToPreviousOrPicker()
            }
            setOnLongClickListener {
                showImePicker()
                true
            }
        }

        // Space key
        view.findViewById<View>(R.id.btnSpace)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            currentInputConnection?.commitText(" ", 1)
        }

        // Auto-repeating Backspace key
        view.findViewById<View>(R.id.btnBackspace)?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    performBackspaceOnce()
                    mainHandler.removeCallbacks(backspaceRepeatRunnable)
                    mainHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_INITIAL_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    mainHandler.removeCallbacks(backspaceRepeatRunnable)
                    true
                }
                else -> false
            }
        }

        // Smart Enter / Action key
        btnEnter?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performSmartEnter()
        }

        // Central Microphone button (Tap + Hold + Slide-up-to-cancel)
        btnMic?.setOnTouchListener { v, event ->
            handleMicTouchEvent(v, event)
        }

        updateToolbarLabels()
        updateEnterKeyLabel(currentInputEditorInfo)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateEnterKeyLabel(info)
        updateToolbarLabels()
        app.engineManager.onImeShown(
            preferredModelId = currentSettings.selectedModelId,
            numThreads = currentSettings.cpuThreads,
        )
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isTapSessionRecording || isHoldSessionRecording) {
            serviceScope.launch {
                audioRecorder.cancel()
                resetMicVisualState()
            }
        }
        app.engineManager.onImeHidden(currentSettings.idleUnloadMinutes)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        audioRecorder.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleMicTouchEvent(view: View, event: MotionEvent): Boolean {
        if (isTranscribingNow) return true
        val cancelSlideThresholdPx = 56f * resources.displayMetrics.density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTimeMs = System.currentTimeMillis()
                touchDownY = event.rawY
                isSlideCancelArmed = false
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                when (currentSettings.interactionMode) {
                    RecordInteractionMode.HOLD_ONLY -> {
                        isHoldSessionRecording = true
                        startVoiceCapture(isHoldGesture = true)
                    }
                    RecordInteractionMode.TAP_ONLY -> {
                        // Handled cleanly on ACTION_UP
                    }
                    RecordInteractionMode.HYBRID -> {
                        if (!isTapSessionRecording) {
                            mainHandler.removeCallbacks(holdTriggerRunnable)
                            mainHandler.postDelayed(holdTriggerRunnable, HOLD_DETECTION_MS)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isHoldSessionRecording) {
                    val dy = event.rawY - touchDownY
                    val shouldCancel = dy < -cancelSlideThresholdPx
                    if (shouldCancel != isSlideCancelArmed) {
                        isSlideCancelArmed = shouldCancel
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        if (shouldCancel) {
                            btnMic?.setBackgroundResource(R.drawable.bg_mic_cancel)
                            tvStatus?.setText(R.string.status_slide_cancel)
                        } else {
                            btnMic?.setBackgroundResource(R.drawable.bg_mic_recording)
                            tvStatus?.setText(R.string.status_recording_hold)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(holdTriggerRunnable)
                if (isHoldSessionRecording) {
                    val cancelNow = isSlideCancelArmed
                    isHoldSessionRecording = false
                    isSlideCancelArmed = false
                    if (cancelNow) {
                        cancelCaptureSession()
                    } else {
                        finishCaptureAndTranscribe()
                    }
                } else {
                    // Short tap!
                    if (currentSettings.interactionMode == RecordInteractionMode.HOLD_ONLY) {
                        tvStatus?.setText(R.string.status_idle_hold)
                    } else if (isTapSessionRecording) {
                        isTapSessionRecording = false
                        finishCaptureAndTranscribe()
                    } else {
                        isTapSessionRecording = true
                        startVoiceCapture(isHoldGesture = false)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(holdTriggerRunnable)
                if (isHoldSessionRecording) {
                    isHoldSessionRecording = false
                    isSlideCancelArmed = false
                    cancelCaptureSession()
                }
                return true
            }
        }
        return false
    }

    private fun startVoiceCapture(isHoldGesture: Boolean) {
        if (!hasRecordPermission()) {
            isTapSessionRecording = false
            isHoldSessionRecording = false
            tvStatus?.setText(R.string.status_need_permission)
            openMainApp()
            return
        }

        val installedModels = app.modelRepository.listInstalledManifests()
        if (installedModels.isEmpty()) {
            isTapSessionRecording = false
            isHoldSessionRecording = false
            tvStatus?.setText(R.string.status_need_model)
            openMainApp()
            return
        }

        val startRes = audioRecorder.start(
            scope = serviceScope,
            vadEnabled = currentSettings.vadFilterEnabled,
            vadThreshold = currentSettings.vadThreshold,
        )
        if (startRes.isFailure) {
            isTapSessionRecording = false
            isHoldSessionRecording = false
            tvStatus?.text = startRes.exceptionOrNull()?.message ?: "无法启动麦克风"
            return
        }

        btnMic?.setBackgroundResource(R.drawable.bg_mic_recording)
        btnMic?.setImageResource(R.drawable.ic_stop_rounded)
        waveformView?.setVisualMode(VoiceWaveformView.VisualMode.LISTENING)
        tvStatus?.setText(
            if (isHoldGesture) R.string.status_recording_hold else R.string.status_recording_tap
        )
    }

    private fun cancelCaptureSession() {
        serviceScope.launch {
            audioRecorder.cancel()
            resetMicVisualState()
        }
    }

    private fun finishCaptureAndTranscribe() {
        serviceScope.launch {
            isTranscribingNow = true
            btnMic?.setBackgroundResource(R.drawable.bg_mic_idle)
            btnMic?.setImageResource(R.drawable.ic_mic)
            waveformView?.setVisualMode(VoiceWaveformView.VisualMode.TRANSCRIBING)
            tvStatus?.setText(R.string.status_transcribing)

            val audioResult = audioRecorder.stop()
            if (!audioResult.hasVoiceActivity || audioResult.samples.isEmpty()) {
                isTranscribingNow = false
                waveformView?.setVisualMode(VoiceWaveformView.VisualMode.IDLE)
                tvStatus?.setText(R.string.status_no_speech)
                return@launch
            }

            val transcribeResult = app.engineManager.transcribe(
                pcm16k = audioResult.samples,
                preferredModelId = currentSettings.selectedModelId,
                options = AsrInferenceOptions(
                    language = currentSettings.selectedLanguage,
                    numThreads = currentSettings.cpuThreads,
                    enableStylePrompt = currentSettings.whisperStylePromptEnabled,
                )
            )

            isTranscribingNow = false
            waveformView?.setVisualMode(VoiceWaveformView.VisualMode.IDLE)

            transcribeResult.fold(
                onSuccess = { res ->
                    val text = res.text.trim()
                    if (text.isNotEmpty()) {
                        currentInputConnection?.commitText(text, 1)
                        tvStatus?.text = "已输入 (${res.inferenceTimeMs}ms) · ${defaultIdleHint(currentSettings.interactionMode)}"
                    } else {
                        tvStatus?.setText(R.string.status_no_speech)
                    }
                },
                onFailure = { err ->
                    tvStatus?.text = err.message ?: "转写失败"
                }
            )
        }
    }

    private fun resetMicVisualState() {
        isTapSessionRecording = false
        isHoldSessionRecording = false
        isSlideCancelArmed = false
        isTranscribingNow = false
        btnMic?.setBackgroundResource(R.drawable.bg_mic_idle)
        btnMic?.setImageResource(R.drawable.ic_mic)
        waveformView?.setVisualMode(VoiceWaveformView.VisualMode.IDLE)
        tvStatus?.text = defaultIdleHint(currentSettings.interactionMode)
    }

    private fun updateEngineStatusUi(status: EngineRuntimeStatus) {
        if (isTapSessionRecording || isHoldSessionRecording) return
        when (status) {
            is EngineRuntimeStatus.Loading -> {
                tvStatus?.text = "正在加载 ${status.modelName}…"
            }
            is EngineRuntimeStatus.Error -> {
                tvStatus?.text = status.message
            }
            is EngineRuntimeStatus.Ready -> {
                if (!isTranscribingNow) {
                    tvStatus?.text = defaultIdleHint(currentSettings.interactionMode)
                }
            }
            else -> {}
        }
    }

    private fun updateToolbarLabels() {
        val installed = app.modelRepository.listInstalledManifests()
        val activeModel = installed.find { it.id == currentSettings.selectedModelId }
            ?: installed.firstOrNull()

        btnModelSwitch?.text = if (activeModel != null) {
            "模型: ${activeModel.name.substringBefore('(').trim()}"
        } else {
            "未安装模型 · 点击下载"
        }

        btnLangSwitch?.text = languageDisplayLabel(currentSettings.selectedLanguage)
    }

    private fun cycleInstalledModelOrOpenApp() {
        val installed = app.modelRepository.listInstalledManifests()
        if (installed.isEmpty()) {
            openMainApp()
            return
        }
        val currentIdx = installed.indexOfFirst { it.id == currentSettings.selectedModelId }
        val next = installed[(currentIdx + 1) % installed.size]
        serviceScope.launch {
            app.preferencesRepository.setSelectedModelId(next.id)
            app.engineManager.ensureModelLoaded(next.id, currentSettings.cpuThreads)
        }
    }

    private fun cycleLanguage() {
        val idx = LANGUAGE_ROTATION.indexOf(currentSettings.selectedLanguage)
        val next = LANGUAGE_ROTATION[(idx + 1) % LANGUAGE_ROTATION.size]
        serviceScope.launch {
            app.preferencesRepository.setSelectedLanguage(next)
        }
    }

    private fun bindPunctuationKey(root: View, viewId: Int, symbol: String) {
        root.findViewById<View>(viewId)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            currentInputConnection?.commitText(symbol, 1)
        }
    }

    private fun performBackspaceOnce() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
    }

    private fun updateEnterKeyLabel(info: EditorInfo?) {
        val action = extractImeAction(info)
        btnEnter?.text = when (action) {
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
    }

    private fun performSmartEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = extractImeAction(info)
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            if (!ic.performEditorAction(action)) {
                ic.commitText("\n", 1)
            }
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun extractImeAction(info: EditorInfo?): Int {
        if (info == null) return EditorInfo.IME_ACTION_NONE
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (noEnterAction) return EditorInfo.IME_ACTION_NONE
        return info.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    private fun switchToPreviousOrPicker() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            false
        }
        if (!switched) {
            showImePicker()
        }
    }

    private fun showImePicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun defaultIdleHint(mode: RecordInteractionMode): String {
        return when (mode) {
            RecordInteractionMode.HYBRID -> getString(R.string.status_idle_hybrid)
            RecordInteractionMode.TAP_ONLY -> getString(R.string.status_idle_tap)
            RecordInteractionMode.HOLD_ONLY -> getString(R.string.status_idle_hold)
        }
    }

    private fun languageDisplayLabel(code: String): String {
        return when (code) {
            "auto" -> "自动混输"
            "zh" -> "中文"
            "en" -> "English"
            "yue" -> "粵語"
            "ja" -> "日本語"
            "ko" -> "한국어"
            else -> code.uppercase()
        }
    }

    companion object {
        private const val HOLD_DETECTION_MS = 240L
        private const val BACKSPACE_INITIAL_DELAY_MS = 380L
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 55L
        private val LANGUAGE_ROTATION = listOf("auto", "zh", "en", "yue", "ja", "ko")
    }
}
