package org.jetbrains.plugins.template

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.util.ProcessingContext
import com.intellij.openapi.components.service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

class SweepCompletionContributor : CompletionContributor() {
    // The simple cache is replaced with the advanced cache manager.
    private val cacheManager = SweepCacheManager()

    companion object {
        // Optimized HTTP client for quick responses
        private val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1000))
            .build()

        // Rate limiting - much more permissive for debugging
        private var lastRequestTime = 0L
        private const val MIN_REQUEST_INTERVAL = 50L // ms - very responsive
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val document = parameters.editor.document
        val offset = parameters.offset
        val editor = parameters.editor
        val project = editor.project ?: return

        val contextAnalyzer = SweepContextAnalyzer()
        val context = contextAnalyzer.analyzeContext(editor, offset, parameters.originalFile)

        if (context.currentLine.trim().length < 2) return

        println("AI-FIRST Context-Aware Completion triggered:")
        println("  Current line: '${context.currentLine}'")
        println("  Function: '${context.currentFunction}' (${context.functionParameters.size} params)")
        println("  Class: '${context.currentClass}' (${context.classFields.size} fields)")
        println("  Language: '${context.fileLanguage}'")
        println("  Available variables: ${context.variables.size}")
        println("  File structure: ${context.fileStructure.functions.size} functions, ${context.fileStructure.classes.size} classes")

        val lineNumber = document.getLineNumber(offset)
        val cacheKey = buildEnhancedCacheKey(context, lineNumber)

        // ✅ FIRST: Check cache for existing AI results
       /* val cachedSuggestion = cacheManager.getCachedCompletion(cacheKey, context)
        if (cachedSuggestion != null) {
            println("Using cached AI suggestion: '$cachedSuggestion'")
            addSuggestion(result, cachedSuggestion, context.currentLine)
            val ghostTextService = project.service<SweepGhostTextService>()
            ghostTextService.setSuggestion(editor, cachedSuggestion, offset, context.currentLine.trim())
            return
        }
        */
        // ✅ SECOND: Always make AI call for fresh suggestions
        val triggerText = context.currentLine.trim()
        println("Making AI-FIRST request with full file analysis for: '${context.currentLine}' (line $lineNumber)")
        println("Context summary: ${context.fileStructure.functions.size} functions, ${context.variables.size} variables")

        CompletableFuture.supplyAsync {
            println("AI-FIRST request started for: '${triggerText}'")
            lastRequestTime = System.currentTimeMillis()
            callEnhancedContextAwareOpenAI(context)
        }.thenAccept { aiSuggestion ->
            var finalSuggestion = aiSuggestion

            // ✅ FALLBACK: Only use quick completion if AI completely fails
            if (finalSuggestion.isEmpty()) {
                println("AI failed, trying quick completion fallback for: '${context.currentLine}'")
                finalSuggestion = getEnhancedContextAwareQuickCompletion(context)

                // If still empty, try basic fallback
                if (finalSuggestion.isEmpty()) {
                    finalSuggestion = getBasicFallbackCompletion(context.currentLine.trim())
                }
            }

            if (finalSuggestion.isNotEmpty() && !finalSuggestion.startsWith("#")) {
                // Store the new suggestion in the advanced cache.
                cacheManager.cacheCompletion(cacheKey, finalSuggestion, context)

                // Show as ghost text (this is the main UX)
                val ghostTextService = project.service<SweepGhostTextService>()
                ghostTextService.setSuggestion(editor, finalSuggestion, offset, triggerText)

                println("AI-FIRST completion set: '$finalSuggestion' with trigger: '$triggerText'")
            } else {
                println("No AI-FIRST completion available for: '${context.currentLine}'")
            }
        }.exceptionally { throwable ->
            println("AI-FIRST request failed: ${throwable.message}")

            // ✅ EMERGENCY FALLBACK: Quick completion only on network errors
            val fallback = getEnhancedContextAwareQuickCompletion(context)
            if (fallback.isNotEmpty()) {
                val ghostTextService = project.service<SweepGhostTextService>()
                ghostTextService.setSuggestion(editor, fallback, offset, triggerText)
                println("Network error fallback: '$fallback'")
            }
            null
        }
    }

    // Placeholder for secure API key retrieval.
    private fun getApiKey(): String? {
        // TODO: Implement secure API key retrieval from a settings panel and the IDE's PasswordSafe.
        return "YOUR_API_KEY_HERE"
    }

    private fun buildEnhancedCacheKey(context: CodeContext, lineNumber: Int): String {
        return buildString {
            append(context.currentLine.trim())
            append("_lang_${context.fileLanguage}")
            append("_line_$lineNumber")

            // Include function context if not defining
            if (!isDefiningFunction(context.currentLine) && context.currentFunction.isNotEmpty()) {
                append("_func_${context.currentFunction}")
                append("_params_${context.functionParameters.size}")
            }

            // Include class context if not defining
            if (!isDefiningClass(context.currentLine) && context.currentClass.isNotEmpty()) {
                append("_class_${context.currentClass}")
                append("_fields_${context.classFields.size}")
            }

            // Include variable context
            append("_vars_${context.variables.take(5).sorted().joinToString(",")}")

            // Include imports for import-related completions
            if (context.currentLine.contains("import")) {
                append("_imports_${context.imports.size}")
            }

            // Include file structure context
            append("_structure_${context.fileStructure.functions.size}_${context.fileStructure.classes.size}")
        }
    }

    private fun getBasicFallbackCompletion(currentLine: String): String {
        return when {
            currentLine == "def v" -> "def validate(self):"
            currentLine == "def" -> "def process(self):"
            currentLine == "class" -> "class DataProcessor:"
            currentLine == "import t" -> "import torch"
            currentLine == "import n" -> "import numpy as np"
            currentLine == "import p" -> "import pandas as pd"
            currentLine == "for i" -> "for i in range(10):"
            currentLine == "for" -> "for item in data:"
            currentLine == "if" -> "if condition:"
            currentLine == "while" -> "while condition:"
            currentLine == "try" -> "try:"
            currentLine == "with" -> "with open('file.txt') as f:"
            else -> ""
        }
    }

    private fun getEnhancedContextAwareQuickCompletion(context: CodeContext): String {
        val currentLine = context.currentLine.trim()

        return when (context.fileLanguage) {
            "python" -> getEnhancedPythonContextCompletion(currentLine, context)
            "java", "kotlin" -> getEnhancedJavaKotlinContextCompletion(currentLine, context)
            "javascript", "typescript" -> getEnhancedJsContextCompletion(currentLine, context)
            else -> getEnhancedPythonContextCompletion(currentLine, context)
        }
    }

    private fun getEnhancedPythonContextCompletion(currentLine: String, context: CodeContext): String {
        return when {
            currentLine == "def" && context.currentClass.isNotEmpty() && !isInsideFunction(context) -> {
                val existingMethods = context.fileStructure.classes
                    .find { it.name == context.currentClass }?.methods ?: emptyList()
                val suggestedName = generateSmartMethodName(context.currentClass, existingMethods)
                "def $suggestedName(self):"
            }

            currentLine == "class" -> {
                val existingClasses = context.fileStructure.classes.map { it.name }
                val suggestedName = generateSmartClassName(context, existingClasses)
                "class $suggestedName:"
            }

            currentLine == "for" && hasActualIterableVariables(context) -> {
                val iterables = getActualIterableVariables(context)
                "for item in ${iterables.first()}:"
            }

            currentLine == "for i" && hasActualIterableVariables(context) -> {
                val iterables = getActualIterableVariables(context)
                "for i in range(len(${iterables.first()})):"
            }

            currentLine == "for i" -> "for i in range("
            currentLine == "for" -> "for item in "

            currentLine.startsWith("import ") && !isDuplicateImport(currentLine, context) -> {
                val suggestion = suggestSmartImport(currentLine, context)
                if (suggestion.isNotEmpty()) suggestion else ""
            }

            currentLine.startsWith("from ") -> {
                val suggestion = suggestSmartFromImport(currentLine, context)
                if (suggestion.isNotEmpty()) suggestion else ""
            }

            currentLine == "print" && hasActualVariables(context) -> {
                val smartVar = selectActualVariable(context.variables + context.functionParameters, context)
                "print($smartVar)"
            }

            currentLine.contains("len") && hasActualIterableVariables(context) -> {
                val iterables = getActualIterableVariables(context)
                "len(${iterables.first()})"
            }

            currentLine.startsWith("self.") && context.classFields.isNotEmpty() -> {
                "self.${context.classFields.first()}"
            }

            // Basic patterns as fallback - CONSERVATIVE
            else -> getBasicPythonCompletion(currentLine)
        }
    }

    private fun hasActualIterableVariables(context: CodeContext): Boolean {
        val availableVars = context.variables + context.functionParameters + context.classFields
        return availableVars.any { variable ->
            variable.endsWith("_list") ||
                    variable.endsWith("_array") ||
                    variable == "data" ||
                    variable == "items" ||
                    // Check in recent context lines for actual usage
                    context.previousLines.any { line ->
                        line.contains("$variable = [") ||
                                line.contains("$variable = list(")
                    }
        }
    }

    private fun getActualIterableVariables(context: CodeContext): List<String> {
        val availableVars = context.variables + context.functionParameters + context.classFields
        return availableVars.filter { variable ->
            variable.endsWith("_list") ||
                    variable.endsWith("_array") ||
                    variable == "data" ||
                    variable == "items" ||
                    context.previousLines.any { line ->
                        line.contains("$variable = [") ||
                                line.contains("$variable = list(")
                    }
        }.ifEmpty {
            // Fallback to function parameters if they exist
            context.functionParameters.ifEmpty { listOf("data") }
        }
    }

    private fun hasActualVariables(context: CodeContext): Boolean {
        return (context.variables + context.functionParameters + context.classFields).isNotEmpty()
    }

    private fun selectActualVariable(variables: List<String>, context: CodeContext): String {
        // Prioritize function parameters
        if (context.functionParameters.isNotEmpty()) {
            return context.functionParameters.first()
        }

        // Then class fields
        if (context.classFields.isNotEmpty()) {
            return "self.${context.classFields.first()}"
        }

        // Then local variables
        if (variables.isNotEmpty()) {
            return variables.first()
        }

        return "data" // Safe fallback
    }

    private fun generateSmartMethodName(className: String, existingMethods: List<String>): String {
        val commonPatterns = mapOf(
            "neural" to listOf("forward", "backward", "train", "predict"),
            "data" to listOf("load", "save", "process", "validate"),
            "model" to listOf("fit", "predict", "evaluate", "transform"),
            "service" to listOf("start", "stop", "process", "handle"),
            "manager" to listOf("create", "update", "delete", "get"),
            "handler" to listOf("handle", "process", "execute", "run")
        )

        val classLower = className.lowercase()
        for ((pattern, methods) in commonPatterns) {
            if (classLower.contains(pattern)) {
                for (method in methods) {
                    if (!existingMethods.contains(method)) {
                        return method
                    }
                }
            }
        }

        val baseName = className.lowercase().replace("class", "").replace("manager", "").replace("handler", "")
        return "${baseName}_method"
    }

    private fun generateSmartClassName(context: CodeContext, existingClasses: List<String>): String {
        val imports = context.imports.joinToString(" ").lowercase()

        return when {
            imports.contains("torch") && !existingClasses.any { it.contains("Neural") } -> "NeuralNetwork"
            imports.contains("pandas") && !existingClasses.any { it.contains("Data") } -> "DataProcessor"
            imports.contains("sklearn") && !existingClasses.any { it.contains("Model") } -> "MLModel"
            imports.contains("fastapi") && !existingClasses.any { it.contains("Service") } -> "APIService"
            context.allFunctions.any { it.contains("test") } && !existingClasses.any { it.contains("Test") } -> "TestCase"
            else -> "NewClass"
        }
    }

    private fun suggestSmartImport(currentLine: String, context: CodeContext): String {
        val partialImport = currentLine.substringAfter("import ").trim()
        val existingLibs = context.imports.joinToString(" ").lowercase()

        return when {
            partialImport.startsWith("tor") && !existingLibs.contains("torch") -> "import torch"
            partialImport.startsWith("np") && !existingLibs.contains("numpy") -> "import numpy as np"
            partialImport.startsWith("pd") && !existingLibs.contains("pandas") -> "import pandas as pd"
            partialImport.startsWith("plt") && !existingLibs.contains("matplotlib") -> "import matplotlib.pyplot as plt"
            partialImport.startsWith("sk") && !existingLibs.contains("sklearn") -> "import sklearn"
            partialImport.startsWith("tf") && !existingLibs.contains("tensorflow") -> "import tensorflow as tf"
            partialImport.startsWith("req") && !existingLibs.contains("requests") -> "import requests"

            // Smart suggestions based on existing imports
            existingLibs.contains("torch") && partialImport.startsWith("tor") -> "import torch.nn.functional as F"
            existingLibs.contains("numpy") && partialImport.startsWith("mat") -> "import matplotlib.pyplot as plt"
            existingLibs.contains("pandas") && partialImport.startsWith("sk") -> "import sklearn.model_selection"

            else -> ""
        }
    }

    private fun suggestSmartFromImport(currentLine: String, context: CodeContext): String {
        val partialFrom = currentLine.substringAfter("from ").trim()
        val existingLibs = context.imports.joinToString(" ").lowercase()

        return when {
            partialFrom.startsWith("torch") && existingLibs.contains("torch") -> "from torch import nn, optim"
            partialFrom.startsWith("sklearn") && existingLibs.contains("sklearn") -> "from sklearn.model_selection import train_test_split"
            partialFrom.startsWith("typing") -> "from typing import List, Dict, Optional"
            else -> ""
        }
    }

    private fun getEnhancedJavaKotlinContextCompletion(currentLine: String, context: CodeContext): String {
        // Similar enhancements for Java/Kotlin...
        return getBasicJavaKotlinCompletion(currentLine)
    }

    private fun getEnhancedJsContextCompletion(currentLine: String, context: CodeContext): String {
        // Similar enhancements for JavaScript/TypeScript...
        return getBasicJsCompletion(currentLine)
    }

    private fun callEnhancedContextAwareOpenAI(context: CodeContext): String {
        
        val apiKey = getApiKey()

        if (apiKey.isNullOrEmpty() || apiKey == "YOUR_API_KEY_HERE") {
            return "# Add your OpenAI API key in the settings!"
        }

        return try {
            // ENHANCED: Use smart prompt builder
            val promptBuilder = SweepPromptBuilder()
            val prompt = promptBuilder.buildMultiLinePrompt(context)

            println("AI-FIRST API Request - Smart Prompt Length: ${prompt.length}")

            // Proper JSON escaping
            val escapedPrompt = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

            val jsonBody = """
            {
    "model": "gpt-4o-mini",
    "messages": [
        {"role": "system", "content": "You are a code completion assistant like GitHub Copilot. When given incomplete code, suggest what should come next. Focus on useful, practical completions. Return only the completed/extended code without explanations."},
        {"role": "user", "content": "$escapedPrompt"}
    ],
    "max_tokens": 100,
    "temperature": 0.3,
    "stop": ["\n\n\n"]
}
            """.trimIndent()

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofMillis(3000)) // Increased timeout for complex requests
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            println("AI-FIRST API Response - Status: ${response.statusCode()}")

            if (response.statusCode() == 200) {
                val parsed = parseContent(response.body())
                println("AI-FIRST API Response - Parsed: '$parsed'")
                parsed
            } else {
                println("AI-FIRST API Error - Body: ${response.body()}")
                "# API Error ${response.statusCode()}"
            }

        } catch (e: Exception) {
            println("AI-FIRST Network Error: ${e.message}")
            "# Network Error: ${e.message}"
        }
    }

    // Keep existing helper methods...
    private fun isDefiningFunction(currentLine: String): Boolean {
        return currentLine.trim().startsWith("def ")
    }

    private fun isDefiningClass(currentLine: String): Boolean {
        return currentLine.trim().startsWith("class ")
    }

    private fun isInsideFunction(context: CodeContext): Boolean {
        return context.currentFunction.isNotEmpty() &&
                context.previousLines.any { it.trim().startsWith("def ${context.currentFunction}") }
    }

    private fun isDuplicateImport(currentLine: String, context: CodeContext): Boolean {
        val importName = currentLine.substringAfter("import ").trim()
        return context.imports.any { it.contains(importName) }
    }

    private fun addSuggestion(result: CompletionResultSet, completionText: String, currentLine: String) {
        val trimmedCompletion = completionText.trim()
        val trimmedCurrentLine = currentLine.trim()

        if (trimmedCompletion == trimmedCurrentLine || trimmedCompletion.isEmpty()) {
            return
        }

        val uniqueLookup = "ai_${System.currentTimeMillis()}_${trimmedCompletion.hashCode()}"

        val suggestion = LookupElementBuilder.create(uniqueLookup)
            .withTypeText("🤖 AI-First")
            .withPresentableText("🤖 $trimmedCompletion")
            .withInsertHandler { context, _ ->
                val document = context.document
                val offset = context.startOffset
                val lineNumber = document.getLineNumber(offset)
                val lineStart = document.getLineStartOffset(lineNumber)
                val lineEnd = document.getLineEndOffset(lineNumber)

                val finalText = when {
                    trimmedCompletion.startsWith(trimmedCurrentLine) -> trimmedCompletion
                    else -> {
                        if (trimmedCurrentLine.isNotEmpty() && !trimmedCompletion.startsWith(trimmedCurrentLine)) {
                            trimmedCurrentLine + trimmedCompletion
                        } else {
                            trimmedCompletion
                        }
                    }
                }

                document.replaceString(lineStart, lineEnd, finalText)
            }
            .bold()
            .withLookupString(trimmedCompletion)

        result.addElement(suggestion)
    }

    private fun parseContent(json: String): String {
        // Replaced brittle regex with a more robust manual JSON traversal.
        // A proper JSON library (e.g., kotlinx.serialization) is still the best solution.
        try {
            val choicesIndex = json.indexOf("\"choices\"")
            if (choicesIndex == -1) return ""

            val messageIndex = json.indexOf("\"message\"", choicesIndex)
            if (messageIndex == -1) return ""

            val contentIndex = json.indexOf("\"content\"", messageIndex)
            if (contentIndex == -1) return ""

            val contentStartIndex = json.indexOf('"', contentIndex + 9) + 1
            val contentEndIndex = json.indexOf('"', contentStartIndex)

            if (contentStartIndex == 0 || contentEndIndex == -1) return ""

            val content = json.substring(contentStartIndex, contentEndIndex)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\\", "\\")
                .trim()

            println("Raw API content: '$content'")

            if (content.contains("I cannot") || content.contains("I'm sorry") || content.contains("I don't")) {
                println("Content filtered out - refusal: '$content'")
                return ""
            } else if (content.length < 1) {
                println("Content filtered out - too short: '$content'")
                return ""
            } else if (content == "```python" || content == "```") {
                println("Content filtered out - just markdown: '$content'")
                return ""
            } else {
                val cleaned = cleanAndValidateCompletion(content)
                println("Content processed: '$content' -> '$cleaned'")
                return cleaned
            }
        } catch (e: Exception) {
            println("Error parsing JSON response: ${e.message}")
            return ""
        }
    }

    private fun cleanAndValidateCompletion(content: String): String {
        var cleaned = content
            .replace("```python", "")
            .replace("```kotlin", "")
            .replace("```java", "")
            .replace("```javascript", "")
            .replace("```", "")
            .replace("`", "")
            .trim()

        if (cleaned.isEmpty()) return ""

        val lines = cleaned.split('\n').filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() &&
                    !trimmed.startsWith("Here") &&
                    !trimmed.startsWith("This") &&
                    !trimmed.startsWith("The") &&
                    !trimmed.startsWith("//") &&
                    !trimmed.contains("I cannot") &&
                    !trimmed.contains("I'm sorry")
        }

        if (lines.isEmpty()) return ""

        val firstLine = lines[0].trim()

        return when {
            firstLine.contains("I cannot") || firstLine.contains("I'm sorry") -> ""
            firstLine.length < 2 -> ""
            firstLine.length > 150 -> firstLine.take(150)
            firstLine.matches(Regex(".*[a-zA-Z0-9_=\\(\\)\\[\\]\\.:{}\\-<>\"'\\s]+.*")) -> firstLine
            else -> {
                println("Content filtered out - validation failed: '$firstLine'")
                ""
            }
        }
    }

    private fun getBasicPythonCompletion(currentLine: String): String {
        return when {
            currentLine == "import" -> "import "
            currentLine == "from" -> "from "
            currentLine == "def" -> "def "
            currentLine == "class" -> "class "
            currentLine == "if" -> "if "
            currentLine == "while" -> "while "
            currentLine == "try" -> "try:"
            currentLine == "except" -> "except:"
            currentLine == "finally" -> "finally:"
            currentLine == "with" -> "with "
            currentLine == "for i" -> "for i in range("
            currentLine == "for i in" -> "for i in range("
            currentLine == "for i in range" -> "for i in range("
            currentLine == "for" -> "for i in range("
            currentLine.matches(Regex("for \\w+")) -> "${currentLine} in range("
            currentLine.matches(Regex("for \\w+ in")) -> "${currentLine} range("
            currentLine == "if __name__" -> "if __name__ == '__main__':"
            currentLine == "print" -> "print("
            currentLine == "len" -> "len("
            currentLine == "range" -> "range("
            currentLine == "enumerate" -> "enumerate("
            else -> ""
        }
    }

    private fun getBasicJavaKotlinCompletion(currentLine: String): String {
        return when {
            currentLine == "fun" -> "fun "
            currentLine == "class" -> "class "
            currentLine == "val" -> "val "
            currentLine == "var" -> "var "
            currentLine == "if" -> "if ("
            currentLine == "for" -> "for ("
            currentLine == "while" -> "while ("
            else -> ""
        }
    }

    private fun getBasicJsCompletion(currentLine: String): String {
        return when {
            currentLine == "function" -> "function "
            currentLine == "const" -> "const "
            currentLine == "let" -> "let "
            currentLine == "var" -> "var "
            currentLine == "if" -> "if ("
            currentLine == "for" -> "for ("
            currentLine == "while" -> "while ("
            else -> ""
        }
    }
}
