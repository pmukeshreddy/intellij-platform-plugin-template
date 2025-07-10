package org.jetbrains.plugins.template

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler

class SweepTabHandler : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val project = editor.project

        println("TAB PRESSED! Editor: ${editor.hashCode()}")

        if (project != null) {
            val ghostTextService = project.service<SweepGhostTextService>()
            val currentOffset = caret?.offset ?: editor.caretModel.offset

            println("TAB: Current offset: $currentOffset")
            println("TAB: Available suggestions: ${ghostTextService.getAllSuggestions()}")

            val searchStart = currentOffset - 15


            for (offset in currentOffset downTo searchStart) {
                val suggestion = ghostTextService.getSuggestionAt(offset)
                if (suggestion != null && suggestion.isNotEmpty()) {
                    println("TAB: Found suggestion at offset $offset: '$suggestion'")
                    val accepted = ghostTextService.acceptSuggestion(editor, offset)

                    println("TAB: Suggestion accepted: $accepted")
                    if (accepted) {
                        println("Tab accepted ghost text: '$suggestion'")
                        return // Don't execute normal tab behavior
                    }
                }
            }
        }

        println("TAB: No suggestion found, executing normal tab")

        // Use write action for tab insertion
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            try {
                val document = editor.document
                val caretOffset = caret?.offset ?: editor.caretModel.offset
                document.insertString(caretOffset, "\t")
                editor.caretModel.moveToOffset(caretOffset + 1)
            } catch (e: Exception) {
                println("Error inserting tab: ${e.message}")
            }
        }
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
        return true
    }
}
