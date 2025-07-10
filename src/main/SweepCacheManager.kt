package org.jetbrains.plugins.template

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

data class CachedCompletion(
    val suggestion: String,
    val context: CodeContext,
    val timestamp: Long = System.currentTimeMillis(),
    val usage: Int = 0
) {
    fun isExpired(ttlMs: Long = 300_000): Boolean { // 5 minutes default TTL
        return System.currentTimeMillis() - timestamp > ttlMs
    }

    fun withIncrementedUsage(): CachedCompletion {
        return copy(usage = usage + 1)
    }
}

class SweepCacheManager {
    companion object {
        private const val MAX_CACHE_SIZE = 1000
        private const val DEFAULT_TTL_MS = 300_000L // 5 minutes
        private const val SIMILARITY_THRESHOLD = 0.8
    }

    private val cache = ConcurrentHashMap<String, CachedCompletion>()
    private val accessOrder = ConcurrentLinkedQueue<String>()

    // NEW: Context validation method
    private fun shouldInvalidateCache(cacheKey: String, currentContext: CodeContext): Boolean {
        val cached = cache[cacheKey] ?: return false

        // Invalidate if the line structure has changed significantly
        val currentLineWords = currentContext.currentLine.trim().split("\\s+".toRegex())
        val cachedLineWords = cached.context.currentLine.trim().split("\\s+".toRegex())

        // If the line has changed structure (different word count or major differences)
        if (currentLineWords.size != cachedLineWords.size) {
            println("Cache INVALIDATE: Line structure changed")
            return true
        }

        // If we're in a different function/class context
        if (currentContext.currentFunction != cached.context.currentFunction ||
            currentContext.currentClass != cached.context.currentClass) {
            println("Cache INVALIDATE: Function/class context changed")
            return true
        }

        // If there are syntax errors in the cached suggestion
        if (hasObviousErrors(cached.suggestion)) {
            println("Cache INVALIDATE: Cached suggestion has errors")
            return true
        }

        return false
    }

    fun getCachedCompletion(cacheKey: String, currentContext: CodeContext): String? {
        // Check if we should invalidate this cache entry
        if (shouldInvalidateCache(cacheKey, currentContext)) {
            cache.remove(cacheKey)
            accessOrder.remove(cacheKey)
            println("Cache INVALIDATED for key: $cacheKey")
            return null
        }

        // First try exact match
        val exactMatch = cache[cacheKey]
        if (exactMatch != null && !exactMatch.isExpired()) {
            updateAccessOrder(cacheKey)
            cache[cacheKey] = exactMatch.withIncrementedUsage()
            println("Cache HIT (exact): $cacheKey -> '${exactMatch.suggestion}'")
            return exactMatch.suggestion
        }

        // Try similarity-based matching for more flexible caching
        val similarMatch = findSimilarCachedCompletion(currentContext)
        if (similarMatch != null) {
            // But also validate the similar match isn't broken
            if (!hasObviousErrors(similarMatch.second.suggestion)) {
                println("Cache HIT (similar): ${similarMatch.first} -> '${similarMatch.second.suggestion}'")
                return similarMatch.second.suggestion
            }
        }

        println("Cache MISS: $cacheKey")
        return null
    }

    fun cacheCompletion(cacheKey: String, suggestion: String, context: CodeContext) {
        if (suggestion.isBlank() || suggestion.startsWith("#") || hasObviousErrors(suggestion)) {
            println("Cache SKIP: Not caching problematic suggestion: '$suggestion'")
            return // Don't cache errors or empty suggestions
        }

        // Clean up expired entries before adding new ones
        cleanupExpiredEntries()

        // Ensure cache size limit
        if (cache.size >= MAX_CACHE_SIZE) {
            evictLeastRecentlyUsed()
        }

        val cachedCompletion = CachedCompletion(suggestion, context)
        cache[cacheKey] = cachedCompletion
        updateAccessOrder(cacheKey)

        println("Cache STORE: $cacheKey -> '${suggestion}' (cache size: ${cache.size})")
    }

    // ENHANCED: Detect obviously wrong suggestions before caching
    private fun hasObviousErrors(suggestion: String): Boolean {
        val lower = suggestion.lowercase()

        val basicErrors = listOf(
            "npump", "inmport", "imort", "deff ", "clas ", "pring",
            "___init__", "def ___", "self.super(", "self d self"
        )

        val nonStandardImports = listOf(
            "import numpy as n",
            "import pandas as p",
            "import matplotlib as m",
            "import matplotlib.pyplot as p",
        )

        // Check for Python indentation issues
        val pythonIndentErrors = listOf(
            "def __init__(\n", // Method not indented in class
            "class DNN:\ndef", // Method not indented after class
        )

        return basicErrors.any { lower.contains(it) } ||
                nonStandardImports.any { lower == it } ||
                pythonIndentErrors.any { suggestion.contains(it) } ||
                // Check for obvious Python syntax errors
                suggestion.contains("self.super(") ||
                suggestion.matches(Regex(".*def _{3,}.*")) // Triple+ underscore methods
    }

    private fun findSimilarCachedCompletion(currentContext: CodeContext): Pair<String, CachedCompletion>? {
        return cache.entries
            .filter { !it.value.isExpired() }
            .map { entry ->
                val similarity = calculateContextSimilarity(currentContext, entry.value.context)
                Triple(entry.key, entry.value, similarity)
            }
            .filter { it.third > SIMILARITY_THRESHOLD }
            .maxByOrNull { it.third }
            ?.let { Pair(it.first, it.second) }
    }

