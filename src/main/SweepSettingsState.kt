package org.jetbrains.plugins.template

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.application.ApplicationManager

@Service(Service.Level.APP)
@State(
    name = "SweepSettingsState",
    storages = [Storage("SweepSettings.xml")]
)
class SweepSettingsState : PersistentStateComponent<SweepSettingsState.State> {

    companion object {
        private const val SUBSYSTEM = "Sweep AI Assistant"
        private const val API_KEY_CREDENTIAL = "OpenAI API Key"

        fun getInstance(): SweepSettingsState = ApplicationManager.getApplication().getService(SweepSettingsState::class.java)
    }

    data class State(
        var enableAICompletions: Boolean = true,
        var enableGhostText: Boolean = true,
        var enableSingleLineMode: Boolean = true, // New single-line mode flag
        var maxTokens: Int = 30, // Reduced for single-line
        var temperature: Double = 0.2, // Lower for more consistent single-line suggestions
        var responseTimeoutMs: Int = 3000
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // Secure API key storage using IntelliJ's PasswordSafe
    var apiKey: String
        get() {
            val credentialAttributes = CredentialAttributes(
                generateServiceName(SUBSYSTEM, API_KEY_CREDENTIAL)
            )
            val credentials = PasswordSafe.instance.get(credentialAttributes)
            return credentials?.getPasswordAsString() ?: ""
        }
        set(value) {
            val credentialAttributes = CredentialAttributes(
                generateServiceName(SUBSYSTEM, API_KEY_CREDENTIAL)
            )
            val credentials = if (value.isNotEmpty()) {
                Credentials(API_KEY_CREDENTIAL, value)
            } else {
                null
            }
            PasswordSafe.instance.set(credentialAttributes, credentials)
            println("API key stored securely: ${value.isNotEmpty()}")
        }

    // Settings getters/setters
    var enableAICompletions: Boolean
        get() = myState.enableAICompletions
        set(value) { myState.enableAICompletions = value }

    var enableGhostText: Boolean
        get() = myState.enableGhostText
        set(value) { myState.enableGhostText = value }

    var enableSingleLineMode: Boolean
        get() = myState.enableSingleLineMode
        set(value) { myState.enableSingleLineMode = value }

    var maxTokens: Int
        get() = myState.maxTokens
        set(value) { myState.maxTokens = value }

    var temperature: Double
        get() = myState.temperature
        set(value) { myState.temperature = value }

    var responseTimeoutMs: Int
        get() = myState.responseTimeoutMs
        set(value) { myState.responseTimeoutMs = value }

    // Validation methods
    fun hasValidApiKey(): Boolean {
        val key = apiKey
        return key.isNotEmpty() && key.startsWith("sk-") && key.length > 20
    }

    fun clearApiKey() {
        apiKey = ""
    }

    fun isSingleLineModeEnabled(): Boolean {
        return enableSingleLineMode && enableAICompletions
    }

    fun isGhostTextEnabled(): Boolean {
        return enableGhostText && enableAICompletions
    }
}
