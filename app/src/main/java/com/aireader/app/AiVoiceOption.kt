package com.aireader.app

enum class AiVoiceOption(
    val displayName: String,
    val modelPath: String
) {
    LESSAC(
        displayName = "Lessac • American English",
        modelPath = "tts/en_US-lessac-medium.onnx"
    ),

    CORI(
        displayName = "Cori • British English",
        modelPath =
            "tts/voices/en_GB-cori-medium.onnx"
    )
}