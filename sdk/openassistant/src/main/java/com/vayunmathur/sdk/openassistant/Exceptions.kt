package com.vayunmathur.sdk.openassistant

/** Thrown when the OpenAssistant app is not installed on this device. */
class AssistantNotInstalledException :
    Exception("OpenAssistant app is not installed on this device")

/** Failure reported by the OpenAssistant inference service. */
class AssistantException(message: String) : Exception(message)
