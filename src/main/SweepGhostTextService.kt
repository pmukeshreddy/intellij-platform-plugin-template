package org.jetbrains.plugins.template

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SweepGhostTextService(private val project: Project) {

    private data class GhostSuggestion(
        val text: String,
        val offset: Int,
        val lineNumber: Int,
        val inlay: com.intellij.openapi.editor.Inlay<*>?,
        val triggerText: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val activeSuggestions = ConcurrentHashMap<Int, GhostSuggestion>()
    private val editorListeners = ConcurrentHashMap<Editor, Pair<CaretListener, DocumentListener>>()

    fun setSuggestion(editor: Editor, suggestion: String, offset: Int, triggerText: String = "") {
        if (suggestion.isEmpty() || suggestion.trim().length < 3) return

        println("setSuggestion called: suggestion='$suggestion', offset=$offset, triggerText='$triggerText'")

        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            try {
                val lineNumber = editor.document.getLineNumber(offset)
                val suggestionsOnLine = activeSuggestions.values.filter { it.lineNumber == lineNumber }
                suggestionsOnLine.forEach { clearSuggestion(it.offset) }

                clearSuggestion(offset)

                val inlay = editor.inlayModel.addInlineElement(
                    offset,
                    false,
                    object : com.intellij.openapi.editor.EditorCustomElementRenderer {
                        override fun calcWidthInPixels(inlay: com.intellij.openapi.editor.Inlay<*>): Int {
                            val fm = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN))
                            return fm.stringWidth("💡 $suggestion")
                        }

                        override fun paint(
                            inlay: com.intellij.openapi.editor.Inlay<*>,
                            g: java.awt.Graphics,
                            targetRegion: java.awt.Rectangle,
                            textAttributes: TextAttributes
                        ) {
                            g.color = JBColor.GRAY
                            g.font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN).deriveFont(Font.ITALIC)
                            g.drawString("💡 $suggestion", targetRegion.x, targetRegion.y + targetRegion.height - 3)
                        }
                    }
                )

                if (inlay != null) {
                    val ghostSuggestion = GhostSuggestion(suggestion, offset, lineNumber, inlay, triggerText)
                    activeSuggestions[offset] = ghostSuggestion
                    attachListeners(editor)
                    println("Ghost text displayed at offset $offset: '$suggestion'")
                }
            } catch (e: Exception) {
                println("Error creating ghost text: ${e.message}")
            }
        }
    }

    fun getSuggestionAt(offset: Int): String? {
        return activeSuggestions[offset]?.text
    }

    fun acceptSuggestion(editor: Editor, offset: Int): Boolean {
        val suggestion = activeSuggestions.remove(offset) ?: return false

        try {
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                suggestion.inlay?.dispose()
                val document = editor.document

                if (suggestion.triggerText.isNotEmpty()) {
                    val lineNumber = document.getLineNumber(offset)
                    val lineStart = document.getLineStartOffset(lineNumber)
                    val lineEnd = document.getLineEndOffset(lineNumber)

                    val trigger = suggestion.triggerText.trim()
                    val suggestionText = suggestion.text.trim()

                    val finalLine = when {
                        // Use suggestion as complete replacement for most cases
                        suggestionText.startsWith("import ") ||
                                suggestionText.startsWith("class ") ||
                                suggestionText.startsWith("def ") ||
                                suggestionText.startsWith("self.") -> suggestionText

                        // Smart append only for safe cases
                        trigger.startsWith("import ") && suggestionText.startsWith(".") -> trigger + suggestionText

                        // Default: use suggestion as-is
                        else -> suggestionText
                    }

                    // Add proper indentation
                    val currentLineText = document.getText().substring(lineStart, lineEnd)
                    val indentation = currentLineText.takeWhile { it == ' ' || it == '\t' }
                    val completedLine = if (finalLine.startsWith(indentation)) finalLine else indentation + finalLine.trim()

                    document.replaceString(lineStart, lineEnd, completedLine)
                    editor.caretModel.moveToOffset(lineStart + completedLine.length)

                    println("Replaced: '$trigger' -> '$completedLine'")
                } else {
                    val currentCaret = editor.caretModel.offset
                    document.insertString(currentCaret, suggestion.text)
                    editor.caretModel.moveToOffset(currentCaret + suggestion.text.length)
                }

                clearSuggestionsForEditor(editor)
            }
            return true
        } catch (e: Exception) {
            println("Error accepting suggestion: ${e.message}")
            return false
        }
    }

    fun clearSuggestion(offset: Int) {
        val suggestion = activeSuggestions.remove(offset)
        suggestion?.inlay?.let { inlay ->
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                inlay.dispose()
            }
        }
    }

    fun clearSuggestionsForEditor(editor: Editor) {
        val suggestions = activeSuggestions.values.toList()
        activeSuggestions.clear()
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            suggestions.forEach { it.inlay?.dispose() }
        }
    }

    private fun attachListeners(editor: Editor) {
        if (editorListeners.containsKey(editor)) return

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val offset = event.caret?.offset ?: editor.caretModel.offset
                val toRemove = activeSuggestions.filter { (suggestionOffset, _) ->
                    Math.abs(suggestionOffset - offset) > 25
                }
                toRemove.forEach { (suggestionOffset, suggestion) ->
                    activeSuggestions.remove(suggestionOffset)
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        suggestion.inlay?.dispose()
                    }
                }
            }
        }

        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val changeOffset = event.offset

                // Keep suggestions if typing towards them, clear if typing away
                activeSuggestions.entries.removeAll { (suggestionOffset, suggestion) ->
                    val distance = Math.abs(changeOffset - suggestionOffset)
                    val shouldRemove = distance > 20

                    if (shouldRemove) {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                            suggestion.inlay?.dispose()
                        }
                    }
                    shouldRemove
                }
            }
        }

        editor.caretModel.addCaretListener(caretListener)
        editor.document.addDocumentListener(documentListener)
        editorListeners[editor] = Pair(caretListener, documentListener)
    }

    fun getAllSuggestions(): Map<Int, String> {
        return activeSuggestions.mapValues { "${it.value.text} (trigger: '${it.value.triggerText}')" }
    }

    fun dispose() {
        val suggestions = activeSuggestions.values.toList()
        activeSuggestions.clear()
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            suggestions.forEach { it.inlay?.dispose() }
        }
        editorListeners.forEach { (editor, listeners) ->
            try {
                editor.caretModel.removeCaretListener(listeners.first)
                editor.document.removeDocumentListener(listeners.second)
            } catch (e: Exception) {
                // Editor disposed
            }
        }
        editorListeners.clear()
    }
}
