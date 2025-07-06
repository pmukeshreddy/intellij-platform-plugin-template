package org.jetbrains.plugins.template

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class SweepInlayHintsProvider : InlayHintsProvider<SweepInlayHintsProvider.Settings> {

    data class Settings(val enabled: Boolean = true)

    override val key: SettingsKey<Settings> = SettingsKey("sweep.inlay.hints")
    override val name: String = "Sweep AI Suggestions"
    override val previewText: String = "Shows AI-powered code completions inline"

    override fun createSettings(): Settings = Settings()

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: Settings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {
        if (!settings.enabled) return null
        return SweepInlayHintsCollector(editor)
    }

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JPanel {
                return JPanel() // Simple panel for now
            }
        }
    }

    private class SweepInlayHintsCollector(private val editor: Editor) : FactoryInlayHintsCollector(editor) {

        override fun collect(element: com.intellij.psi.PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val project = editor.project ?: return true
            val ghostTextService = project.service<SweepGhostTextService>()

            // Check if we have a ghost text suggestion for current cursor position
            val offset = editor.caretModel.offset
            val suggestion = ghostTextService.getSuggestionAt(offset)

            println("InlayHints: Checking offset $offset, suggestion: '$suggestion'")

            if (suggestion != null && suggestion.isNotEmpty()) {
                // Create simple text presentation
                val presentation = factory.text("💡 $suggestion")
                sink.addInlineElement(offset, false, presentation, false)
                println("InlayHints: Added suggestion at offset $offset: '$suggestion'")
            }

            return true
        }
    }
}
