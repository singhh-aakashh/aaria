package com.aaria.app.mode

enum class AariaMode {
    NORMAL,
    DRIVING,
    FOCUS,
    SILENT
}

class ModeManager {

    var currentMode: AariaMode = AariaMode.NORMAL
        private set

    var focusContact: String? = null
        private set

    var onModeChanged: ((AariaMode) -> Unit)? = null

    fun switchTo(mode: AariaMode) {
        if (mode != AariaMode.FOCUS) focusContact = null
        currentMode = mode
        onModeChanged?.invoke(mode)
    }

    fun setFocus(contactName: String) {
        focusContact = contactName
        switchTo(AariaMode.FOCUS)
    }

    fun exitFocus() {
        focusContact = null
        switchTo(AariaMode.NORMAL)
    }

    fun shouldReadMessage(sender: String): Boolean = when (currentMode) {
        AariaMode.DRIVING -> true
        AariaMode.FOCUS -> sender.equals(focusContact, ignoreCase = true)
        AariaMode.SILENT -> false
        AariaMode.NORMAL -> false
    }

    fun shouldActivateReplyWindow(): Boolean = when (currentMode) {
        AariaMode.DRIVING -> true
        AariaMode.FOCUS -> true
        AariaMode.SILENT -> false
        AariaMode.NORMAL -> false
    }
}
