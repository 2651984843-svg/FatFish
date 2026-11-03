package com.example.fatfishphone

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.fatfishphone.databinding.ActivityMainBinding
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * 大肥鱼「手机端」配置推送工具：
 * 在手机上填好 API 地址 / 协议 / 模型 / Key / 城市，打开同步服务后，
 * 内置 HTTP 服务（端口 8765）供手表端拉取，省去手表小屏打字的痛苦。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 最新配置 JSON 缓存：输入框一变就更新，服务线程只读这个引用，避免跨线程访问 View */
    private val latestConfig = AtomicReference<String>("{}")

    private val server = ConfigServer { latestConfig.get() }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var currentProtocol = "openai"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 预填上次保存的配置
        currentProtocol = prefs.getString(KEY_PROTOCOL, "openai") ?: "openai"
        binding.baseUrlInput.setText(prefs.getString(KEY_BASE_URL, "") ?: "")
        binding.modelInput.setText(prefs.getString(KEY_MODEL, "") ?: "")
        binding.keyInput.setText(prefs.getString(KEY_API, "") ?: "")
        binding.cityInput.setText(prefs.getString(KEY_CITY, "") ?: "")
        binding.protocolSelect.text = protocolLabel(currentProtocol)
        updateLatestConfig()

        // 任一输入框变化：保存 + 更新推送缓存
        binding.baseUrlInput.doAfterTextChanged { onFieldChanged() }
        binding.modelInput.doAfterTextChanged { onFieldChanged() }
        binding.keyInput.doAfterTextChanged { onFieldChanged() }
        binding.cityInput.doAfterTextChanged { onFieldChanged() }

        // 协议选择
        binding.protocolSelect.setOnClickListener {
            val options = arrayOf(
                "OpenAI 兼容（DeepSeek / 中转站）",
                "Anthropic（Claude）"
            )
            val values = arrayOf("openai", "anthropic")
            val checked = values.indexOf(currentProtocol).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("API 协议")
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    currentProtocol = values[which]
                    binding.protocolSelect.text = protocolLabel(currentProtocol)
                    saveFields()
                    updateLatestConfig()
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 同步服务开关
        binding.syncSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                saveFields()
                updateLatestConfig()
                if (server.start(PORT)) {
                    val ip = NetworkUtils.getWifiIp(this)
                    val addr = if (ip.startsWith("未")) ip else "http://$ip:$PORT"
                    binding.addressText.text = getString(R.string.address_value, addr)
                    binding.statusText.text = getString(R.string.status_on)
                } else {
                    binding.syncSwitch.isChecked = false
                    Toast.makeText(this, "服务启动失败，可能端口 $PORT 被占用", Toast.LENGTH_LONG).show()
                }
            } else {
                server.stop()
                binding.addressText.text = getString(R.string.address_hint)
                binding.statusText.text = getString(R.string.status_off)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()
    }

    /** 输入框变化：落盘 + 更新推送缓存 */
    private fun onFieldChanged() {
        saveFields()
        updateLatestConfig()
    }

    private fun saveFields() {
        prefs.edit()
            .putString(KEY_BASE_URL, binding.baseUrlInput.text.toString().trim())
            .putString(KEY_PROTOCOL, currentProtocol)
            .putString(KEY_MODEL, binding.modelInput.text.toString().trim())
            .putString(KEY_API, binding.keyInput.text.toString().trim())
            .putString(KEY_CITY, binding.cityInput.text.toString().trim())
            .apply()
    }

    /** 把当前界面字段拼成完整配置 JSON，写入原子引用 */
    private fun updateLatestConfig() {
        val obj = JSONObject().apply {
            put("version", 1)
            put("app", "FatFishPhone")
            put("base_url", binding.baseUrlInput.text.toString().trim())
            put("protocol", currentProtocol)
            put("model", binding.modelInput.text.toString().trim())
            put("api_key", binding.keyInput.text.toString().trim())
            put("city", binding.cityInput.text.toString().trim())
        }
        latestConfig.set(obj.toString())
    }

    private fun protocolLabel(p: String): String =
        if (p == "anthropic") "Anthropic（Claude）" else "OpenAI 兼容（DeepSeek / 中转站）"

    companion object {
        private const val PORT = 8765
        private const val PREFS = "fatfish_phone"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_MODEL = "model"
        private const val KEY_API = "api_key"
        private const val KEY_CITY = "city"
    }
}
