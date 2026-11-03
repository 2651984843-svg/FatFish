package com.example.fatfish

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

/**
 * 手表本地语音识别（ASR）。
 *
 * 用 sherpa-onnx 的 Paraformer 中文小模型在 CPU 上离线识别，
 * 完全不经过第三方云服务，也不需要 GPU/CUDA。
 *
 * 模型文件放在 app/src/main/assets/asr/ 下：
 *   - asr/model.int8.onnx   （Paraformer-zh-small int8，约 70MB，省内存）
 *   - asr/tokens.txt
 */
class SherpaAsr(context: Context) {

    private val recognizer: OfflineRecognizer

    init {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "asr/model.int8.onnx",
                ),
                tokens = "asr/tokens.txt",
                modelType = "paraformer",
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        // 传 assetManager 让 sherpa-onnx 直接从 APK 的 assets 里加载模型
        recognizer = OfflineRecognizer(assetManager = context.assets, config = config)
    }

    /**
     * 把 16k 单声道 PCM16 录音转成文字。
     * [pcm16] 是 AudioRecorder 采集的原始字节（little-endian）。
     */
    fun recognize(pcm16: ByteArray): String {
        // PCM16 -> FloatArray（sherpa-onnx 需要 -1.0 ~ 1.0 的浮点采样）
        val samples = FloatArray(pcm16.size / 2) { i ->
            val lo = pcm16[2 * i].toInt() and 0xFF
            val hi = pcm16[2 * i + 1].toInt()
            val v = (hi shl 8) or lo
            v / 32768.0f
        }

        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate = 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return result.text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer.release()
    }
}
