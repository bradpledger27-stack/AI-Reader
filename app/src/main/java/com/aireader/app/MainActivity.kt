package com.aireader.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var aiVoicePlayer: AiVoicePlayer
    private var ttsReady = false

    private var chunks: List<String> = emptyList()
    private var currentChunk = 0
    private var paused = false

    private var speechRate = 1.0f
    private var speechPitch = 1.0f

    private var availableVoices by mutableStateOf<List<Voice>>(emptyList())
    private var selectedVoiceName by mutableStateOf<String?>(null)

    private val prefs by lazy {
        getSharedPreferences("ai_reader", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aiVoicePlayer = AiVoicePlayer(this)
        speechRate = prefs.getFloat("speech_rate", 1.0f)
        speechPitch = prefs.getFloat("speech_pitch", 1.0f)
        selectedVoiceName = prefs.getString("voice_name", null)

        textToSpeech = TextToSpeech(this, this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AIReaderScreen(
                        initialSpeed = speechRate,
                        initialPitch = speechPitch,
                        voices = availableVoices,
                        selectedVoiceName = selectedVoiceName,

                        onVoiceSelected = { voice ->
                            selectVoice(voice)
                        },

                        onTestAiVoice = {
                        aiVoicePlayer.speakTestSentence()
                        },

                        onPlay = { text ->
                            startReading(text)
                        },

                        onPause = {
                            pauseReading()
                        },

                        onResume = {
                            resumeReading()
                        },

                        onStop = {
                            stopReading()
                        },

                        onSpeedChanged = { speed ->
                            speechRate = speed

                            if (ttsReady) {
                                textToSpeech.setSpeechRate(speed)
                            }

                            prefs.edit()
                                .putFloat("speech_rate", speed)
                                .apply()
                        },

                        onPitchChanged = { pitch ->
                            speechPitch = pitch

                            if (ttsReady) {
                                textToSpeech.setPitch(pitch)
                            }

                            prefs.edit()
                                .putFloat("speech_pitch", pitch)
                                .apply()
                        }
                    )
                }
            }
        }
    }

    override fun onInit(status: Int) {

        if (status != TextToSpeech.SUCCESS) {
            return
        }

        val languageResult =
            textToSpeech.setLanguage(Locale.getDefault())

        ttsReady =
            languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED

        if (!ttsReady) {
            return
        }

        textToSpeech.setSpeechRate(speechRate)
        textToSpeech.setPitch(speechPitch)

        availableVoices =
            textToSpeech.voices
                ?.filter { voice ->
                    voice.locale.language.equals(
                        "en",
                        ignoreCase = true
                    )
                }
                ?.sortedWith(
                    compareBy<Voice>(
                        { voicePriority(it) },
                        { it.locale.displayCountry },
                        { it.name }
                    )
                )
                ?: emptyList()

        restoreVoice()

        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {

                override fun onStart(utteranceId: String?) {
                }

                override fun onDone(utteranceId: String?) {

                    if (!paused) {

                        currentChunk++

                        if (currentChunk < chunks.size) {
                            speakCurrentChunk()
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                }
            }
        )
    }

    private fun voicePriority(voice: Voice): Int {

        val country =
            voice.locale.country.uppercase(Locale.ROOT)

        return when (country) {
            "NZ" -> 0
            "AU" -> 1
            "GB" -> 2
            "US" -> 3
            else -> 4
        }
    }

    private fun restoreVoice() {

        val savedName = selectedVoiceName

        val savedVoice =
            availableVoices.firstOrNull {
                it.name == savedName
            }

        if (savedVoice != null) {
            textToSpeech.voice = savedVoice
            selectedVoiceName = savedVoice.name
            return
        }

        val nzVoice =
            availableVoices.firstOrNull {
                it.locale.country.equals(
                    "NZ",
                    ignoreCase = true
                )
            }

        val defaultVoice =
            nzVoice ?: availableVoices.firstOrNull()

        if (defaultVoice != null) {
            selectVoice(defaultVoice)
        }
    }

    private fun selectVoice(voice: Voice) {

        if (!ttsReady) {
            return
        }

        val result = textToSpeech.setVoice(voice)

        if (result == TextToSpeech.SUCCESS) {

            selectedVoiceName = voice.name

            prefs.edit()
                .putString("voice_name", voice.name)
                .apply()
        }
    }

    private fun startReading(text: String) {

        if (!ttsReady || text.isBlank()) {
            return
        }

        textToSpeech.stop()

        chunks = splitText(text)
        currentChunk = 0
        paused = false

        speakCurrentChunk()
    }

    private fun pauseReading() {

        if (!ttsReady || chunks.isEmpty()) {
            return
        }

        paused = true
        textToSpeech.stop()
    }

    private fun resumeReading() {

        if (!ttsReady || chunks.isEmpty()) {
            return
        }

        paused = false
        speakCurrentChunk()
    }

    private fun stopReading() {

        paused = false
        currentChunk = 0
        chunks = emptyList()

        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
        }
    }

    private fun speakCurrentChunk() {

        if (
            !ttsReady ||
            paused ||
            currentChunk !in chunks.indices
        ) {
            return
        }

        textToSpeech.setSpeechRate(speechRate)
        textToSpeech.setPitch(speechPitch)

        textToSpeech.speak(
            chunks[currentChunk],
            TextToSpeech.QUEUE_FLUSH,
            null,
            "AI_READER_$currentChunk"
        )
    }

    private fun splitText(text: String): List<String> {

        val maximumLength = 3000

        if (text.length <= maximumLength) {
            return listOf(text)
        }

        val result = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.isNotEmpty()) {

            if (remaining.length <= maximumLength) {
                result.add(remaining)
                break
            }

            var splitPosition =
                remaining.lastIndexOf(
                    '.',
                    maximumLength
                )

            if (splitPosition < maximumLength / 2) {
                splitPosition =
                    remaining.lastIndexOf(
                        ' ',
                        maximumLength
                    )
            }

            if (splitPosition <= 0) {
                splitPosition = maximumLength
            } else {
                splitPosition++
            }

            result.add(
                remaining.substring(
                    0,
                    splitPosition
                ).trim()
            )

            remaining =
                remaining.substring(splitPosition)
                    .trim()
        }

        return result
    }

    override fun onDestroy() {

        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }

        if (::aiVoicePlayer.isInitialized) {
        aiVoicePlayer.release()
    }

        super.onDestroy()
    }
    }

