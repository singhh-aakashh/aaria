package com.aaria.app.wakeword

enum class WakeWordState {
    INACTIVE,
    LISTENING_FOR_REPLY,
    LISTENING_FOR_STOP,
    LISTENING_FOR_CANCEL
}

class WakeWordEngine {

    var state: WakeWordState = WakeWordState.INACTIVE
        private set

    var onWakeWordDetected: (() -> Unit)? = null
    var onStopWordDetected: (() -> Unit)? = null
    var onCancelDetected: (() -> Unit)? = null

    fun startListeningForReply() {
        state = WakeWordState.LISTENING_FOR_REPLY
        // TODO Phase 3: init Porcupine with "Hey Aaria" keyword
    }

    fun startListeningForStop() {
        state = WakeWordState.LISTENING_FOR_STOP
        // TODO Phase 3: switch Porcupine to "Done" keyword
    }

    fun startListeningForCancel() {
        state = WakeWordState.LISTENING_FOR_CANCEL
        // TODO Phase 4: switch Porcupine to "cancel" keyword
    }

    fun stop() {
        state = WakeWordState.INACTIVE
        // TODO Phase 3: release Porcupine resources
    }
}
