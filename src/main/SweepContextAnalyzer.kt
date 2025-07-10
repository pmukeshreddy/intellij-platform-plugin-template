package org.jetbrains.plugins.template

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

data class CodeContext(
    val currentLine: String,
    val currentFunction: String,
    val currentClass: String,
    val imports: List<String>,
    val previousLines: List<String>,
    val fileLanguage: String,
    val indentation: String,
    val variables: List<String>,
    // NEW: Enhanced context fields
    val functionSignature: String,
    val functionParameters: List<String>,
    val returnType: String,
    val classFields: List<String>,
    val allFunctions: List<String>,
    val contextLines: List<String>, // More lines around cursor
    val fileStructure: FileStructure
)

data class FileStructure(
    val totalLines: Int,
    val imports: List<String>,
    val classes: List<ClassInfo>,
    val functions: List<FunctionInfo>,
    val mainBlocks: List<String>
)

data class ClassInfo(
    val name: String,
    val lineNumber: Int,
    val methods: List<String>,
    val fields: List<String>
)

data class FunctionInfo(
    val name: String,
    val lineNumber: Int,
    val parameters: List<String>,
    val returnType: String,
    val isMethod: Boolean
)

class SweepContextAnalyzer {

    fun analyzeContext(editor: Editor, offset: Int, psiFile: PsiFile?): CodeContext {
        val document = editor.document
        val text = document.text

        // Get current line
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val currentLine = document.getText().substring(lineStart, offset)

        // Get file language
        val fileLanguage = detectLanguage(psiFile)

        // Get indentation of current line
        val indentation = getIndentation(currentLine)

        // ENHANCED: Get more context lines (50 lines before/after)
        val contextLines = getContextLines(document, lineNumber, 50)
        val previousLines = getPreviousLines(document, lineNumber, 25) // Increased from 15

        // Extract imports
        val imports = extractImports(text, fileLanguage)

        // ENHANCED: Deep function analysis
        val currentFunction = findCurrentFunction(text, offset, fileLanguage)
        val functionSignature = getCurrentFunctionSignature(text, offset, fileLanguage)
        val functionParameters = extractFunctionParameters(functionSignature, fileLanguage)
        val returnType = extractReturnType(functionSignature, fileLanguage)

        // ENHANCED: Deep class analysis
        val currentClass = findCurrentClass(text, offset, fileLanguage)
        val classFields = getClassFields(text, currentClass, fileLanguage)

        // Extract variables from larger context
        val variables = extractVariables(contextLines, fileLanguage) // Use contextLines instead

        val fileStructure = analyzeFileStructure(text, fileLanguage)
        val allFunctions = fileStructure.functions.map { it.name }

        println("ENHANCED CONTEXT ANALYSIS:")
        println("  Language: $fileLanguage")
        println("  Current line: '$currentLine'")
        println("  Current function: '$currentFunction'")
        println("  Function signature: '$functionSignature'")
        println("  Function parameters: ${functionParameters.joinToString(", ")}")
        println("  Return type: '$returnType'")
        println("  Current class: '$currentClass'")
        println("  Class fields: ${classFields.joinToString(", ")}")
        println("  All functions: ${allFunctions.joinToString(", ")}")
        println("  Context lines: ${contextLines.size} lines")
        println("  File structure: ${fileStructure.classes.size} classes, ${fileStructure.functions.size} functions")

        return CodeContext(
            currentLine = currentLine.trim(),
            currentFunction = currentFunction,
            currentClass = currentClass,
            imports = imports,
            previousLines = previousLines,
            fileLanguage = fileLanguage,
            indentation = indentation,
            variables = variables,
            functionSignature = functionSignature,
            functionParameters = functionParameters,
            returnType = returnType,
            classFields = classFields,
            allFunctions = allFunctions,
            contextLines = contextLines,
            fileStructure = fileStructure
        )
    }

    private fun getContextLines(document: com.intellij.openapi.editor.Document, currentLineNumber: Int, count: Int): List<String> {
        val lines = mutableListOf<String>()
        val halfCount = count / 2
        val startLine = maxOf(0, currentLineNumber - halfCount)
        val endLine = minOf(document.lineCount - 1, currentLineNumber + halfCount)

        for (lineNum in startLine..endLine) {
            try {
                val lineStart = document.getLineStartOffset(lineNum)
                val lineEnd = document.getLineEndOffset(lineNum)
                val lineText = document.getText().substring(lineStart, lineEnd)
                lines.add(lineText)
            } catch (e: Exception) {
                lines.add("")
            }
        }

        return lines
    }

