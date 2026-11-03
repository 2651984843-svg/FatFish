package com.example.fatfish.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * 16k 单声道 PCM 录音器，录音结果喂给云端 ASR（讯飞等）。
 *
 * 为什么不用系统的 SpeechRecognizer：
 * 无 GMS 的国行魔改表（OPPO Watch 等）上 Google 语音识别常常不可用，
 * 直接录 PCM 上传云端识别是最稳的方案。
 */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    @Volatile
    private var recording = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    fun start() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, 2048)
        synchronized(pcm) { pcm.reset() }

        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        recording = true
        record?.startRecording()

        thread = Thread {
            val buf = ByteArray(bufSize)
            while (recording) {
                val n = record?.read(buf, 0, buf.size) ?: -1
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }.apply { start() }
    }

    /** 停止录音并返回整段 PCM 数据 */
    fun stop(): ByteArray {
        recording = false
        thread?.join(800)
        thread = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        return synchronized(pcm) { pcm.toByteArray() }
    }
}