    private fun calculateContextSimilarity(context1: CodeContext, context2: CodeContext): Double {
        var totalScore = 0.0
        var maxScore = 0.0

        // Current line similarity (high weight)
        val lineWeight = 0.4
        val lineSimilarity = calculateStringSimilarity(context1.currentLine, context2.currentLine)
        totalScore += lineSimilarity * lineWeight
        maxScore += lineWeight

        // Function context similarity
        val functionWeight = 0.2
        val functionSimilarity = if (context1.currentFunction == context2.currentFunction && context1.currentFunction.isNotEmpty()) 1.0 else 0.0
        totalScore += functionSimilarity * functionWeight
        maxScore += functionWeight

        // Class context similarity
        val classWeight = 0.2
        val classSimilarity = if (context1.currentClass == context2.currentClass && context1.currentClass.isNotEmpty()) 1.0 else 0.0
        totalScore += classSimilarity * classWeight
        maxScore += classWeight

        // Language similarity (must match)
        val languageWeight = 0.1
        val languageSimilarity = if (context1.fileLanguage == context2.fileLanguage) 1.0 else 0.0
        totalScore += languageSimilarity * languageWeight
        maxScore += languageWeight

        // Variable overlap similarity
        val variableWeight = 0.1
        val variableOverlap = calculateListOverlap(context1.variables, context2.variables)
        totalScore += variableOverlap * variableWeight
        maxScore += variableWeight

        return if (maxScore > 0) totalScore / maxScore else 0.0
    }

    private fun calculateStringSimilarity(str1: String, str2: String): Double {
        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0

        // Use Levenshtein distance for string similarity
        val distance = levenshteinDistance(str1.lowercase(), str2.lowercase())
        val maxLength = maxOf(str1.length, str2.length)

        return 1.0 - (distance.toDouble() / maxLength)
    }

    private fun levenshteinDistance(str1: String, str2: String): Int {
        val m = str1.length
        val n = str2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (str1[i - 1] == str2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[m][n]
    }

    private fun calculateListOverlap(list1: List<String>, list2: List<String>): Double {
        if (list1.isEmpty() && list2.isEmpty()) return 1.0
        if (list1.isEmpty() || list2.isEmpty()) return 0.0

        val set1 = list1.toSet()
        val set2 = list2.toSet()
        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size

        return intersection.toDouble() / union
    }

    private fun updateAccessOrder(cacheKey: String) {
        accessOrder.remove(cacheKey) // Remove if already exists
        accessOrder.offer(cacheKey)  // Add to end (most recent)
    }

    private fun evictLeastRecentlyUsed() {
        val oldestKey = accessOrder.poll()
        if (oldestKey != null) {
            cache.remove(oldestKey)
            println("Cache EVICT (LRU): $oldestKey")
        }
    }

    private fun cleanupExpiredEntries() {
        val expiredKeys = cache.entries
            .filter { it.value.isExpired() }
            .map { it.key }

        expiredKeys.forEach { key ->
            cache.remove(key)
            accessOrder.remove(key)
        }

        if (expiredKeys.isNotEmpty()) {
            println("Cache CLEANUP: Removed ${expiredKeys.size} expired entries")
        }
    }

    fun invalidateCache() {
        cache.clear()
        accessOrder.clear()
        println("Cache INVALIDATED: All entries cleared")
    }

    fun invalidateByPattern(pattern: String) {
        val keysToRemove = cache.keys.filter { it.contains(pattern) }
        keysToRemove.forEach { key ->
            cache.remove(key)
            accessOrder.remove(key)
        }
        println("Cache INVALIDATE by pattern '$pattern': Removed ${keysToRemove.size} entries")
    }

    fun getCacheStats(): CacheStats {
        val totalEntries = cache.size
        val expiredEntries = cache.values.count { it.isExpired() }
        val averageUsage = if (cache.isNotEmpty()) {
            cache.values.map { it.usage }.average()
        } else 0.0

        return CacheStats(totalEntries, expiredEntries, averageUsage)
    }

    // Preload cache with common patterns for faster initial completions
    fun preloadCommonPatterns() {
        // Temporarily disabled to fix syntax errors
        println("Cache PRELOAD: Disabled for debugging")
    }

    private fun createDummyContext(language: String, currentLine: String, variables: List<String> = emptyList()): CodeContext {
        return CodeContext(
            currentLine = currentLine,
            currentFunction = "",
            currentClass = "",
            imports = emptyList(),
            previousLines = emptyList(),
            fileLanguage = language,
            indentation = "",
            variables = variables,
            functionSignature = "",
            functionParameters = emptyList(),
            returnType = "",
            classFields = emptyList(),
            allFunctions = emptyList(),
            contextLines = emptyList(),
            fileStructure = FileStructure(0, emptyList(), emptyList(), emptyList(), emptyList())
        )
    }
}

data class CacheStats(
    val totalEntries: Int,
    val expiredEntries: Int,
    val averageUsage: Double
) {
    override fun toString(): String {
        return "Cache Stats: $totalEntries total, $expiredEntries expired, ${averageUsage.toString().take(4)} avg usage"
    }
}