@Composable
fun AIReaderScreen(
    initialSpeed: Float,
    initialPitch: Float,
    voices: List<Voice>,
    selectedVoiceName: String?,
    onVoiceSelected: (Voice) -> Unit,
    onTestAiVoice: () -> Unit,
    onPlay: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onPitchChanged: (Float) -> Unit
) {

    var text by remember {
        mutableStateOf("")
    }

    var playerState by remember {
        mutableIntStateOf(0)
    }

    var speed by remember {
        mutableStateOf(initialSpeed)
    }

    var pitch by remember {
        mutableStateOf(initialPitch)
    }

    var voiceMenuOpen by remember {
        mutableStateOf(false)
    }

    val selectedVoice =
        voices.firstOrNull {
            it.name == selectedVoiceName
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {

        Text(
            text = "AI Reader",
            style =
                MaterialTheme.typography.headlineLarge
        )

        Text(
            text = "Enter or paste text below"
        )

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = {
                Text("Text to read")
            }
        )

        OutlinedButton(
        onClick = onTestAiVoice,
        modifier = Modifier.fillMaxWidth()
)       {
    Text("Test Offline AI Voice")
}

        Text(
            text = "Voice"
        )

        OutlinedButton(
            onClick = {
                voiceMenuOpen = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {

            Text(
                text =
                    selectedVoice?.let {
                        voiceDisplayName(it)
                    } ?: "Select voice"
            )
        }

        DropdownMenu(
            expanded = voiceMenuOpen,
            onDismissRequest = {
                voiceMenuOpen = false
            }
        ) {

            voices.forEach { voice ->

                DropdownMenuItem(
                    text = {
                        Text(
                            text = voiceDisplayName(voice)
                        )
                    },
                    onClick = {
                        onVoiceSelected(voice)
                        voiceMenuOpen = false
                    }
                )
            }
        }

        Text(
            text = "Speed: %.1fx".format(speed)
        )

        Slider(
            value = speed,
            onValueChange = {
                speed = it
                onSpeedChanged(it)
            },
            valueRange = 0.5f..2.0f,
            steps = 14
        )

        Text(
            text = "Pitch: %.1f".format(pitch)
        )

        Slider(
            value = pitch,
            onValueChange = {
                pitch = it
                onPitchChanged(it)
            },
            valueRange = 0.5f..1.5f,
            steps = 9
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            Button(
                onClick = {
                    onPlay(text)
                    playerState = 1
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("▶ Play")
            }

            Button(
                onClick = {
                    onPause()
                    playerState = 2
                },
                enabled = playerState == 1,
                modifier = Modifier.weight(1f)
            ) {
                Text("⏸ Pause")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            Button(
                onClick = {
                    onResume()
                    playerState = 1
                },
                enabled = playerState == 2,
                modifier = Modifier.weight(1f)
            ) {
                Text("▶ Resume")
            }

            Button(
                onClick = {
                    onStop()
                    playerState = 0
                },
                enabled = playerState != 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("■ Stop")
            }
        }
    }
}

fun voiceDisplayName(voice: Voice): String {

    val locale = voice.locale

    val location =
        when {
            locale.displayCountry.isNotBlank() ->
                "${locale.displayLanguage} (${locale.displayCountry})"

            else ->
                locale.displayLanguage
        }

    val connection =
        if (voice.isNetworkConnectionRequired) {
            "Online"
        } else {
            "Offline"
        }

    return "$location • $connection • ${voice.name}"
}