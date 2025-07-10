package org.jetbrains.plugins.template

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class SweepSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private var apiKeyField: String = ""

    override fun getDisplayName(): String = "Sweep AI Assistant"

    override fun getPreferredFocusedComponent(): JComponent? = panel

    override fun createComponent(): JComponent {
        panel = panel {
            group("OpenAI Configuration") {
                row("API Key:") {
                    passwordField()
                        .bindText(::apiKeyField)
                        .comment("Enter your OpenAI API key for AI-powered completions")
                        .columns(50)
                }
                row {
                    comment("Get your API key from: https://platform.openai.com/account/api-keys")
                }
            }

            group("Single-Line Completion Features") {
                row {
                    checkBox("Enable single-line AI completions")
                        .comment("Show AI-powered single-line code suggestions while typing")
                        .selected(true)
                }
                row {
                    checkBox("Enable ghost text")
                        .comment("Display suggestions as gray text in the editor")
                        .selected(true)
                }
                row {
                    checkBox("Quick Tab acceptance")
                        .comment("Use Tab key to accept suggestions instantly")
                        .selected(true)
                }
            }

            group("Performance") {
                row("Max suggestion length:") {
                    intTextField(range = 10..100)
                        .comment("Maximum length of single-line AI suggestions (characters)")
                        .text("80")
                }
                row("Response timeout:") {
                    intTextField(range = 1000..10000)
                        .comment("AI request timeout in milliseconds")
                        .text("3000")
                }
            }
        }

        // Load current settings
        loadSettings()

        return panel!!
    }

    override fun isModified(): Boolean {
        val currentApiKey = SweepSettingsState.getInstance().apiKey
        return apiKeyField != currentApiKey
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        // Validate API key format
        if (apiKeyField.isNotEmpty() && !apiKeyField.startsWith("sk-")) {
            throw ConfigurationException("OpenAI API key should start with 'sk-'")
        }

        // Save to secure storage
        SweepSettingsState.getInstance().apiKey = apiKeyField

        println("Sweep settings saved - API key configured: ${apiKeyField.isNotEmpty()}")
        println("Single-line completion mode active")
    }

    override fun reset() {
        loadSettings()
    }

    private fun loadSettings() {
        apiKeyField = SweepSettingsState.getInstance().apiKey
        panel?.reset()
    }
}
