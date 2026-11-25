package com.freespeak.ime.ime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
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
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.widget.ImageViewCompat
import com.freespeak.ime.FreeSpeakApplication
import com.freespeak.ime.R
import com.freespeak.ime.audio.AudioRecorder
import com.freespeak.ime.data.RecordInteractionMode
import com.freespeak.ime.model.canTranslateToEnglish
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.AsrInferenceOptions
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.ui.MainActivity
import com.freespeak.ime.ui.MainTab
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
    private var imeRoot: View? = null
    private var punctRow: View? = null
    private var bottomRow: View? = null
    private var btnUndo: ImageButton? = null
    private var btnRedo: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnLang: TextView? = null
    private var btnTargetIme: ImageButton? = null
    private var btnCancelRecording: ImageButton? = null
    private var btnSpace: TextView? = null
    private var btnBackspace: ImageButton? = null
    private var micAnimator: ValueAnimator? = null
    private var translateAvailable = false
    private var activePalette: ImePanelPalette? = null

    // Undo / redo of text that was inserted by speech recognition
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastTextUsesCjk: Boolean? = null
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
                applyPanelTheme()
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
        imeRoot = view.findViewById(R.id.imeRootContainer)
        punctRow = view.findViewById(R.id.punctRow)
        bottomRow = view.findViewById(R.id.bottomRow)
        btnUndo = view.findViewById(R.id.btnUndo)
        btnRedo = view.findViewById(R.id.btnRedo)
        btnSettings = view.findViewById(R.id.btnOpenSettings)
        btnLang = view.findViewById(R.id.btnLang)
        btnTargetIme = view.findViewById(R.id.btnTargetIme)
        btnCancelRecording = view.findViewById(R.id.btnCancelRecording)
        btnSpace = view.findViewById(R.id.btnSpace)
        btnBackspace = view.findViewById(R.id.btnBackspace)
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
            view.findViewById(R.id.btnPunct7),
            view.findViewById(R.id.btnPunct8),
        )
        punctButtons.forEach { tv ->
            tv.setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                clearUndoHistory()
                currentInputConnection?.commitText(tv.text, 1)
            }
        }

        val baseBottomPad = view.paddingBottom
        val canvasHeight = view.findViewById<View>(R.id.panelCanvas).layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            // Navigation bar visibility can change during the IME show animation. Reserve its
            // full space even in those intermediate frames, rather than shrinking the panel.
            val navBottom = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomPadding = baseBottomPad + navBottom
            val panelHeight = canvasHeight + v.paddingTop + bottomPadding
            val lp = v.layoutParams
            if (v.paddingBottom != bottomPadding || lp.height != panelHeight) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadding)
                lp.height = panelHeight
                v.layoutParams = lp
                // Insets may arrive during an existing layout pass. Ask the IME frame to
                // measure again on the next turn so the bottom row cannot remain clipped.
                v.post {
                    if (v === imeRoot && v.isAttachedToWindow) {
                        v.requestLayout()
                        v.parent?.requestLayout()
                    }
                }
            }
            insets
        }
        view.doOnAttach { ViewCompat.requestApplyInsets(it) }

        btnUndo?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performUndo()
        }
        btnRedo?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            performRedo()
        }
        btnLang?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val enable = !currentSettings.translateToEnglish
            serviceScope.launch { app.preferencesRepository.setTranslateToEnglish(enable) }
        }
        btnTargetIme?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            switchToTargetIme()
        }
        btnCancelRecording?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (isTapSessionRecording || isHoldSessionRecording) {
                mainHandler.removeCallbacks(holdTriggerRunnable)
                cancelCaptureSession()
            }
        }

        view.findViewById<View>(R.id.btnOpenSettings)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openMainApp(MainTab.SETTINGS)
        }

        view.findViewById<View>(R.id.btnSpace)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            clearUndoHistory()
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

        applyPanelTheme()
        updateToolbarAndPunctuation()
        updateEnterKeyLabel(currentInputEditorInfo)
        updatePanelMode(animate = false)
        return view
    }

    private fun currentPalette(): ImePanelPalette {
        return activePalette ?: ImePanelTheme.resolvePalette(this, currentSettings.imeColorScheme)
            .also { activePalette = it }
    }

    private fun applyPanelTheme() {
        val palette = ImePanelTheme.resolvePalette(this, currentSettings.imeColorScheme)
        activePalette = palette
        val density = resources.displayMetrics.density

        imeRoot?.setBackgroundColor(palette.panelBg)
        tvStatus?.setTextColor(palette.statusText)

        punctButtons.forEach { tv ->
            tv.background = ImePanelTheme.pillKeyDrawable(palette.keyBg, palette.keyRipple, density)
            tv.setTextColor(palette.keyFg)
        }
        btnSpace?.apply {
            background = ImePanelTheme.pillKeyDrawable(palette.keyBg, palette.keyRipple, density)
            setTextColor(palette.keyFg)
        }
        btnEnter?.apply {
            background = ImePanelTheme.pillKeyDrawable(palette.keyBg, palette.keyRipple, density)
            setTextColor(palette.keyFg)
        }
        btnBackspace?.apply {
            background = ImePanelTheme.pillKeyDrawable(palette.keyBg, palette.keyRipple, density)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(palette.keyFg))
        }

        val roundFgTint = ColorStateList.valueOf(palette.roundKeyFg)
        listOfNotNull(btnUndo, btnRedo, btnSettings, btnTargetIme, btnCancelRecording).forEach { btn ->
            btn.background = ImePanelTheme.ovalKeyDrawable(palette.roundKeyBg, palette.keyRipple)
            ImageViewCompat.setImageTintList(btn, roundFgTint)
        }

        applyLangButtonStyle(palette)
        applyMicStyle(palette)
        applyNavBarIconTint(palette)
    }

    private fun applyLangButtonStyle(palette: ImePanelPalette = currentPalette()) {
        val translating = translateAvailable && currentSettings.translateToEnglish
        btnLang?.apply {
            background = if (translating) {
                ImePanelTheme.ovalKeyDrawable(palette.micBg, palette.micRipple)
            } else {
                ImePanelTheme.ovalKeyDrawable(palette.roundKeyBg, palette.keyRipple)
            }
            setTextColor(if (translating) palette.micFg else palette.roundKeyFg)
        }
    }

    private fun applyMicStyle(palette: ImePanelPalette = currentPalette()) {
        val density = resources.displayMetrics.density
        val bg = if (isSlideCancelArmed) palette.micCancelBg else palette.micBg
        val fg = if (isSlideCancelArmed) palette.micCancelFg else palette.micFg
        btnMic?.apply {
            background = ImePanelTheme.micDrawable(bg, palette.micRipple, density)
            ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(fg))
        }
        waveformView?.setForegroundColor(fg)
    }

    /** Match system nav icons (hide / IME switcher) to actual panel luminance and remove nav scrim. */
    private fun applyNavBarIconTint(palette: ImePanelPalette = currentPalette()) {
        val w = window?.window ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.isNavigationBarContrastEnforced = false
        }
        val isLightPanel = ColorUtils.calculateLuminance(palette.panelBg) > 0.5
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = isLightPanel
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPanelTheme()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyPanelTheme()
        updateEnterKeyLabel(info)
        updateToolbarAndPunctuation()
        app.engineManager.onImeShown(
            preferredModelId = currentSettings.selectedModelId,
            numThreads = currentSettings.cpuThreads,
        )
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applyNavBarIconTint()
        // The input view is reused across editors and IME switches; refresh after the window
        // is visible so we do not retain the previous window's inset/measurement state.
        imeRoot?.let { root ->
            root.post {
                if (root === imeRoot && root.isAttachedToWindow) {
                    ViewCompat.requestApplyInsets(root)
                    root.requestLayout()
                    root.parent?.requestLayout()
                }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) clearUndoHistory()
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
                            tvStatus?.setText(R.string.status_slide_cancel)
                        } else {
                            tvStatus?.setText(R.string.status_recording_hold)
                        }
                        applyMicStyle()
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
            openMainApp(MainTab.HOME)
            return
        }

        val installedModels = app.modelRepository.listInstalledManifests()
        if (installedModels.isEmpty()) {
            isTapSessionRecording = false
            isHoldSessionRecording = false
            tvStatus?.setText(R.string.status_need_model)
            openMainApp(MainTab.MODELS)
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

        applyMicStyle()
        waveformView?.setVisualMode(VoiceWaveformView.VisualMode.LISTENING)
        updatePanelMode()
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
            applyMicStyle()
            waveformView?.setVisualMode(VoiceWaveformView.VisualMode.TRANSCRIBING)
            tvStatus?.setText(R.string.status_transcribing)
            updatePanelMode()

            val audioResult = audioRecorder.stop()
            if (!audioResult.hasVoiceActivity || audioResult.samples.isEmpty()) {
                isTranscribingNow = false
                waveformView?.setVisualMode(VoiceWaveformView.VisualMode.IDLE)
                tvStatus?.setText(R.string.status_no_speech)
                applyMicStyle()
                updatePanelMode()
                return@launch
            }

            val settingsSnapshot = currentSettings
            val asrLang = settingsSnapshot.spokenLanguageHint
            val transcribeResult = app.engineManager.transcribe(
                pcm16k = audioResult.samples,
                preferredModelId = settingsSnapshot.selectedModelId,
                options = AsrInferenceOptions(
                    language = asrLang,
                    numThreads = settingsSnapshot.cpuThreads,
                    enableStylePrompt = settingsSnapshot.whisperStylePromptEnabled,
                    translateToEnglish = settingsSnapshot.translateToEnglish,
                    chineseOutputMode = settingsSnapshot.chineseOutputMode,
                )
            )

            isTranscribingNow = false
            waveformView?.setVisualMode(VoiceWaveformView.VisualMode.IDLE)
            applyMicStyle()
            updatePanelMode()

            transcribeResult.fold(
                onSuccess = { res ->
                    val text = res.text.trim()
                    if (text.isNotEmpty()) {
                        lastTextUsesCjk = text.any { it in '\u3000'..'\u9FFF' }
                        updateToolbarAndPunctuation()
                        val commitStr = if (shouldAppendTrailingSpace(text, asrLang)) "$text " else text
                        currentInputConnection?.commitText(commitStr, 1)
                        pushUndo(commitStr)
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
        applyMicStyle()
        waveformView?.setVisualMode(VoiceWaveformView.VisualMode.IDLE)
        tvStatus?.text = defaultIdleHint(currentSettings.interactionMode)
        updatePanelMode()
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

        translateAvailable = activeModel?.canTranslateToEnglish == true
        val translating = translateAvailable && currentSettings.translateToEnglish

        val useCjkPunct = !translating && (lastTextUsesCjk
            ?: (resources.configuration.locales[0].language in setOf("zh", "ja")))
        val symbols = if (useCjkPunct) CJK_PUNCTUATION else LATIN_PUNCTUATION
        punctButtons.forEachIndexed { idx, tv ->
            if (idx < symbols.size) tv.text = symbols[idx]
        }

        applyLangButtonStyle()
        updatePanelMode(animate = false)
    }

    private val isRecordingUi: Boolean
        get() = isTapSessionRecording || isHoldSessionRecording || isTranscribingNow

    private fun hasTargetIme(): Boolean {
        val id = currentSettings.targetImeId
        if (id.isEmpty()) return false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.id == id }
    }

    /** Shows the idle panel or the focused recording panel, and refreshes conditional buttons. */
    private fun updatePanelMode(animate: Boolean = true) {
        val recording = isRecordingUi
        val idleVis = if (recording) View.GONE else View.VISIBLE
        punctRow?.visibility = idleVis
        tvStatus?.visibility = idleVis
        bottomRow?.visibility = idleVis
        btnSettings?.visibility = idleVis
        btnUndo?.visibility = if (!recording && undoStack.isNotEmpty()) View.VISIBLE else View.GONE
        btnRedo?.visibility = if (!recording && redoStack.isNotEmpty()) View.VISIBLE else View.GONE
        btnLang?.visibility = if (!recording && translateAvailable) View.VISIBLE else View.GONE
        btnTargetIme?.visibility = if (!recording && hasTargetIme()) View.VISIBLE else View.GONE
        btnCancelRecording?.visibility =
            if (recording && !isTranscribingNow) View.VISIBLE else View.GONE
        waveformView?.visibility = if (recording) View.VISIBLE else View.GONE
        btnMic?.apply {
            if (recording) setImageDrawable(null) else setImageResource(R.drawable.ic_mic)
        }
        animateMic(if (recording) MIC_CIRCLE_DP else MIC_PILL_DP, animate)
    }

    private fun animateMic(targetDp: Int, animate: Boolean) {
        val mic = btnMic ?: return
        val density = resources.displayMetrics.density
        val lp = mic.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetPx = (targetDp * density).toInt()
        val centerPx = MIC_CENTER_DP * density
        micAnimator?.cancel()
        fun applyHeight(h: Int) {
            lp.height = h
            lp.topMargin = (centerPx - h / 2f).toInt()
            mic.layoutParams = lp
        }
        if (!animate || lp.height == targetPx) {
            applyHeight(targetPx)
            return
        }
        micAnimator = ValueAnimator.ofInt(lp.height, targetPx).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun pushUndo(text: String) {
        undoStack.addLast(text)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        updatePanelMode(animate = false)
    }

    private fun clearUndoHistory() {
        if (undoStack.isEmpty() && redoStack.isEmpty()) return
        undoStack.clear()
        redoStack.clear()
        updatePanelMode(animate = false)
    }

    private fun performUndo() {
        val text = undoStack.lastOrNull() ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(text.length, 0)?.toString()
        if (before != text || !ic.getSelectedText(0).isNullOrEmpty()) {
            // The cursor moved or the text was edited elsewhere: history is stale.
            clearUndoHistory()
            return
        }
        ic.deleteSurroundingText(text.length, 0)
        undoStack.removeLast()
        redoStack.addLast(text)
        updatePanelMode(animate = false)
    }

    private fun performRedo() {
        val text = redoStack.lastOrNull() ?: return
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        redoStack.removeLast()
        undoStack.addLast(text)
        updatePanelMode(animate = false)
    }

    private fun switchToTargetIme() {
        val id = currentSettings.targetImeId
        if (id.isEmpty()) return
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ok = runCatching { switchInputMethod(id) }.isSuccess
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val token = window?.window?.attributes?.token
            if (imm != null && token != null) {
                @Suppress("DEPRECATION")
                imm.setInputMethod(token, id)
                ok = true
            }
        }
        if (!ok) showImePicker()
    }

    private fun performBackspaceOnce() {
        clearUndoHistory()
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
        clearUndoHistory()
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

    private fun showImePicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun openMainApp(tab: MainTab) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TAB, tab.name)
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
        private const val MAX_UNDO = 30
        private const val MIC_PILL_DP = 68
        private const val MIC_CIRCLE_DP = 164
        private const val MIC_CENTER_DP = 104f
        private val CJK_PUNCTUATION = listOf("，", "。", "：", "；", "！", "？", "@", "/")
        private val LATIN_PUNCTUATION = listOf(",", ".", ":", ";", "!", "?", "@", "/")
    }
}
