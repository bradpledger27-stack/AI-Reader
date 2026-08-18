package com.aireader.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var aiVoicePlayer: AiVoicePlayer

    private var speechRate = 1.0f
    private var currentReaderText = ""

    private var playbackStatus by
    mutableStateOf("Ready")

    private val prefs by lazy {
        getSharedPreferences(
            "ai_reader",
            MODE_PRIVATE
        )
    }

    private val savedTextFile by lazy {
        File(filesDir, "saved_reader_text.txt")
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        speechRate =
            prefs.getFloat(
                "speech_rate",
                1.0f
            )

        currentReaderText =
            try {
                if (savedTextFile.exists()) {
                    savedTextFile.readText()
                } else {
                    ""
                }
            } catch (_: Throwable) {
                ""
            }

        aiVoicePlayer =
            AiVoicePlayer(
                context = this,
                onStatusChanged = { status ->
                    playbackStatus = status
                }
            )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AIReaderScreen(
                        initialText =
                            currentReaderText,
                        initialSpeed =
                            speechRate,
                        playbackStatus =
                            playbackStatus,

                        onTextChanged = { text ->
                            currentReaderText = text
                        },

                        onPlay = { text ->
                            aiVoicePlayer.speak(
                                text = text,
                                speed = speechRate
                            )
                        },

                        onPause = {
                            aiVoicePlayer.pause()
                        },

                        onResume = {
                            aiVoicePlayer.resume()
                        },

                        onStop = {
                            aiVoicePlayer.stop()
                        },

                        onTestAiVoice = {
                            aiVoicePlayer
                                .speakTestSentence()
                        },

                        onSpeedChanged = { speed ->
                            speechRate = speed

                            prefs.edit()
                                .putFloat(
                                    "speech_rate",
                                    speed
                                )
                                .apply()
                        }
                    )
                }
            }
        }
    }

    override fun onStop() {
        try {
            savedTextFile.writeText(
                currentReaderText
            )
        } catch (_: Throwable) {
            // Keep the app running if saving fails.
        }

        super.onStop()
    }

    override fun onDestroy() {
        if (::aiVoicePlayer.isInitialized) {
            aiVoicePlayer.release()
        }

        super.onDestroy()
    }
}

@Composable
fun AIReaderScreen(
    initialText: String,
    initialSpeed: Float,
    playbackStatus: String,
    onTextChanged: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onTestAiVoice: () -> Unit,
    onSpeedChanged: (Float) -> Unit
) {
    val context = LocalContext.current

    var text by remember {
        mutableStateOf(initialText)
    }

    var playerState by remember {
        mutableIntStateOf(0)
    }

    var speed by remember {
        mutableStateOf(initialSpeed)
    }

    val filePicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                try {
                    val importedText =
                        context.contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { reader ->
                                reader.readText()
                            }
                            .orEmpty()

                    if (importedText.isBlank()) {
                        Toast.makeText(
                            context,
                            "The selected file is empty.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        text = importedText
                        onTextChanged(importedText)

                        Toast.makeText(
                            context,
                            "Text file imported.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (error: Throwable) {
                    Toast.makeText(
                        context,
                        "Could not open file: " +
                                error.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    LaunchedEffect(playbackStatus) {
        playerState =
            when (playbackStatus) {
                "Reading" -> 1
                "Preparing voice…" -> 1
                "Preparing next section…" -> 1
                "Paused" -> 2
                else -> 0
            }
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
            text = "Free offline AI voice"
        )

        Text(
            text =
                "English (United States) • Lessac"
        )

        Surface(
            color =
                MaterialTheme.colorScheme
                    .secondaryContainer,
            shape =
                MaterialTheme.shapes.medium,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Status: $playbackStatus",
                modifier =
                    Modifier.padding(12.dp),
                style =
                    MaterialTheme.typography
                        .bodyMedium
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                onTextChanged(newText)
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = {
                Text("Text to read")
            },
            placeholder = {
                Text(
                    "Enter, paste, or import text"
                )
            }
        )

        OutlinedButton(
            onClick = {
                filePicker.launch(
                    arrayOf("text/plain")
                )
            },
            enabled = playerState == 0,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text("📄 Import Text File")
        }

        OutlinedButton(
            onClick = onTestAiVoice,
            enabled = playerState == 0,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text("Test Offline AI Voice")
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
            enabled = playerState == 0,
            valueRange = 0.5f..2.0f,
            steps = 14
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onPlay(text)
                    }
                },
                enabled =
                    text.isNotBlank() &&
                            playerState == 0,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("▶ Play")
            }

            Button(
                onClick = onPause,
                enabled = playerState == 1,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("⏸ Pause")
            }
        }

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onResume,
                enabled = playerState == 2,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("▶ Resume")
            }

            Button(
                onClick = onStop,
                enabled = playerState != 0,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("■ Stop")
            }
        }
    }
}