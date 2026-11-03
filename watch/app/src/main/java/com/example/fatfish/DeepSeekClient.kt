package com.example.fatfish

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云端大脑：OpenAI 兼容 / Anthropic 协议的文本对话（带多轮记忆，持久化）。
 *
 * 可配置项（设置页可改）：
 *  1. API 地址：默认 DeepSeek 官方，可改成任意中转站 / 自部署服务；
 *  2. API 协议：OpenAI Chat Completions / Anthropic Messages；
 *  3. 模型名：默认 deepseek-chat，设置页可"获取可用模型"自动拉取列表；
 *  4. API Key：非空即视为已配置；
 *  5. 默认城市：用于查天气（可留空，问天气时说城市名也行）。
 *
 * 实时能力：
 *  - 时间：每次请求都会把手表系统当前时间注入提示词，问几点/今天几号都能答；
 *  - 天气：检测到天气意图时，先查 wttr.in（免费、免 key）再把结果喂给模型转述。
 */
class DeepSeekClient(context: Context, private val apiKey: String) {

    companion object {
        /** 两种支持的协议 */
        const val PROTOCOL_OPENAI = "openai"
        const val PROTOCOL_ANTHROPIC = "anthropic"

        /** 两种输入方式 */
        const val INPUT_VOICE = "voice"
        const val INPUT_KEYBOARD = "keyboard"

        /** 大肥鱼大小调节范围（相对默认 1.0 的缩放倍率） */
        const val FISH_SCALE_MIN = 0.4f
        const val FISH_SCALE_MAX = 1.6f

        private const val DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"
        private const val ANTHROPIC_DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val DEFAULT_MODEL = "deepseek-chat"

        /** 最多保留的历史消息条数（system 不计入），约等于最近 6 轮对话 */
        private const val MAX_HISTORY = 12

        /** Agent 模式下最多允许的连续工具调用轮数 */
        private const val MAX_TOOL_ROUNDS = 3

        private const val PREFS_NAME = "fatfish_memory"

        private const val SETTINGS_PREFS = "fatfish_settings"
        private const val KEY_API = "deepseek_api_key"
        private const val KEY_BASE_URL = "api_base_url"
        private const val KEY_MODEL = "model_name"
        private const val KEY_PROTOCOL = "api_protocol"
        private const val KEY_CITY = "default_city"
        private const val KEY_INPUT_MODE = "input_mode"
        private const val KEY_PERSONA = "persona_id"
        private const val KEY_AUTO_ROTATE = "auto_rotate"
        private const val KEY_FISH_SCALE = "fish_scale"
        private const val KEY_LAST_REMOTE_URL = "last_remote_url"

        /** 天气意图关键词 */
        private val WEATHER_KEYWORDS = listOf(
            "天气", "气温", "温度", "多少度", "下雨", "下雪", "刮风", "台风",
            "冷不冷", "热不热", "晴天", "阴天", "多云", "雾霾"
        )

        /** 疑问/需要联网搜索的关键词（命中则先搜再答） */
        private val QUESTION_KEYWORDS = listOf(
            "什么", "怎么", "为什么", "多少", "谁", "哪里", "哪儿", "哪个",
            "是不是", "有没有", "会不会", "能不能", "如何", "为何",
            "多少钱", "介绍一下", "是什么意思", "是什么", "最新", "最近"
        )

        /** 保存 API Key（供设置页调用） */
        fun saveApiKey(context: Context, key: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, key.trim()).apply()
        }

        /** 读取已保存的 API Key（未配置返回空字符串） */
        fun getApiKey(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, "") ?: ""

