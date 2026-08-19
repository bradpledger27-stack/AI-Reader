package com.aireader.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

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

    private val ttsLock = Any()
    @Volatile
    private var selectedVoice =
        AiVoiceOption.LESSAC

    @Volatile
    private var generationNumber = 0

    private fun updateStatus(
        status: String
    ) {
        Handler(Looper.getMainLooper()).post {
            onStatusChanged(status)
        }
    }

    fun selectVoice(
        voice: AiVoiceOption
    ) {
        if (voice == selectedVoice) {
            return
        }

        generationNumber++
        stopAudio()

        synchronized(ttsLock) {
            offlineTts?.release()
            offlineTts = null
            selectedVoice = voice
        }

        chunks = emptyList()
        currentChunk = 0
        paused = false

        updateStatus(
            "Voice selected: ${voice.displayName}"
        )
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
                val audio =
                    synchronized(ttsLock) {
                        val tts =
                            offlineTts
                                ?: createTts().also {
                                    offlineTts = it
                                }

                        tts.generate(
                            text = text,
                            sid = 0,
                            speed = currentSpeed
                        )
                    }

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

    fun saveAsWav(
        text: String,
        speed: Float,
        destination: Uri
    ) {
        if (text.isBlank()) {
            return
        }

        generationNumber++
        stopAudio()
        paused = false

        val exportGeneration =
            generationNumber

        updateStatus("Generating audio…")

        Thread {
            val temporaryPcmFile =
                File(
                    context.cacheDir,
                    "ai-reader-${System.nanoTime()}.pcm"
                )

            try {
                val exportChunks =
                    splitText(text)

                var sampleRate = 0
                var completedChunks = 0

                BufferedOutputStream(
                    FileOutputStream(
                        temporaryPcmFile
                    )
                ).use { pcmOutput ->

                    exportChunks.forEach { chunk ->
                        if (
                            exportGeneration !=
                            generationNumber
                        ) {
                            return@Thread
                        }

                        val audio =
                            synchronized(ttsLock) {
                                val tts =
                                    offlineTts
                                        ?: createTts().also {
                                            offlineTts = it
                                        }

                                tts.generate(
                                    text = chunk,
                                    sid = 0,
                                    speed = speed
                                )
                            }

                        if (sampleRate == 0) {
                            sampleRate =
                                audio.sampleRate
                        }

                        writePcm16Samples(
                            output = pcmOutput,
                            samples = audio.samples
                        )

                        completedChunks++

                        updateStatus(
                            "Generating audio " +
                                    "$completedChunks/" +
                                    "${exportChunks.size}…"
                        )
                    }
                }

                if (
                    exportGeneration != generationNumber
                ) {
                    return@Thread
                }

                val outputStream =
                    context.contentResolver
                        .openOutputStream(
                            destination,
                            "w"
                        )
                        ?: throw IllegalStateException(
                            "Could not create the audio file."
                        )

                outputStream.use { output ->
                    writeWavHeader(
                        output = output,
                        sampleRate = sampleRate,
                        dataSize =
                            temporaryPcmFile.length()
                    )

                    temporaryPcmFile
                        .inputStream()
                        .use { input ->
                            input.copyTo(output)
                        }

                    output.flush()
                }

                updateStatus("Audio saved")
            } catch (error: Throwable) {
                showError(error)
            } finally {
                temporaryPcmFile.delete()
            }
        }.start()
    }

    private fun writePcm16Samples(
        output: OutputStream,
        samples: FloatArray
    ) {
        val buffer =
            ByteArray(samples.size * 2)

        var bufferPosition = 0

        samples.forEach { sample ->
            val pcmValue =
                (
                        sample.coerceIn(
                            -1.0f,
                            1.0f
                        ) * 32767.0f
                        ).toInt()

            buffer[bufferPosition] =
                (pcmValue and 0xff).toByte()

            buffer[bufferPosition + 1] =
                (
                        pcmValue shr 8 and 0xff
                        ).toByte()

            bufferPosition += 2
        }

        output.write(buffer)
    }

    private fun writeWavHeader(
        output: OutputStream,
        sampleRate: Int,
        dataSize: Long
    ) {
        output.write(
            "RIFF".toByteArray(Charsets.US_ASCII)
        )

        writeIntLittleEndian(
            output,
            36L + dataSize
        )

        output.write(
            "WAVE".toByteArray(Charsets.US_ASCII)
        )

        output.write(
            "fmt ".toByteArray(Charsets.US_ASCII)
        )

        writeIntLittleEndian(output, 16)
        writeShortLittleEndian(output, 1)
        writeShortLittleEndian(output, 1)

        writeIntLittleEndian(
            output,
            sampleRate.toLong()
        )

        writeIntLittleEndian(
            output,
            sampleRate.toLong() * 2L
        )

        writeShortLittleEndian(output, 2)
        writeShortLittleEndian(output, 16)

        output.write(
            "data".toByteArray(Charsets.US_ASCII)
        )

        writeIntLittleEndian(
            output,
            dataSize
        )
    }

    private fun writeIntLittleEndian(
        output: OutputStream,
        value: Long
    ) {
        output.write(
            (value and 0xff).toInt()
        )

        output.write(
            (value shr 8 and 0xff).toInt()
        )

        output.write(
            (value shr 16 and 0xff).toInt()
        )

        output.write(
            (value shr 24 and 0xff).toInt()
        )
    }

    private fun writeShortLittleEndian(
        output: OutputStream,
        value: Int
    ) {
        output.write(value and 0xff)

        output.write(
            value shr 8 and 0xff
        )
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
            destination.parentFile?.mkdirs()

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
                    selectedVoice.modelPath,
                tokens =
                    "tts/tokens.txt",
                dataDir =
                    espeakDataDirectory.absolutePath
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
        generationNumber++
        stopAudio()

        synchronized(ttsLock) {
            offlineTts?.release()
            offlineTts = null
        }
    }
}