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
import com.freespeak.ime.data.SupportedLanguages
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
    private var btnModelSwitch: TextView? = null
    private var btnLangSwitch: TextView? = null
    private var tvStatus: TextView? = null
    private var waveformView: VoiceWaveformView? = null
    private var btnMic: ImageButton? = null
    private var btnEnter: TextView? = null
    private var punctButtons: List<TextView> = emptyList()

    // Touch / Recording state
    private var isTapSessionRecording = false
    private var isHoldSessionRecording = false
    private var isSlideCancelArmed = false
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
                updateToolbarAndPunctuation()
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
                updateToolbarAndPunctuation()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_voice_panel, null)
        btnModelSwitch = view.findViewById(R.id.btnModelSwitch)
        btnLangSwitch = view.findViewById(R.id.btnLangSwitch)
        tvStatus = view.findViewById(R.id.tvImeStatus)
        waveformView = view.findViewById(R.id.waveformView)
        btnMic = view.findViewById(R.id.btnMic)
        btnEnter = view.findViewById(R.id.btnEnter)

        punctButtons = listOfNotNull(
            view.findViewById(R.id.btnPunct1),
            view.findViewById(R.id.btnPunct2),
            view.findViewById(R.id.btnPunct3),
            view.findViewById(R.id.btnPunct4),
            view.findViewById(R.id.btnPunct5),
            view.findViewById(R.id.btnPunct6),
        )
        punctButtons.forEach { tv ->
            tv.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentInputConnection?.commitText(tv.text, 1)
            }
        }

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

        view.findViewById<View>(R.id.btnSpace)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            currentInputConnection?.commitText(" ", 1)
        }

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

        btnEnter?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performSmartEnter()
        }

        btnMic?.setOnTouchListener { v, event ->
            handleMicTouchEvent(v, event)
        }

        updateToolbarAndPunctuation()
        updateEnterKeyLabel(currentInputEditorInfo)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateEnterKeyLabel(info)
        updateToolbarAndPunctuation()
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
                touchDownY = event.rawY
                isSlideCancelArmed = false
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                when (currentSettings.interactionMode) {
                    RecordInteractionMode.HOLD_ONLY -> {
                        isHoldSessionRecording = true
                        startVoiceCapture(isHoldGesture = true)
                    }
                    RecordInteractionMode.TAP_ONLY -> Unit
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
            tvStatus?.text = startRes.exceptionOrNull()?.message.orEmpty()
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

            val asrLang = currentSettings.effectiveAsrLanguage
            val transcribeResult = app.engineManager.transcribe(
                pcm16k = audioResult.samples,
                preferredModelId = currentSettings.selectedModelId,
                options = AsrInferenceOptions(
                    language = asrLang,
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
                        val commitStr = if (shouldAppendTrailingSpace(text, asrLang)) "$text " else text
                        currentInputConnection?.commitText(commitStr, 1)
                        val donePrefix = getString(R.string.status_done_latency, res.inferenceTimeMs)
                        tvStatus?.text = "$donePrefix · ${defaultIdleHint(currentSettings.interactionMode)}"
                    } else {
                        tvStatus?.setText(R.string.status_no_speech)
                    }
                },
                onFailure = { err ->
                    tvStatus?.text = err.message.orEmpty()
                }
            )
        }
    }

    private fun shouldAppendTrailingSpace(text: String, asrLang: String): Boolean {
        if (text.isEmpty()) return false
        val lastChar = text.last()
        val isAsciiEnd = lastChar.code in 0x21..0x7E
        val hasCjk = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        return isAsciiEnd && !hasCjk && asrLang !in setOf("zh", "yue", "ja")
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
                tvStatus?.text = getString(R.string.status_loading_model, status.modelName)
            }
            is EngineRuntimeStatus.Error -> {
                tvStatus?.text = status.message
            }
            is EngineRuntimeStatus.Ready -> {
                if (!isTranscribingNow) {
                    tvStatus?.text = defaultIdleHint(currentSettings.interactionMode)
                }
            }
            else -> Unit
        }
    }

    private fun updateToolbarAndPunctuation() {
        val installed = app.modelRepository.listInstalledManifests()
        val activeModel = installed.find { it.id == currentSettings.selectedModelId }
            ?: installed.firstOrNull()

        btnModelSwitch?.text = activeModel?.name ?: getString(R.string.ime_no_model_tap)

        val effLang = currentSettings.effectiveAsrLanguage
        btnLangSwitch?.text = if (effLang == "auto") {
            getString(R.string.pref_lang_auto_mixed)
        } else {
            SupportedLanguages.labelFor(effLang)
        }

        val useCjkPunct = when (effLang) {
            "zh", "yue", "ja" -> true
            "auto" -> currentSettings.preferredLanguages.any { it in setOf("zh", "yue", "ja") }
            else -> false
        }
        val symbols = if (useCjkPunct) CJK_PUNCTUATION else LATIN_PUNCTUATION
        punctButtons.forEachIndexed { idx, tv ->
            if (idx < symbols.size) tv.text = symbols[idx]
        }
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
        val preferred = currentSettings.orderedPreferredLanguages
        if (preferred.size <= 1) {
            openMainApp()
            return
        }
        val rotation = listOf("auto") + preferred
        val cur = currentSettings.effectiveAsrLanguage
        val idx = rotation.indexOf(cur)
        val next = rotation[(idx + 1) % rotation.size]
        serviceScope.launch {
            app.preferencesRepository.setSelectedLanguage(next)
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
        val resId = when (action) {
            EditorInfo.IME_ACTION_SEND -> R.string.ime_key_send
            EditorInfo.IME_ACTION_SEARCH -> R.string.ime_key_search
            EditorInfo.IME_ACTION_GO -> R.string.ime_key_go
            EditorInfo.IME_ACTION_NEXT -> R.string.ime_key_next
            EditorInfo.IME_ACTION_DONE -> R.string.ime_key_done
            else -> R.string.ime_key_return
        }
        btnEnter?.setText(resId)
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

    companion object {
        private const val HOLD_DETECTION_MS = 240L
        private const val BACKSPACE_INITIAL_DELAY_MS = 380L
        private const val BACKSPACE_REPEAT_INTERVAL_MS = 55L
        private val CJK_PUNCTUATION = listOf("，", "。", "？", "！", "、", "：")
        private val LATIN_PUNCTUATION = listOf(",", ".", "?", "!", ";", ":")
    }
}
