package com.aireader.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import android.net.Uri
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var aiVoicePlayer: AiVoicePlayer

    private var speechRate = 1.0f
    private var currentReaderText = ""

    private var selectedVoice by
    mutableStateOf(AiVoiceOption.LESSAC)

    private var playbackStatus by
    mutableStateOf("Ready")

    private val prefs by lazy {
        getSharedPreferences(
            "ai_reader",
            MODE_PRIVATE
        )
    }

    private val savedTextFile by lazy {
        File(
            filesDir,
            "saved_reader_text.txt"
        )
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

        selectedVoice =
            try {
                AiVoiceOption.valueOf(
                    prefs.getString(
                        "selected_ai_voice",
                        AiVoiceOption.LESSAC.name
                    ) ?: AiVoiceOption.LESSAC.name
                )
            } catch (_: Throwable) {
                AiVoiceOption.LESSAC
            }

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

        aiVoicePlayer.selectVoice(
            selectedVoice
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
                        selectedVoice =
                            selectedVoice,
                        playbackStatus =
                            playbackStatus,

                        onTextChanged = { text ->
                            currentReaderText = text
                        },

                        onVoiceSelected = { voice ->
                            selectedVoice = voice

                            aiVoicePlayer.selectVoice(
                                voice
                            )

                            prefs.edit()
                                .putString(
                                    "selected_ai_voice",
                                    voice.name
                                )
                                .apply()
                        },

                        onPlay = { text ->
                            aiVoicePlayer.speak(
                                text = text,
                                speed = speechRate
                            )
                        },

                        onSaveAudio = { text, uri ->
                            aiVoicePlayer.saveAsWav(
                                text = text,
                                speed = speechRate,
                                destination = uri
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
            // Keep running if saving fails.
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
    selectedVoice: AiVoiceOption,
    playbackStatus: String,
    onTextChanged: (String) -> Unit,
    onVoiceSelected: (AiVoiceOption) -> Unit,
    onPlay: (String) -> Unit,
    onSaveAudio: (String, Uri) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onTestAiVoice: () -> Unit,
    onSpeedChanged: (Float) -> Unit
) {
    val context = LocalContext.current

    val photoTextRecognizer =
        remember {
            PhotoTextRecognizer(context)
        }

    DisposableEffect(Unit) {
        onDispose {
            photoTextRecognizer.close()
        }
    }

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText
            )
        )
    }

    var playerState by remember {
        mutableIntStateOf(0)
    }

    var speed by remember {
        mutableStateOf(initialSpeed)
    }

    var voiceMenuOpen by remember {
        mutableStateOf(false)
    }

    var audioTextToSave by remember {
        mutableStateOf("")
    }

    var suggestedAudioName by remember {
        mutableStateOf(
            "AI-Reader-Audio.wav"
        )
    }

    var cameraImageUri by remember {
        mutableStateOf<Uri?>(null)
    }

    var scanningPhoto by remember {
        mutableStateOf(false)
    }

    val hasSelection =
        !textFieldValue.selection.collapsed

    val selectedText =
        if (hasSelection) {
            textFieldValue.text.substring(
                textFieldValue.selection.min,
                textFieldValue.selection.max
            )
        } else {
            ""
        }

    val textFilePicker =
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
                        textFieldValue =
                            TextFieldValue(
                                text = importedText
                            )

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

    val audioFilePicker =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts
                    .CreateDocument("audio/wav")
        ) { uri ->
            if (
                uri != null &&
                audioTextToSave.isNotBlank()
            ) {
                onSaveAudio(
                    audioTextToSave,
                    uri
                )
            }
        }

    val cameraLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.TakePicture()
        ) { pictureSaved ->
            val imageUri = cameraImageUri

            if (
                pictureSaved &&
                imageUri != null
            ) {
                scanningPhoto = true

                photoTextRecognizer.recognize(
                    imageUri = imageUri,

                    onSuccess = { recognizedText ->
                        scanningPhoto = false

                        if (recognizedText.isBlank()) {
                            Toast.makeText(
                                context,
                                "No text was found in the photo.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val combinedText =
                                if (
                                    textFieldValue.text
                                        .isBlank()
                                ) {
                                    recognizedText
                                } else {
                                    textFieldValue.text +
                                            "\n\n" +
                                            recognizedText
                                }

                            textFieldValue =
                                TextFieldValue(
                                    text = combinedText,
                                    selection =
                                        TextRange(
                                            combinedText.length
                                        )
                                )

                            onTextChanged(combinedText)

                            Toast.makeText(
                                context,
                                "Photo text added.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },

                    onError = { error ->
                        scanningPhoto = false

                        Toast.makeText(
                            context,
                            "Could not read photo: " +
                                    error.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } else {
                scanningPhoto = false
            }
        }

    LaunchedEffect(playbackStatus) {
        playerState =
            when {
                playbackStatus == "Paused" ->
                    2

                playbackStatus.startsWith(
                    "Generating audio"
                ) ->
                    3

                playbackStatus == "Reading" ->
                    1

                playbackStatus.startsWith(
                    "Preparing"
                ) ->
                    1

                else ->
                    0
            }
    }

    val screenBusy =
        playerState != 0 ||
                scanningPhoto

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement =
            Arrangement.spacedBy(10.dp)
    )
    {
        Text(
            text = "AI Reader",
            style =
                MaterialTheme.typography
                    .headlineLarge
        )

        Text(
            text = "Offline AI voice"
        )

        OutlinedButton(
            onClick = {
                voiceMenuOpen = true
            },
            enabled = !screenBusy,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(
                selectedVoice.displayName
            )
        }

        DropdownMenu(
            expanded = voiceMenuOpen,
            onDismissRequest = {
                voiceMenuOpen = false
            }
        ) {
            AiVoiceOption.values()
                .forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                voice.displayName
                            )
                        },
                        onClick = {
                            onVoiceSelected(voice)
                            voiceMenuOpen = false
                        }
                    )
                }
        }

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
                text =
                    if (scanningPhoto) {
                        "Status: Reading photo…"
                    } else {
                        "Status: $playbackStatus"
                    },
                modifier =
                    Modifier.padding(12.dp)
            )
        }

        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                onTextChanged(newValue.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            label = {
                Text("Text to read")
            },
            placeholder = {
                Text(
                    "Enter, paste, import, or scan text"
                )
            }
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    textFilePicker.launch(
                        arrayOf("text/plain")
                    )
                },
                enabled = !screenBusy,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("📄 Import")
            }

            OutlinedButton(
                onClick = {
                    try {
                        val cameraFolder =
                            File(
                                context.cacheDir,
                                "camera"
                            )

                        cameraFolder.mkdirs()

                        val photoFile =
                            File.createTempFile(
                                "text-photo-",
                                ".jpg",
                                cameraFolder
                            )

                        val photoUri =
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                photoFile
                            )

                        cameraImageUri = photoUri
                        cameraLauncher.launch(photoUri)
                    } catch (error: Throwable) {
                        Toast.makeText(
                            context,
                            "Could not open camera: " +
                                    error.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                enabled = !screenBusy,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("📷 Scan Text")
            }
        }

        OutlinedButton(
            onClick = {
                textFieldValue =
                    textFieldValue.copy(
                        selection =
                            TextRange(
                                start = 0,
                                end =
                                    textFieldValue
                                        .text.length
                            )
                    )
            },
            enabled =
                textFieldValue.text.isNotEmpty() &&
                        !screenBusy,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text("Select All")
        }

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    audioTextToSave =
                        textFieldValue.text

                    suggestedAudioName =
                        "AI-Reader-" +
                                selectedVoice.name +
                                ".wav"

                    audioFilePicker.launch(
                        suggestedAudioName
                    )
                },
                enabled =
                    textFieldValue.text.isNotBlank() &&
                            !screenBusy,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("💾 Save All")
            }

            OutlinedButton(
                onClick = {
                    audioTextToSave =
                        selectedText

                    suggestedAudioName =
                        "AI-Reader-Selection-" +
                                selectedVoice.name +
                                ".wav"

                    audioFilePicker.launch(
                        suggestedAudioName
                    )
                },
                enabled =
                    selectedText.isNotBlank() &&
                            !screenBusy,
                modifier =
                    Modifier.weight(1f)
            ) {
                Text("💾 Save Selected")
            }
        }

        Text(
            text =
                if (hasSelection) {
                    "${selectedText.length} characters selected"
                } else {
                    "Long-press and drag to select text"
                },
            style =
                MaterialTheme.typography.bodySmall
        )

        OutlinedButton(
            onClick = onTestAiVoice,
            enabled = !screenBusy,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text("Test Selected Voice")
        }

        Text(
            text =
                "Speed: %.1fx".format(speed)
        )

        Slider(
            value = speed,
            onValueChange = {
                speed = it
                onSpeedChanged(it)
            },
            enabled = !screenBusy,
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
                    if (
                        textFieldValue.text
                            .isNotBlank()
                    ) {
                        onPlay(
                            textFieldValue.text
                        )
                    }
                },
                enabled =
                    textFieldValue.text.isNotBlank() &&
                            !screenBusy,
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