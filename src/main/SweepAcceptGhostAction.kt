package org.jetbrains.plugins.template

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class SweepAcceptGhostAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return

        println("GHOST ACCEPT ACTION TRIGGERED!")

        val ghostTextService = project.service<SweepGhostTextService>()
        val currentOffset = editor.caretModel.offset

        println("Checking for ghost text at offset: $currentOffset")
        println("Available suggestions: ${ghostTextService.getAllSuggestions()}")

        // Check current position and nearby positions
        val offsets = listOf(currentOffset, currentOffset - 1, currentOffset - 2, currentOffset - 3)

        for (offset in offsets) {
            val suggestion = ghostTextService.getSuggestionAt(offset)
            if (suggestion != null && suggestion.isNotEmpty()) {
                println("Found ghost text at offset $offset: '$suggestion' - accepting!")

                com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                    val accepted = ghostTextService.acceptSuggestion(editor, offset)
                    if (accepted) {
                        println("Successfully accepted ghost text!")
                    } else {
                        println("Failed to accept ghost text")
                    }
                }
                return
            }
        }

        println("No ghost text found at any nearby position")
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null
    }
}