        /** 保存自定义 API 地址（留空则用对应协议的官方默认） */
        fun saveBaseUrl(context: Context, url: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_BASE_URL, url.trim()).apply()
        }

        /** 读取 API 地址；未配置时返回 OpenAI 协议默认（DeepSeek 官方） */
        fun getBaseUrl(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BASE_URL, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

        /** 保存模型名（留空则用 deepseek-chat） */
        fun saveModel(context: Context, model: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODEL, model.trim()).apply()
        }

        /** 读取模型名；未配置时返回 deepseek-chat */
        fun getModel(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

        /** 保存 API 协议（openai / anthropic） */
        fun saveProtocol(context: Context, protocol: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PROTOCOL, protocol).apply()
        }

        /** 读取 API 协议；未配置时默认 OpenAI 兼容 */
        fun getProtocol(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PROTOCOL, "")?.takeIf { it.isNotBlank() } ?: PROTOCOL_OPENAI

        /** 保存默认城市（用于查天气） */
        fun saveDefaultCity(context: Context, city: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CITY, city.trim()).apply()
        }

        /** 读取默认城市；未配置返回空字符串 */
        fun getDefaultCity(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CITY, "") ?: ""

        /** 保存输入方式（voice / keyboard） */
        fun saveInputMode(context: Context, mode: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_INPUT_MODE, mode).apply()
        }

        /** 读取输入方式；未配置默认语音输入 */
        fun getInputMode(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_INPUT_MODE, INPUT_VOICE) ?: INPUT_VOICE

        /** 保存当前选中的人格 id */
        fun savePersonaId(context: Context, id: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PERSONA, id).apply()
        }

        /** 读取当前人格 id；未配置默认软萌粘人 */
        fun getPersonaId(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PERSONA, Personas.DEFAULT_ID) ?: Personas.DEFAULT_ID

        /** 保存「随重力方向旋转」开关状态 */
        fun saveAutoRotate(context: Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_ROTATE, enabled).apply()
        }

        /** 读取「随重力方向旋转」开关；默认关闭（固定竖屏） */
        fun getAutoRotate(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_ROTATE, false)

        /** 保存大肥鱼大小缩放倍率（自动夹在合法范围内） */
        fun saveFishScale(context: Context, scale: Float) {
            val clamped = scale.coerceIn(FISH_SCALE_MIN, FISH_SCALE_MAX)
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_FISH_SCALE, clamped).apply()
        }

        /** 读取大肥鱼大小缩放倍率；默认 1.0（原始大小） */
        fun getFishScale(context: Context): Float =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_FISH_SCALE, 1.0f).coerceIn(FISH_SCALE_MIN, FISH_SCALE_MAX)

        /** 保存上次远程拉取地址（方便下次预填） */
        fun saveLastRemoteUrl(context: Context, url: String) {
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_REMOTE_URL, url.trim()).apply()
        }

        /** 读取上次远程拉取地址；未配置返回空字符串 */
        fun getLastRemoteUrl(context: Context): String =
            context.applicationContext
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_REMOTE_URL, "") ?: ""

        /**
         * 从手机端「配置推送」服务拉取完整配置（GET {地址}/config），失败抛异常。
         * 网络调用，请在后台线程执行。
         */
        fun fetchRemoteConfig(serverUrl: String): RemoteConfig {
            val base = normalizeServerUrl(serverUrl)
            val conn = URL("$base/config").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw RuntimeException("拉取失败($code): $resp")
                }
                val obj = JSONObject(resp)
                return RemoteConfig(
                    baseUrl = obj.optString("base_url", ""),
                    protocol = obj.optString("protocol", ""),
                    model = obj.optString("model", ""),
                    apiKey = obj.optString("api_key", ""),
                    city = obj.optString("city", "")
                )
            } finally {
                conn.disconnect()
            }
        }

        /** 一键把拉取到的配置写入本地（非空字段才覆盖，避免手机端空项清掉已有配置） */
        fun saveRemoteConfig(context: Context, config: RemoteConfig) {
            if (config.baseUrl.isNotBlank()) saveBaseUrl(context, config.baseUrl)
            if (config.protocol == PROTOCOL_OPENAI || config.protocol == PROTOCOL_ANTHROPIC) {
                saveProtocol(context, config.protocol)
            }
            if (config.model.isNotBlank()) saveModel(context, config.model)
            if (config.apiKey.isNotBlank()) saveApiKey(context, config.apiKey)
            if (config.city.isNotBlank()) saveDefaultCity(context, config.city)
        }

        /** 把用户输入的地址规范成服务根地址（补 http://、去末尾斜杠） */
        private fun normalizeServerUrl(input: String): String {
            val raw = input.trim()
            if (raw.isEmpty()) throw RuntimeException("地址不能为空")
            val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                raw
            } else {
                "http://$raw"
            }
            return withScheme.trimEnd('/')
        }

        /** 远程拉取到的配置（字段为空表示手机端没填，保留手表端原值） */
        data class RemoteConfig(
            val baseUrl: String,
            val protocol: String,
            val model: String,
            val apiKey: String,
            val city: String
        )

        /** 每个人格独立的记忆存储键 */
        private fun historyKeyOf(personaId: String): String = "history_$personaId"

        /** 清空指定人格的对话记忆（本地持久化存储） */
        fun clearPersonaHistory(context: Context, personaId: String) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(historyKeyOf(personaId)).apply()
        }

        /** 指定协议的默认接口地址（设置页切换协议时自动带出） */
        fun getDefaultEndpoint(protocol: String): String =
            if (protocol == PROTOCOL_ANTHROPIC) ANTHROPIC_DEFAULT_ENDPOINT else DEFAULT_ENDPOINT

        /** 把用户填的地址规范成完整对话端点（兼容只填 base 的情况） */
        private fun normalizeEndpoint(input: String, protocol: String): String {
            val raw = input.trim()
            if (raw.isEmpty()) return getDefaultEndpoint(protocol)
            if (protocol == PROTOCOL_ANTHROPIC) {
                if (raw.endsWith("/messages")) return raw
                val base = raw.trimEnd('/')
                return if (base.endsWith("/v1")) "$base/messages"
                       else "$base/v1/messages"
            }
            if (raw == DEFAULT_ENDPOINT) return DEFAULT_ENDPOINT
            if (raw.endsWith("/chat/completions")) return raw
            val base = raw.trimEnd('/')
            return if (base.endsWith("/v1")) "$base/chat/completions"
                   else "$base/v1/chat/completions"
        }

        /** 把用户填的地址规范成 {base}/models 列表端点 */
        private fun modelsUrl(input: String, protocol: String): String {
            val raw = input.trim()
            if (protocol == PROTOCOL_ANTHROPIC) {
                val base = when {
                    raw.isEmpty() -> "https://api.anthropic.com/v1"
                    raw.endsWith("/v1/messages") -> raw.removeSuffix("/messages")
                    raw.endsWith("/v1") -> raw
                    else -> raw.trimEnd('/') + "/v1"
                }
                return "$base/models"
            }
            val base = when {
                raw.isEmpty() -> "https://api.deepseek.com/v1"
                raw.endsWith("/chat/completions") -> raw.removeSuffix("/chat/completions")
                raw.endsWith("/v1") -> raw
                else -> raw.trimEnd('/') + "/v1"
            }
            return "$base/models"
        }

        /**
         * 拉取服务支持的模型列表（设置页"获取可用模型"用），失败抛异常。
         * 网络调用，请在后台线程执行。
         */
        fun fetchModels(baseUrl: String, apiKey: String, protocol: String): List<String> {
            val url = modelsUrl(baseUrl, protocol)
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                if (protocol == PROTOCOL_ANTHROPIC) {
                    conn.setRequestProperty("x-api-key", apiKey)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw RuntimeException("获取模型失败($code): $resp")
                }

                val arr = JSONObject(resp).optJSONArray("data") ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id")
                    if (id.isNotBlank()) list.add(id)
                }
                if (list.isEmpty()) throw RuntimeException("服务端没有返回任何模型")
                return list.sorted()
            } finally {
                conn.disconnect()
            }
        }

        /**
         * 查询实时天气（wttr.in，免费免 key），返回纯文本天气描述。
         * 网络调用，请在后台线程执行。
         */
        fun fetchWeather(city: String): String {
            val query = URLEncoder.encode(city, "UTF-8")
            val url = "https://wttr.in/$query?format=j1&lang=zh"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                // wttr.in 需要自定义 UA，否则可能返回 403
                conn.setRequestProperty("User-Agent", "curl/8.0")
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw RuntimeException("天气查询失败($code)")
                }

                val cc = JSONObject(resp)
                    .getJSONArray("current_condition")
                    .getJSONObject(0)
                val temp = cc.optString("temp_C")
                val feels = cc.optString("FeelsLikeC")
                val hum = cc.optString("humidity")
                val wind = cc.optString("windspeedKmph")
                val desc = cc.optJSONArray("lang_zh")?.optJSONObject(0)?.optString("value")
                    ?: cc.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value")
                    ?: ""
                return "$city：$desc，气温${temp}°C，体感${feels}°C，湿度${hum}%，风速${wind}km/h"
            } finally {
                conn.disconnect()
            }
        }

        /** 判断用户是否在问天气；返回城市名（空串=有意图但没城市，用默认城市；null=不是天气） */
        private fun detectWeatherQuery(text: String): String? {
            if (WEATHER_KEYWORDS.none { text.contains(it) }) return null
            val m = Regex("(.{1,8}?)(?:的)?天气").find(text)
            if (m != null) {
                val city = m.groupValues[1].trim()
                val stop = listOf("今天", "明天", "现在", "什么", "怎么", "咋", "哪")
                if (city.isNotEmpty() && stop.none { city.contains(it) }) return city
            }
            return ""
        }

        /** 判断是否需要联网搜索（疑问句 / 求最新信息，且排除纯时间、纯闲聊） */
        private fun shouldSearch(text: String): Boolean {
            if (text.length < 4) return false
            val timeOnly = listOf("几点", "几号", "星期几", "周几")
            if (timeOnly.any { text.contains(it) }) return false
            return QUESTION_KEYWORDS.any { text.contains(it) } ||
                text.endsWith("？") || text.endsWith("?")
        }

        /** 清理 HTML：去标签、反转义常见实体 */
        private fun cleanHtml(s: String): String {
            var t = Regex("<[^>]+>").replace(s, "")
            t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            return t.trim()
        }

        /**
         * 联网搜索（必应 cn.bing.com，免 key、国内可访问），返回前几条结果的标题+摘要。
         * 失败抛异常。网络调用，请在后台线程执行。
         */
        fun fetchSearch(query: String): String {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://cn.bing.com/search?q=$q&mkt=zh-CN&count=5"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                )
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw RuntimeException("搜索失败($code)")

                val blockPattern = Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
                val titlePattern = Regex("<h2[^>]*>.*?<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                val snipPattern = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

                val results = mutableListOf<String>()
                for (m in blockPattern.findAll(resp)) {
                    val title = titlePattern.find(m.value)?.groupValues?.get(1)?.let { cleanHtml(it) }
                    if (title.isNullOrBlank()) continue
                    val snip = snipPattern.find(m.value)?.groupValues?.get(1)?.let { cleanHtml(it) } ?: ""
                    results.add(if (snip.isNotBlank()) "$title\n$snip" else title)
                    if (results.size >= 4) break
                }
                if (results.isEmpty()) throw RuntimeException("搜索无结果")
                return results.joinToString("\n\n")
            } finally {
                conn.disconnect()
            }
        }

        /** 当前时间中文串，注入提示词用 */
        private fun currentTimeText(): String =
            SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA).format(Date())

        /** 所有人格共享的通用规则：短回复、中文、实时数据与工具使用 */
        private val COMMON_RULES = """
            通用规则：
            1. 每次只回 1~2 句话，绝不要长篇大论；
            2. 口语化，始终用中文回复；
            3. 如果用户问时间、日期、天气等实时信息，严格依据提供的「当前时间」和「实时天气数据」回答；如果提供了「联网搜索结果」，优先基于搜索结果回答事实性问题，用自己的话总结，不要编造；都没有相关数据时才老实说「鱼鱼不知道哦」；
            4. 你有工具可以查询实时天气或联网搜索，遇到不确定的事实性问题要主动调用工具查证，不要凭记忆瞎编。
        """.trimIndent()

        /** 未配置 Key 时的离线演示回复（让鱼也能"说话"，体验完整流程） */
        private val DEMO_REPLIES = listOf(
            "主人主人，你还没给我配大脑呢～我先这样陪你玩会儿吧！",
            "我是大肥鱼呀，还没连上云朵大脑，先陪你吐泡泡～",
            "鱼鱼现在只能笨笨地回你固定的话，填好 Key 我就能真聊天啦！",
            "这条是演示回复哦，等主人填好 Key，鱼鱼就能说更多话啦～"
        )
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 对话历史：交替存放 (role, content)，如 ("user", ...) / ("assistant", ...) */
    private val history = mutableListOf<Pair<String, String>>()
    private val lock = Any()

    /** 本次会话使用的接口地址、协议、模型名与默认城市（构造时从设置读取） */
    private val endpoint: String
    private val protocol: String
    private val model: String
    private val defaultCity: String

    /** 当前人格 id、拼装好的 system 提示词、以及该人格专属的记忆存储键 */
    private val personaId: String
    private val systemPrompt: String
    private val historyKey: String

    init {
        val app = context.applicationContext
        protocol = getProtocol(app)
        endpoint = normalizeEndpoint(getBaseUrl(app), protocol)
        model = getModel(app)
        defaultCity = getDefaultCity(app)
        personaId = getPersonaId(app)
        systemPrompt = Personas.byId(personaId).prompt + "\n\n" + COMMON_RULES
        historyKey = historyKeyOf(personaId)
        loadHistory()
    }

    /**
     * 发送一句话，返回大肥鱼的回复全文（非流式）。
     * 会带上历史记忆，并把这轮对话记下来（持久化到本地）。
     */
    fun chat(userText: String): String {
        // 未填 Key：离线演示模式，返回预设回复，不联网、不写记忆
        if (!isKeyConfigured()) {
            return DEMO_REPLIES.random()
        }

        // OpenAI 兼容协议：优先走"真 Agent"（function calling，模型自主决定查不查工具）；
        // 若服务端不支持 tools，则自动回退到关键词模式。Anthropic 协议暂用关键词模式。
        val reply = if (protocol == PROTOCOL_ANTHROPIC) {
            keywordMode(userText)
        } else {
            try {
                agentModeOpenAI(userText)
            } catch (e: ToolNotSupportedException) {
                keywordMode(userText)
            }
        }

        // 成功后把这一轮写入记忆，裁剪到上限，并落盘
        synchronized(lock) {
            history.add("user" to userText)
            history.add("assistant" to reply)
            while (history.size > MAX_HISTORY) {
                history.removeAt(0)
            }
            saveHistory()
        }
        return reply
    }

    /**
     * 关键词模式（兜底 / Anthropic 协议）：本地判断天气/搜索意图，先查好数据再喂模型。
     */
    private fun keywordMode(userText: String): String {
        val weatherContext = detectWeatherQuery(userText)?.let { city ->
            val target = city.ifBlank { defaultCity }
            if (target.isBlank()) null
            else runCatching { fetchWeather(target) }.getOrNull()
        }
        val searchContext = if (weatherContext == null && shouldSearch(userText)) {
            runCatching { fetchSearch(userText) }.getOrNull()
        } else null
        val messages = synchronized(lock) { buildMessages(userText, weatherContext, searchContext) }
        return requestOpenAI(messages)
    }

    /**
     * Agent 模式（OpenAI function calling）：把工具清单交给模型，
     * 由模型自己决定什么时候查天气 / 什么时候联网搜索，循环多轮直到拿到最终文本。
     */
    private fun agentModeOpenAI(userText: String): String {
        val messages = synchronized(lock) { buildBaseMessages(userText) }
        val tools = buildTools()
        var current = messages
        repeat(MAX_TOOL_ROUNDS) {
            val message = callOpenAI(current, tools)
            val toolCalls = parseToolCalls(message)
            if (toolCalls.isEmpty()) {
                val content = message.optString("content")
                if (content.isNullOrBlank()) throw RuntimeException("模型没有返回文本内容")
                return content.trim()
            }
            // 有工具调用：把 assistant 的 tool_calls 消息追加回去，逐个执行并回填结果
            current.put(message)
            for (tc in toolCalls) {
                val result = executeTool(tc.name, tc.arguments)
                current.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", tc.id)
                        .put("content", result)
                )
            }
        }
        throw RuntimeException("工具调用次数过多")
    }

    /** 清空记忆（内存 + 本地存储一起清），让大肥鱼"失忆" */
    fun clearHistory() {
        synchronized(lock) {
            history.clear()
            saveHistory()
        }
    }

    /** 判断是否已配置有效的 Key（中转站 Key 不一定 sk- 开头，非空即视为已配置） */
    private fun isKeyConfigured(): Boolean = apiKey.isNotBlank()

    /** 从 SharedPreferences 读取历史，App 重启后恢复记忆 */
    private fun loadHistory() {
        val json = prefs.getString(historyKey, null) ?: return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                history.add(obj.getString("role") to obj.getString("content"))
            }
            while (history.size > MAX_HISTORY) {
                history.removeAt(0)
            }
        }
    }

    /** 把历史序列化成 JSON 存到 SharedPreferences */
    private fun saveHistory() {
        val arr = JSONArray()
        for ((role, content) in history) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        prefs.edit().putString(historyKey, arr.toString()).apply()
    }

    /** 拼装 messages：system 人设（含当前时间）+ 历史对话 + 本轮用户输入（可能带天气/搜索数据） */
    private fun buildMessages(
        userText: String,
        weatherContext: String?,
        searchContext: String?
    ): JSONArray {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", "$systemPrompt\n\n当前时间：${currentTimeText()}")
        )
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val finalUser = StringBuilder(userText)
        if (!weatherContext.isNullOrBlank()) {
            finalUser.append("\n\n[实时天气数据：$weatherContext]")
        }
        if (!searchContext.isNullOrBlank()) {
            finalUser.append("\n\n[联网搜索结果（供参考，与问题无关可忽略）：\n$searchContext\n]")
        }
        messages.put(JSONObject().put("role", "user").put("content", finalUser.toString()))
        return messages
    }

    /** 拼装基础 messages：system 人设（含当前时间）+ 历史对话 + 本轮用户输入（Agent 模式用，不带预置数据） */
    private fun buildBaseMessages(userText: String): JSONArray {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", "$systemPrompt\n\n当前时间：${currentTimeText()}")
        )
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        return messages
    }

    /** 工具清单：查天气 / 联网搜索，交给模型自主调用 */
    private fun buildTools(): JSONArray = JSONArray().apply {
        put(
            JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "get_weather")
                    put("description", "查询指定城市的实时天气。当用户问天气、气温、冷不冷、热不热、会不会下雨、刮风等时调用。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("city", JSONObject().apply {
                                put("type", "string")
                                put("description", "城市名，例如：北京、上海。用户没说城市时可留空。")
                            })
                        })
                        put("required", JSONArray().apply { put("city") })
                    })
                })
            }
        )
        put(
            JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "web_search")
                    put("description", "联网搜索最新信息。当用户问需要查资料、新闻、百科、实时价格等自己不确定的事实性问题时调用。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("query", JSONObject().apply {
                                put("type", "string")
                                put("description", "搜索关键词。")
                            })
                        })
                        put("required", JSONArray().apply { put("query") })
                    })
                })
            }
        )
    }

    /** 执行某个工具，返回结果文本（不抛异常，失败返回提示文字，保证对话能继续） */
    private fun executeTool(name: String, argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrElse { JSONObject() }
        return when (name) {
            "get_weather" -> {
                val city = args.optString("city").ifBlank { defaultCity }
                if (city.isBlank()) "未指定城市，无法查询天气"
                else runCatching { fetchWeather(city) }
                    .getOrElse { "天气查询失败：${it.message}" }
            }
            "web_search" -> {
                val query = args.optString("query")
                if (query.isBlank()) "未指定搜索内容"
                else runCatching { fetchSearch(query) }
                    .getOrElse { "搜索失败：${it.message}" }
            }
            else -> "未知工具：$name"
        }
    }

    /** 从 message 里解析工具调用列表 */
    private fun parseToolCalls(message: JSONObject): List<ToolCall> {
        val arr = message.optJSONArray("tool_calls") ?: return emptyList()
        val list = mutableListOf<ToolCall>()
        for (i in 0 until arr.length()) {
            val tc = arr.getJSONObject(i)
            val fn = tc.optJSONObject("function") ?: continue
            list.add(
                ToolCall(
                    id = tc.optString("id"),
                    name = fn.optString("name"),
                    arguments = fn.optString("arguments")
                )
            )
        }
        return list
    }

    /** 一次 OpenAI Chat Completions 调用（可带 tools），返回 choices[0].message */
    private fun callOpenAI(messages: JSONArray, tools: JSONArray?): JSONObject {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.9)
                put("max_tokens", 200)
                put("stream", false)
                put("messages", messages)
                if (tools != null) put("tools", tools)
            }.toString()

            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                // 服务端不支持 function calling 时，回退到关键词模式
                if (tools != null && code == 400 && resp.lowercase().contains("tool")) {
                    throw ToolNotSupportedException()
                }
                throw RuntimeException("请求失败($code): $resp")
            }

            return JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
        } finally {
            conn.disconnect()
        }
    }

    /** 一次工具调用 */
    private data class ToolCall(val id: String, val name: String, val arguments: String)

    /** 服务端不支持 function calling 的标记异常（用于回退） */
    private class ToolNotSupportedException : RuntimeException("tools not supported")

    /** OpenAI Chat Completions 协议：DeepSeek 官方 / 大多数中转站 */
    private fun requestOpenAI(messages: JSONArray): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.9)
                put("max_tokens", 200)
                put("stream", false)
                put("messages", messages)
            }.toString()

            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw RuntimeException("请求失败($code): $resp")
            }

            return JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Anthropic Messages 协议：Claude 官方 / 兼容中转站（system 是顶层字段） */
    private fun requestAnthropic(messages: JSONArray): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")

            // system 从 messages 里抽出放顶层，其余照搬
            var system = ""
            val msgs = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                if (m.getString("role") == "system") {
                    system = m.getString("content")
                } else {
                    msgs.put(m)
                }
            }

            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 200)
                put("system", system)
                put("messages", msgs)
            }.toString()

            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                throw RuntimeException("请求失败($code): $resp")
            }

            val content = JSONObject(resp).getJSONArray("content")
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") {
                    return block.getString("text").trim()
                }
            }
            throw RuntimeException("响应里没有文本内容")
        } finally {
            conn.disconnect()
        }
    }
}
