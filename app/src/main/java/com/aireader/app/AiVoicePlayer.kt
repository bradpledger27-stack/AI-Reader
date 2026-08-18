package com.aireader.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

class AiVoicePlayer(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit = {}
) {
    private var offlineTts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    private var chunks: List<String> = emptyList()
    private var currentChunk = 0
    private var currentSpeed = 1.0f
    private var paused = false

    @Volatile
    private var generationNumber = 0

    private fun updateStatus(
        status: String
    ) {
        Handler(Looper.getMainLooper()).post {
            onStatusChanged(status)
        }
    }

    fun speakTestSentence() {
        speak(
            text =
                "Hello. This is your new offline AI voice.",
            speed = 1.0f
        )
    }

    fun speak(
        text: String,
        speed: Float
    ) {
        if (text.isBlank()) {
            return
        }

        updateStatus("Preparing voice…")

        generationNumber++
        stopAudio()

        chunks = splitText(text)
        currentChunk = 0
        currentSpeed = speed
        paused = false

        generateCurrentChunk(generationNumber)
    }

    private fun generateCurrentChunk(
        expectedGeneration: Int
    ) {
        if (
            expectedGeneration != generationNumber ||
            currentChunk !in chunks.indices
        ) {
            return
        }

        val text = chunks[currentChunk]

        Thread {
            try {
                val tts =
                    offlineTts ?: createTts().also {
                        offlineTts = it
                    }

                val audio = tts.generate(
                    text = text,
                    sid = 0,
                    speed = currentSpeed
                )

                if (
                    expectedGeneration != generationNumber
                ) {
                    return@Thread
                }

                val bufferSize =
                    audio.samples.size *
                            Float.SIZE_BYTES

                val newTrack =
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(
                                    AudioAttributes.USAGE_MEDIA
                                )
                                .setContentType(
                                    AudioAttributes
                                        .CONTENT_TYPE_SPEECH
                                )
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(
                                    AudioFormat
                                        .ENCODING_PCM_FLOAT
                                )
                                .setSampleRate(
                                    audio.sampleRate
                                )
                                .setChannelMask(
                                    AudioFormat
                                        .CHANNEL_OUT_MONO
                                )
                                .build()
                        )
                        .setBufferSizeInBytes(
                            bufferSize
                        )
                        .setTransferMode(
                            AudioTrack.MODE_STATIC
                        )
                        .build()

                if (
                    expectedGeneration != generationNumber
                ) {
                    newTrack.release()
                    return@Thread
                }

                newTrack.write(
                    audio.samples,
                    0,
                    audio.samples.size,
                    AudioTrack.WRITE_BLOCKING
                )

                newTrack.notificationMarkerPosition =
                    audio.samples.size

                newTrack.setPlaybackPositionUpdateListener(
                    object :
                        AudioTrack.OnPlaybackPositionUpdateListener {

                        override fun onMarkerReached(
                            track: AudioTrack?
                        ) {
                            if (
                                expectedGeneration !=
                                generationNumber
                            ) {
                                return
                            }

                            if (
                                audioTrack === newTrack
                            ) {
                                audioTrack = null
                            }

                            newTrack.release()
                            currentChunk++

                            if (
                                currentChunk <
                                chunks.size
                            ) {
                                updateStatus(
                                    "Preparing next section…"
                                )

                                generateCurrentChunk(
                                    expectedGeneration
                                )
                            } else {
                                updateStatus("Finished")
                            }
                        }

                        override fun onPeriodicNotification(
                            track: AudioTrack?
                        ) {
                        }
                    },
                    Handler(Looper.getMainLooper())
                )

                audioTrack = newTrack

                if (paused) {
                    updateStatus("Paused")
                } else {
                    newTrack.play()
                    updateStatus("Reading")
                }
            } catch (error: Throwable) {
                showError(error)
            }
        }.start()
    }

    fun pause() {
        paused = true

        val track = audioTrack

        if (
            track != null &&
            track.playState ==
            AudioTrack.PLAYSTATE_PLAYING
        ) {
            track.pause()
        }

        updateStatus("Paused")
    }

    fun resume() {
        paused = false

        val track = audioTrack

        if (
            track != null &&
            (
                    track.playState ==
                            AudioTrack.PLAYSTATE_PAUSED ||
                            track.playState ==
                            AudioTrack.PLAYSTATE_STOPPED
                    )
        ) {
            track.play()
            updateStatus("Reading")
        } else if (track == null) {
            updateStatus("Preparing voice…")
        }
    }

    fun stop() {
        generationNumber++
        paused = false
        chunks = emptyList()
        currentChunk = 0

        stopAudio()
        updateStatus("Stopped")
    }

    private fun stopAudio() {
        val track = audioTrack
        audioTrack = null

        if (track != null) {
            try {
                track.stop()
            } catch (_: IllegalStateException) {
                // The track may not have started.
            }

            track.release()
        }
    }

    private fun splitText(
        text: String
    ): List<String> {
        val maximumLength = 400
        val result = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.isNotEmpty()) {
            if (
                remaining.length <=
                maximumLength
            ) {
                result.add(remaining)
                break
            }

            var splitPosition =
                remaining.lastIndexOf(
                    '.',
                    maximumLength
                )

            if (
                splitPosition <
                maximumLength / 3
            ) {
                splitPosition =
                    remaining.lastIndexOf(
                        '?',
                        maximumLength
                    )
            }

            if (
                splitPosition <
                maximumLength / 3
            ) {
                splitPosition =
                    remaining.lastIndexOf(
                        '!',
                        maximumLength
                    )
            }

            if (
                splitPosition <
                maximumLength / 3
            ) {
                splitPosition =
                    remaining.lastIndexOf(
                        ' ',
                        maximumLength
                    )
            }

            if (splitPosition <= 0) {
                splitPosition =
                    maximumLength
            } else {
                splitPosition++
            }

            val chunk =
                remaining.substring(
                    0,
                    splitPosition
                ).trim()

            if (chunk.isNotEmpty()) {
                result.add(chunk)
            }

            remaining =
                remaining.substring(
                    splitPosition
                ).trim()
        }

        return result
    }

    private fun showError(
        error: Throwable
    ) {
        updateStatus("Error")

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                "AI voice error: " +
                        "${error.javaClass.simpleName}: " +
                        "${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun prepareEspeakData(): File {
        val outputDirectory =
            File(
                context.filesDir,
                "espeak-ng-data"
            )

        val completedMarker =
            File(
                outputDirectory,
                ".copy-complete"
            )

        if (!completedMarker.exists()) {
            copyAssetDirectory(
                assetPath =
                    "tts/espeak-ng-data",
                destination =
                    outputDirectory
            )

            completedMarker.writeText(
                "complete"
            )
        }

        return outputDirectory
    }

    private fun copyAssetDirectory(
        assetPath: String,
        destination: File
    ) {
        val entries =
            context.assets
                .list(assetPath)
                .orEmpty()

        if (entries.isEmpty()) {
            destination.parentFile
                ?.mkdirs()

            context.assets
                .open(assetPath)
                .use { input ->

                    destination
                        .outputStream()
                        .use { output ->

                            input.copyTo(output)
                        }
                }
        } else {
            destination.mkdirs()

            entries.forEach { entry ->
                copyAssetDirectory(
                    assetPath =
                        "$assetPath/$entry",
                    destination =
                        File(
                            destination,
                            entry
                        )
                )
            }
        }
    }

    private fun createTts(): OfflineTts {
        val espeakDataDirectory =
            prepareEspeakData()

        val vitsConfig =
            OfflineTtsVitsModelConfig(
                model =
                    "tts/en_US-lessac-medium.onnx",
                tokens =
                    "tts/tokens.txt",
                dataDir =
                    espeakDataDirectory
                        .absolutePath
            )

        val modelConfig =
            OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = true,
                provider = "cpu"
            )

        return OfflineTts(
            assetManager =
                context.assets,
            config =
                OfflineTtsConfig(
                    model = modelConfig
                )
        )
    }

    fun release() {
        generationNumber++
        stopAudio()

        offlineTts?.release()
        offlineTts = null
    }
}