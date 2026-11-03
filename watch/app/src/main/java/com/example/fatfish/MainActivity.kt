package com.example.fatfish

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.fatfish.audio.AudioRecorder
import com.example.fatfish.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        // 按下时长小于该值视为"点击"（开心蹦跳），否则视为"按住说话"
        private const val TAP_MS = 250L
        // 回复显示后，鱼保持"说话"动画多久再回到待机（气泡文字会一直保留）
        private const val SPEAKING_ANIM_MS = 4000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: FishViewModel
    private val recorder = AudioRecorder()

    // 本地识别 + 云端大脑，后台加载完成后才可用（已去掉本地语音合成）
    private var asr: SherpaAsr? = null
    private var deepSeek: DeepSeekClient? = null

    @Volatile
    private var ready = false
    private var chatJob: Job? = null
    private var speakingAnimJob: Job? = null
    private var pressStart = 0L
    private var currentKey = ""
    private var currentEndpoint = ""
    private var currentModel = ""
    private var currentProtocol = ""
    private var currentPersona = ""
    private var currentInputMode = DeepSeekClient.INPUT_VOICE
    /** 已应用的方向设置（缓存，避免重复设置触发多余旋转动画） */
    private var appliedAutoRotate: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 尽早应用方向设置，减少"先竖屏再转"的多余动画
        applyOrientation()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 应用大肥鱼大小设置
        binding.fishView.setUserScale(DeepSeekClient.getFishScale(this))

        viewModel = ViewModelProvider(this)[FishViewModel::class.java]
        ensureRecordPermission()

        // 长回复可以在气泡里滚动查看
        binding.replyText.movementMethod = ScrollingMovementMethod.getInstance()

        // 状态变化 -> 驱动鱼的动作 + 顶部提示
        viewModel.state.observe(this) { s ->
            binding.fishView.setState(s)
            if (ready) {
                binding.statusText.text = when (s) {
                    FishState.IDLE -> "大肥鱼在游来游去"
                    FishState.LISTENING -> "在听你说话…"
                    FishState.THINKING -> "大肥鱼在思考…"
                    FishState.SPEAKING -> "大肥鱼在回答…"
                    FishState.HAPPY -> "大肥鱼好开心！"
                    FishState.SLEEPY -> "大肥鱼困了…"
                }
            }
        }

        // 交互直接作用在人物身上：
        //  语音模式：短按（<250ms）= 开心蹦跳，按住 = 说话
        //  键盘模式：点一下 = 开心蹦跳（发送走底部输入框）
        binding.fishView.setOnTouchListener { _, e ->
            if (currentInputMode == DeepSeekClient.INPUT_KEYBOARD) {
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    viewModel.setHappy()
                }
                return@setOnTouchListener true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressStart = SystemClock.uptimeMillis()
                    binding.replyText.visibility = View.GONE   // 开始新一轮，先收起旧气泡
                    viewModel.startListening()
                    recorder.start()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val pcm = recorder.stop()
                    val held = SystemClock.uptimeMillis() - pressStart
                    if (held < TAP_MS) {
                        viewModel.setHappy()      // 短按算点击
                    } else {
                        onSpeechCaptured(pcm)     // 长按松手，处理语音
                    }
                    true
                }
                else -> false
            }
        }

        // 发送按钮：取输入框文字，走文字对话
        binding.sendButton.setOnClickListener { submitTextInput() }
        // 键盘上的"发送"键同样触发
        binding.inputEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTextInput()
                true
            } else false
        }

        // 设置入口：进入 API Key 输入页
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 应用当前输入方式（语音 / 键盘）
        currentInputMode = DeepSeekClient.getInputMode(this)
        applyInputMode()

        // 后台加载本地 ASR 模型（约 79MB，需要几秒）
        binding.statusText.text = "大肥鱼在准备…"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                asr = SherpaAsr(this@MainActivity)
                currentKey = DeepSeekClient.getApiKey(this@MainActivity)
                currentEndpoint = DeepSeekClient.getBaseUrl(this@MainActivity)
                currentModel = DeepSeekClient.getModel(this@MainActivity)
                currentProtocol = DeepSeekClient.getProtocol(this@MainActivity)
                currentPersona = DeepSeekClient.getPersonaId(this@MainActivity)
                deepSeek = DeepSeekClient(this@MainActivity, currentKey)
                ready = true
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "大肥鱼在游来游去"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "初始化失败：${e.message}"
                }
            }
        }
    }

    /** 从设置页返回时，若地址/协议/模型/Key 任一变了就重建大脑客户端，输入方式变了就切换界面 */
    override fun onResume() {
        super.onResume()
        // 从设置页返回时，方向设置可能已改变，重新应用
        applyOrientation()
        // 大肥鱼大小可能已调整，重新应用
        binding.fishView.setUserScale(DeepSeekClient.getFishScale(this))
        val key = DeepSeekClient.getApiKey(this)
        val endpoint = DeepSeekClient.getBaseUrl(this)
        val model = DeepSeekClient.getModel(this)
        val protocol = DeepSeekClient.getProtocol(this)
        val persona = DeepSeekClient.getPersonaId(this)
        val personaChanged = persona != currentPersona
        if (ready && (key != currentKey || endpoint != currentEndpoint ||
                    model != currentModel || protocol != currentProtocol || personaChanged)) {
            currentKey = key
            currentEndpoint = endpoint
            currentModel = model
            currentProtocol = protocol
            currentPersona = persona
            deepSeek = DeepSeekClient(this, key)
            // 切换人格相当于换了个新对话，清掉旧气泡
            if (personaChanged) {
                binding.replyText.visibility = View.GONE
            }
        }
        val inputMode = DeepSeekClient.getInputMode(this)
        if (inputMode != currentInputMode) {
            currentInputMode = inputMode
            applyInputMode()
        }
    }

    /** 根据当前输入方式切换界面：语音 = 按住说话；键盘 = 底部输入框 */
    private fun applyInputMode() {
        val keyboard = currentInputMode == DeepSeekClient.INPUT_KEYBOARD
        binding.inputBar.visibility = if (keyboard) View.VISIBLE else View.GONE
        if (!keyboard) {
            binding.inputEdit.clearFocus()
        }
    }

    /** 应用「随重力方向旋转」设置：开=随重力自动横竖屏，关=固定竖屏。仅设置变化时生效，避免重复设置触发多余动画 */
    private fun applyOrientation() {
        val auto = DeepSeekClient.getAutoRotate(this)
        if (auto == appliedAutoRotate) return
        appliedAutoRotate = auto
        requestedOrientation = if (auto) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /** 读取输入框文字并发送（空内容忽略） */
    private fun submitTextInput() {
        val text = binding.inputEdit.text.toString().trim()
        if (text.isEmpty()) return
        binding.inputEdit.setText("")
        binding.replyText.visibility = View.GONE
        onTextInput(text)
    }

    /**
     * 收到一段语音后的完整链路：
     * 录音 -> 本地 ASR 识别 -> DeepSeek 生成回复 -> 漂浮文本框显示
     */
    private fun onSpeechCaptured(pcm: ByteArray) {
        if (!ready || asr == null || deepSeek == null) {
            viewModel.setIdle()
            return
        }
        viewModel.startThinking()
        chatJob?.cancel()
        chatJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ① 本地识别
                val text = asr!!.recognize(pcm)
                if (text.isBlank()) {
                    withContext(Dispatchers.Main) {
                        viewModel.setIdle()
                        showReply("鱼鱼没听清，再说一遍嘛～")
                    }
                    return@launch
                }
                // ② 识别成功 → 走云端大脑
                requestReply(text)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.setIdle()
                    showReply("鱼鱼卡住了：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 键盘文字输入：跳过 ASR，直接走云端大脑 */
    private fun onTextInput(text: String) {
        if (!ready || deepSeek == null) {
            return
        }
        viewModel.startThinking()
        chatJob?.cancel()
        chatJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                requestReply(text)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.setIdle()
                    showReply("鱼鱼卡住了：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    /** 在后台线程调用云端大脑，切回主线程后显示气泡（鱼保持"说话"动画几秒） */
    private suspend fun requestReply(text: String) {
        val reply = deepSeek!!.chat(text)
        withContext(Dispatchers.Main) {
            viewModel.startSpeaking()
            showReply(reply)
            scheduleBackToIdle()
        }
    }

    /** 漂浮气泡显示文字 */
    private fun showReply(text: String) {
        binding.replyText.text = text
        binding.replyText.scrollTo(0, 0)
        binding.replyText.visibility = View.VISIBLE
    }

    /** 几秒后让鱼从"说话"动画回到待机，气泡文字继续保留 */
    private fun scheduleBackToIdle() {
        speakingAnimJob?.cancel()
        speakingAnimJob = lifecycleScope.launch {
            delay(SPEAKING_ANIM_MS)
            if (viewModel.state.value == FishState.SPEAKING) {
                viewModel.setIdle()
            }
        }
    }

    private fun ensureRecordPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        }
    }

    override fun onDestroy() {
        chatJob?.cancel()
        speakingAnimJob?.cancel()
        recorder.stop()
        asr?.release()
        super.onDestroy()
    }
}
