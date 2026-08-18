package com.aireader.app

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

class AiVoicePlayer(
    private val context: Context
) {

    private var offlineTts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    fun speakTestSentence() {
        Thread {
            try {
            val tts = offlineTts ?: createTts().also {
                offlineTts = it
            }

            val audio = tts.generate(
                text = "Hello. This is your new offline AI voice.",
                sid = 0,
                speed = 1.0f
            )

            audioTrack?.stop()
            audioTrack?.release()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(
                            AudioFormat.CHANNEL_OUT_MONO
                        )
                        .build()
                )
                .setBufferSizeInBytes(audio.samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { track ->
                    track.write(
                        audio.samples,
                        0,
                        audio.samples.size,
                        AudioTrack.WRITE_BLOCKING
                    )
                    track.play()
                }
                } catch (error: Throwable) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(
            context,
            "AI voice error: ${error.javaClass.simpleName}: ${error.message}",
            Toast.LENGTH_LONG
        ).show()
    }
}
        }.start()
    }

    private fun createTts(): OfflineTts {
        val vitsConfig =
            OfflineTtsVitsModelConfig(
                model = "tts/en_US-lessac-medium.onnx",
                tokens = "tts/tokens.txt",
                dataDir = "tts/espeak-ng-data"
            )

        val modelConfig =
            OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = true,
                provider = "cpu"
            )

        return OfflineTts(
            assetManager = context.assets,
            config = OfflineTtsConfig(
                model = modelConfig
            )
        )
    }

    fun release() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        offlineTts?.release()
        offlineTts = null
    }
}