    private fun getCurrentFunctionSignature(text: String, offset: Int, language: String): String {
        val beforeCursor = text.substring(0, offset)
        val lines = beforeCursor.split('\n').reversed()

        when (language) {
            "python" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("def ") && trimmed.contains("(") && trimmed.endsWith(":")) {
                        return trimmed
                    }
                }
            }
            "java", "kotlin" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if ((trimmed.contains("fun ") || trimmed.contains(" void ") ||
                                trimmed.contains(" int ") || trimmed.contains(" String ")) &&
                        trimmed.contains("(") && trimmed.contains(")")) {
                        return trimmed
                    }
                }
            }
            "javascript", "typescript" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if ((trimmed.startsWith("function ") || trimmed.contains(" => ")) &&
                        trimmed.contains("(") && trimmed.contains(")")) {
                        return trimmed
                    }
                }
            }
        }
        return ""
    }

    private fun extractFunctionParameters(signature: String, language: String): List<String> {
        if (signature.isEmpty()) return emptyList()

        val startIndex = signature.indexOf('(')
        val endIndex = signature.lastIndexOf(')')

        if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) return emptyList()

        val paramsStr = signature.substring(startIndex + 1, endIndex).trim()
        if (paramsStr.isEmpty()) return emptyList()

        return paramsStr.split(',').map { param ->
            when (language) {
                "python" -> param.trim().split(':')[0].trim() // Remove type hints
                "kotlin" -> param.trim().split(':')[0].trim() // Remove type annotations
                else -> param.trim().split(' ').last().trim() // Get parameter name
            }
        }.filter { it.isNotEmpty() && it != "self" }
    }

    private fun extractReturnType(signature: String, language: String): String {
        when (language) {
            "python" -> {
                val arrowIndex = signature.indexOf("->")
                if (arrowIndex != -1) {
                    return signature.substring(arrowIndex + 2).replace(":", "").trim()
                }
            }
            "kotlin" -> {
                val colonIndex = signature.lastIndexOf(':')
                val braceIndex = signature.indexOf('{')
                if (colonIndex != -1) {
                    val endIndex = if (braceIndex != -1) braceIndex else signature.length
                    return signature.substring(colonIndex + 1, endIndex).trim()
                }
            }
            "java" -> {
                val words = signature.trim().split(' ')
                for (i in words.indices) {
                    if (words[i] in listOf("public", "private", "protected", "static")) continue
                    if (words[i].contains("(")) break
                    return words[i]
                }
            }
        }
        return ""
    }

    private fun getClassFields(text: String, className: String, language: String): List<String> {
        if (className.isEmpty()) return emptyList()

        val fields = mutableListOf<String>()
        val lines = text.split('\n')
        var insideClass = false
        var indentLevel = 0

        for (line in lines) {
            val trimmed = line.trim()

            // Find class definition
            if (trimmed.contains("class $className")) {
                insideClass = true
                indentLevel = line.takeWhile { it == ' ' || it == '\t' }.length
                continue
            }

            if (insideClass) {
                val currentIndent = line.takeWhile { it == ' ' || it == '\t' }.length

                // Exit class if we're back to same or lesser indentation
                if (trimmed.isNotEmpty() && currentIndent <= indentLevel) {
                    break
                }

                // Look for field declarations
                when (language) {
                    "python" -> {
                        if (trimmed.startsWith("self.") && trimmed.contains("=")) {
                            val fieldName = trimmed.substringAfter("self.").substringBefore("=").trim()
                            fields.add(fieldName)
                        }
                    }
                    "java", "kotlin" -> {
                        if ((trimmed.contains("val ") || trimmed.contains("var ") ||
                                    trimmed.contains("private ") || trimmed.contains("public ")) &&
                            !trimmed.contains("(") && !trimmed.contains("fun")) {
                            val parts = trimmed.split(' ')
                            for (i in parts.indices) {
                                if (parts[i] in listOf("val", "var", "private", "public") && i + 1 < parts.size) {
                                    val fieldName = parts[i + 1].substringBefore(':').substringBefore('=').trim()
                                    if (fieldName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                                        fields.add(fieldName)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return fields.distinct()
    }

    private fun analyzeFileStructure(text: String, language: String): FileStructure {
        val lines = text.split('\n')
        val imports = extractImports(text, language)
        val classes = mutableListOf<ClassInfo>()
        val functions = mutableListOf<FunctionInfo>()
        val mainBlocks = mutableListOf<String>()

        for ((lineIndex, line) in lines.withIndex()) {
            val trimmed = line.trim()

            when (language) {
                "python" -> {
                    if (trimmed.startsWith("class ") && trimmed.contains(":")) {
                        val className = trimmed.substringAfter("class ").substringBefore("(").substringBefore(":").trim()
                        val methods = extractClassMethods(lines, lineIndex, language)
                        val fields = extractClassFieldsFromLines(lines, lineIndex, language)
                        classes.add(ClassInfo(className, lineIndex + 1, methods, fields))
                    } else if (trimmed.startsWith("def ") && trimmed.contains("(")) {
                        val funcName = trimmed.substringAfter("def ").substringBefore("(").trim()
                        val params = extractFunctionParameters(trimmed, language)
                        val returnType = extractReturnType(trimmed, language)
                        val isMethod = isInsideClass(lines, lineIndex)
                        functions.add(FunctionInfo(funcName, lineIndex + 1, params, returnType, isMethod))
                    }
                }

            }

            // Extract main execution blocks
            if (trimmed.startsWith("if __name__") ||
                trimmed.startsWith("public static void main") ||
                trimmed == "main()") {
                mainBlocks.add(trimmed)
            }
        }

        return FileStructure(lines.size, imports, classes, functions, mainBlocks)
    }

    private fun extractClassMethods(lines: List<String>, classLineIndex: Int, language: String): List<String> {
        val methods = mutableListOf<String>()
        val classIndent = lines[classLineIndex].takeWhile { it == ' ' || it == '\t' }.length

        for (i in (classLineIndex + 1) until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length

            // Exit class scope
            if (trimmed.isNotEmpty() && indent <= classIndent) break

            if (language == "python" && trimmed.startsWith("def ") && trimmed.contains("(")) {
                val methodName = trimmed.substringAfter("def ").substringBefore("(").trim()
                methods.add(methodName)
            }
        }

        return methods
    }

    private fun extractClassFieldsFromLines(lines: List<String>, classLineIndex: Int, language: String): List<String> {
        val fields = mutableListOf<String>()
        val classIndent = lines[classLineIndex].takeWhile { it == ' ' || it == '\t' }.length

        for (i in (classLineIndex + 1) until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length

            // Exit class scope
            if (trimmed.isNotEmpty() && indent <= classIndent) break

            if (language == "python" && trimmed.startsWith("self.") && trimmed.contains("=")) {
                val fieldName = trimmed.substringAfter("self.").substringBefore("=").trim()
                fields.add(fieldName)
            }
        }

        return fields.distinct()
    }

    private fun isInsideClass(lines: List<String>, functionLineIndex: Int): Boolean {
        for (i in (functionLineIndex - 1) downTo 0) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("class ") && trimmed.contains(":")) {
                return true
            }
        }
        return false
    }

    // Keep existing methods but enhance them...

    private fun detectLanguage(psiFile: PsiFile?): String {
        val fileTypeName = psiFile?.fileType?.name?.lowercase() ?: ""
        val fileName = psiFile?.name?.lowercase() ?: ""

        println("Detecting language - FileType: '$fileTypeName', FileName: '$fileName'")

        return when {
            fileTypeName.contains("python") || fileName.endsWith(".py") -> "python"
            fileTypeName.contains("java") || fileName.endsWith(".java") -> "java"
            fileTypeName.contains("kotlin") || fileName.endsWith(".kt") || fileName.endsWith(".kts") -> "kotlin"
            fileTypeName.contains("javascript") || fileName.endsWith(".js") -> "javascript"
            fileTypeName.contains("typescript") || fileName.endsWith(".ts") -> "typescript"
            fileTypeName.contains("go") || fileName.endsWith(".go") -> "go"
            fileTypeName.contains("rust") || fileName.endsWith(".rs") -> "rust"
            fileTypeName.contains("cpp") || fileName.endsWith(".cpp") || fileName.endsWith(".cc") -> "cpp"
            fileTypeName.contains("csharp") || fileName.endsWith(".cs") -> "csharp"
            else -> {
                // Try to detect from file extension as last resort
                when {
                    fileName.endsWith(".java") -> "java"
                    fileName.endsWith(".py") -> "python"
                    fileName.endsWith(".js") -> "javascript"
                    fileName.endsWith(".ts") -> "typescript"
                    fileName.endsWith(".kt") -> "kotlin"
                    else -> "unknown" // Don't default to Python!
                }
            }
        }
    }

    private fun getIndentation(line: String): String {
        return line.takeWhile { it == ' ' || it == '\t' }
    }

    private fun getPreviousLines(document: com.intellij.openapi.editor.Document, currentLineNumber: Int, count: Int): List<String> {
        val lines = mutableListOf<String>()
        val startLine = maxOf(0, currentLineNumber - count)

        for (lineNum in startLine until currentLineNumber) {
            try {
                val lineStart = document.getLineStartOffset(lineNum)
                val lineEnd = document.getLineEndOffset(lineNum)
                val lineText = document.getText().substring(lineStart, lineEnd)
                if (lineText.trim().isNotEmpty()) {
                    lines.add(lineText)
                }
            } catch (e: Exception) {
                // Skip problematic lines
            }
        }

        return lines
    }

    private fun extractImports(text: String, language: String): List<String> {
        val imports = mutableListOf<String>()
        val lines = text.split('\n')

        for (line in lines.take(100)) { // Increased from 50
            val trimmed = line.trim()
            when (language) {
                "python" -> {
                    if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                        imports.add(trimmed)
                    }
                }
                "java", "kotlin" -> {
                    if (trimmed.startsWith("import ")) {
                        imports.add(trimmed)
                    }
                }
                "javascript", "typescript" -> {
                    if (trimmed.startsWith("import ") || trimmed.startsWith("const ") && trimmed.contains("require(")) {
                        imports.add(trimmed)
                    }
                }
            }
        }

        return imports
    }

    private fun findCurrentFunction(text: String, offset: Int, language: String): String {
        val beforeCursor = text.substring(0, offset)
        val lines = beforeCursor.split('\n').reversed()

        when (language) {
            "python" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("def ") && trimmed.contains("(")) {
                        val functionName = trimmed.substringAfter("def ").substringBefore("(").trim()
                        return functionName
                    }
                }
            }
            "java", "kotlin" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if ((trimmed.contains("fun ") || trimmed.contains(" void ") || trimmed.contains(" int ") ||
                                trimmed.contains(" String ")) && trimmed.contains("(")) {
                        val parts = trimmed.split(" ")
                        for (i in parts.indices) {
                            if ((parts[i] == "fun" || parts[i].endsWith("void") || parts[i].endsWith("int") ||
                                        parts[i].endsWith("String")) && i + 1 < parts.size) {
                                val functionName = parts[i + 1].substringBefore("(")
                                return functionName
                            }
                        }
                    }
                }
            }
            "javascript", "typescript" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("function ") || trimmed.contains(" => ") ||
                        (trimmed.contains("const ") && trimmed.contains(" = "))) {
                        if (trimmed.startsWith("function ")) {
                            val functionName = trimmed.substringAfter("function ").substringBefore("(").trim()
                            return functionName
                        }
                    }
                }
            }
        }

        return ""
    }

    private fun findCurrentClass(text: String, offset: Int, language: String): String {
        val beforeCursor = text.substring(0, offset)
        val lines = beforeCursor.split('\n').reversed()

        when (language) {
            "python" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("class ") && trimmed.contains(":")) {
                        val className = trimmed.substringAfter("class ").substringBefore("(").substringBefore(":").trim()
                        return className
                    }
                }
            }
            "java", "kotlin" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.contains("class ")) {
                        val parts = trimmed.split(" ")
                        val classIndex = parts.indexOf("class")
                        if (classIndex >= 0 && classIndex + 1 < parts.size) {
                            val className = parts[classIndex + 1].substringBefore("{").substringBefore(":").trim()
                            return className
                        }
                    }
                }
            }
            "javascript", "typescript" -> {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("class ")) {
                        val className = trimmed.substringAfter("class ").substringBefore(" ").substringBefore("{").trim()
                        return className
                    }
                }
            }
        }

        return ""
    }

    private fun extractVariables(lines: List<String>, language: String): List<String> {
        val variables = mutableSetOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            when (language) {
                "python" -> {
                    if (trimmed.contains(" = ") && !trimmed.startsWith("#")) {
                        val varName = trimmed.substringBefore(" = ").trim()
                        if (varName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                            variables.add(varName)
                        }
                    }
                }
                "java", "kotlin" -> {
                    if (trimmed.contains(" ") && (trimmed.contains("val ") || trimmed.contains("var ") ||
                                trimmed.contains("int ") || trimmed.contains("String "))) {
                        val parts = trimmed.split(" ")
                        for (i in parts.indices) {
                            if ((parts[i] == "val" || parts[i] == "var" || parts[i] == "int" ||
                                        parts[i] == "String") && i + 1 < parts.size) {
                                val varName = parts[i + 1].substringBefore("=").substringBefore(":").trim()
                                if (varName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                                    variables.add(varName)
                                }
                            }
                        }
                    }
                }
                "javascript", "typescript" -> {
                    if (trimmed.contains("const ") || trimmed.contains("let ") || trimmed.contains("var ")) {
                        val parts = trimmed.split(" ")
                        for (i in parts.indices) {
                            if ((parts[i] == "const" || parts[i] == "let" || parts[i] == "var") && i + 1 < parts.size) {
                                val varName = parts[i + 1].substringBefore("=").trim()
                                if (varName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                                    variables.add(varName)
                                }
                            }
                        }
                    }
                }
            }
        }

        return variables.toList()
    }
}
