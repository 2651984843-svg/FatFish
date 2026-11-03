package com.example.fatfish

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fatfish.databinding.ActivitySettingsBinding
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：完整配置云端大脑（API 地址 / 协议 / 密钥 / 模型）。
 * 支持 OpenAI 兼容（DeepSeek / 中转站）与 Anthropic（Claude）两种协议，
 * 模型名可手动填，也可点"获取可用模型"从服务端拉列表点选。
 * 保存到本地 SharedPreferences，下次启动自动生效，无需重新编译。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 当前选中的协议（点保存时才落盘） */
    private var currentProtocol = DeepSeekClient.PROTOCOL_OPENAI

    /** 当前选中的输入方式（点保存时才落盘） */
    private var currentInputMode = DeepSeekClient.INPUT_VOICE
    /** 已应用的方向设置（缓存，避免重复设置触发多余旋转动画） */
    private var appliedAutoRotate: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 尽早应用方向设置，减少"先竖屏再转"的多余动画
        applyOrientation()

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 返回：手表无实体返回键，顶部返回按钮直接回主界面（侧滑返回仍可用）
        binding.backButton.setOnClickListener { finish() }

        // 随重力方向旋转开关：切换即保存并立即生效
        binding.autoRotateSwitch.isChecked = DeepSeekClient.getAutoRotate(this)
        binding.autoRotateSwitch.setOnCheckedChangeListener { _, checked ->
            DeepSeekClient.saveAutoRotate(this, checked)
            applyOrientation()
        }

        // 大肥鱼大小调节：预览鱼基础尺寸取屏幕高度 78%（与主界面默认一致）
        initFishSizePreview()
        updateFishSizeEntry()
        binding.fishSizeEntry.setOnClickListener { openFishSizeOverlay() }
        binding.fishSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = progressToScale(progress)
                DeepSeekClient.saveFishScale(this@SettingsActivity, scale)
                updateFishSizePreview(scale)
                updateFishSizeEntry()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.fishSizeDone.setOnClickListener {
            binding.fishSizeOverlay.visibility = View.GONE
        }

        // 预填当前已保存的配置，方便查看/修改
        currentProtocol = DeepSeekClient.getProtocol(this)
        binding.protocolSelect.text = protocolLabel(currentProtocol)
        binding.baseUrlInput.setText(DeepSeekClient.getBaseUrl(this))
        binding.modelInput.setText(DeepSeekClient.getModel(this))
        binding.keyInput.setText(DeepSeekClient.getApiKey(this))
        binding.cityInput.setText(DeepSeekClient.getDefaultCity(this))
        currentInputMode = DeepSeekClient.getInputMode(this)
        binding.inputModeSelect.text = inputModeLabel(currentInputMode)
        binding.personaSelect.text = Personas.byId(DeepSeekClient.getPersonaId(this)).name

        // 协议选择：点击弹单选对话框；切换时若是默认地址则自动跟随切换
        binding.protocolSelect.setOnClickListener {
            val options = arrayOf(
                "OpenAI 兼容（DeepSeek / 大多数中转站）",
                "Anthropic（Claude）"
            )
            val values = arrayOf(DeepSeekClient.PROTOCOL_OPENAI, DeepSeekClient.PROTOCOL_ANTHROPIC)
            val checked = values.indexOf(currentProtocol).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("API 协议")
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    switchProtocol(values[which])
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 输入方式选择：点击弹单选对话框，二选一
        binding.inputModeSelect.setOnClickListener {
            val options = arrayOf("语音输入（按住人物说话）", "键盘文字输入")
            val values = arrayOf(DeepSeekClient.INPUT_VOICE, DeepSeekClient.INPUT_KEYBOARD)
            val checked = values.indexOf(currentInputMode).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("输入方式")
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    currentInputMode = values[which]
                    binding.inputModeSelect.text = inputModeLabel(currentInputMode)
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 人格：进入人格选择子页面
        binding.personaSelect.setOnClickListener {
            startActivity(Intent(this, PersonaActivity::class.java))
        }

        // 远程拉取配置：输入手机端地址，GET /config 一键覆盖保存
        binding.fetchRemoteButton.setOnClickListener {
            val edit = EditText(this)
            edit.hint = "http://192.168.1.5:8765"
            edit.setText(DeepSeekClient.getLastRemoteUrl(this))
            edit.setSelection(edit.text.length)
            val pad = (20 * resources.displayMetrics.density).toInt()
            edit.setPadding(pad, pad / 2, pad, pad / 2)
            AlertDialog.Builder(this)
                .setTitle("远程拉取配置")
                .setMessage("在手机上打开「大肥鱼配置推送」并开启同步服务，把上面显示的地址填到这里，一键覆盖手表配置。")
                .setView(edit)
                .setPositiveButton("拉取") { _, _ ->
                    val url = edit.text.toString().trim()
                    if (url.isBlank()) {
                        Toast.makeText(this, "请先输入手机端地址", Toast.LENGTH_SHORT).show()
                    } else {
                        doFetchRemote(url)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 获取可用模型：后台拉 /models 列表，弹出单选列表
        binding.fetchModelsButton.setOnClickListener {
            val key = binding.keyInput.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "先填 API 密钥才能拉取模型列表", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.fetchModelsButton.text = "获取中…"
            binding.fetchModelsButton.isEnabled = false
            val url = binding.baseUrlInput.text.toString()
            val protocol = currentProtocol
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val models = DeepSeekClient.fetchModels(url, key, protocol)
                    withContext(Dispatchers.Main) {
                        restoreFetchButton()
                        showModelPicker(models)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        restoreFetchButton()
                        Toast.makeText(
                            this@SettingsActivity,
                            "拉取失败：${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // 保存全部配置
        binding.saveButton.setOnClickListener {
            DeepSeekClient.saveBaseUrl(this, binding.baseUrlInput.text.toString())
            DeepSeekClient.saveProtocol(this, currentProtocol)
            DeepSeekClient.saveModel(this, binding.modelInput.text.toString())
            DeepSeekClient.saveApiKey(this, binding.keyInput.text.toString())
            DeepSeekClient.saveDefaultCity(this, binding.cityInput.text.toString())
            DeepSeekClient.saveInputMode(this, currentInputMode)
            Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从人格子页面返回时刷新当前显示的人格名
        binding.personaSelect.text = Personas.byId(DeepSeekClient.getPersonaId(this)).name
        // 刷新大肥鱼大小入口显示
        updateFishSizeEntry()
    }

    /** 切换协议：更新显示文字；若地址还是旧协议的默认值，自动换成新协议默认值 */
    private fun switchProtocol(newProtocol: String) {
        val old = currentProtocol
        currentProtocol = newProtocol
        binding.protocolSelect.text = protocolLabel(newProtocol)
        if (binding.baseUrlInput.text.toString().trim() == DeepSeekClient.getDefaultEndpoint(old)) {
            binding.baseUrlInput.setText(DeepSeekClient.getDefaultEndpoint(newProtocol))
        }
    }

    private fun protocolLabel(protocol: String): String =
        if (protocol == DeepSeekClient.PROTOCOL_ANTHROPIC) "Anthropic（Claude）"
        else "OpenAI 兼容（DeepSeek / 中转站）"

    private fun inputModeLabel(mode: String): String =
        if (mode == DeepSeekClient.INPUT_KEYBOARD) "键盘文字输入"
        else "语音输入（按住人物说话）"

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

    /** SeekBar 进度 -> 缩放倍率（0~120 -> 0.4~1.6） */
    private fun progressToScale(progress: Int): Float =
        DeepSeekClient.FISH_SCALE_MIN + progress / 100f

    /** 缩放倍率 -> SeekBar 进度 */
    private fun scaleToProgress(scale: Float): Int =
        ((scale - DeepSeekClient.FISH_SCALE_MIN) * 100).roundToInt().coerceIn(0, 120)

    /** 初始化预览鱼尺寸：基础高度 = 屏幕高度的 78%（与主界面默认大小一致） */
    private fun initFishSizePreview() {
        val baseH = (resources.displayMetrics.heightPixels * 0.78f).toInt()
        binding.previewFish.layoutParams = binding.previewFish.layoutParams.apply {
            width = baseH
            height = baseH
        }
    }

    /** 更新预览鱼缩放与百分比文字 */
    private fun updateFishSizePreview(scale: Float) {
        binding.previewFish.scaleX = scale
        binding.previewFish.scaleY = scale
        binding.fishSizePercent.text = getString(
            R.string.fish_size_percent,
            (scale * 100).roundToInt()
        )
    }

    /** 更新「大肥鱼大小」入口文字（显示当前百分比） */
    private fun updateFishSizeEntry() {
        val scale = DeepSeekClient.getFishScale(this)
        binding.fishSizeEntry.text = getString(
            R.string.fish_size_entry_value,
            (scale * 100).roundToInt()
        )
    }

    /** 打开大小调节浮层：同步滑块与预览到当前值 */
    private fun openFishSizeOverlay() {
        val scale = DeepSeekClient.getFishScale(this)
        binding.fishSizeSeek.progress = scaleToProgress(scale)
        updateFishSizePreview(scale)
        binding.fishSizeOverlay.visibility = View.VISIBLE
    }

    private fun restoreFetchButton() {
        binding.fetchModelsButton.text = getString(R.string.fetch_models)
        binding.fetchModelsButton.isEnabled = true
    }

    /** 后台拉取远程配置并覆盖保存，成功后刷新设置页所有预填字段 */
    private fun doFetchRemote(url: String) {
        binding.fetchRemoteButton.text = "拉取中…"
        binding.fetchRemoteButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = DeepSeekClient.fetchRemoteConfig(url)
                DeepSeekClient.saveRemoteConfig(this@SettingsActivity, config)
                DeepSeekClient.saveLastRemoteUrl(this@SettingsActivity, url)
                withContext(Dispatchers.Main) {
                    restoreRemoteButton()
                    currentProtocol = DeepSeekClient.getProtocol(this@SettingsActivity)
                    binding.protocolSelect.text = protocolLabel(currentProtocol)
                    binding.baseUrlInput.setText(DeepSeekClient.getBaseUrl(this@SettingsActivity))
                    binding.modelInput.setText(DeepSeekClient.getModel(this@SettingsActivity))
                    binding.keyInput.setText(DeepSeekClient.getApiKey(this@SettingsActivity))
                    binding.cityInput.setText(DeepSeekClient.getDefaultCity(this@SettingsActivity))
                    Toast.makeText(
                        this@SettingsActivity,
                        "配置已拉取并保存",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    restoreRemoteButton()
                    Toast.makeText(
                        this@SettingsActivity,
                        "拉取失败：${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun restoreRemoteButton() {
        binding.fetchRemoteButton.text = getString(R.string.fetch_remote)
        binding.fetchRemoteButton.isEnabled = true
    }

    /** 模型列表选择弹窗，点选后自动填入模型名输入框 */
    private fun showModelPicker(models: List<String>) {
        val names = models.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择模型（共 ${names.size} 个）")
            .setItems(names) { _, which ->
                binding.modelInput.setText(names[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
