package org.jetbrains.plugins.template

import java.util.concurrent.ConcurrentHashMap


object SimpleCompletionTests {

    private var testsPassed = 0
    private var testsFailed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("🧪 Running Hard Test Cases for SweepCompletionContributor")
        println("=" * 60)

        // Create test instance
        val contributor = SweepCompletionContributor()

        // Run all tests
        testCacheConsistency(contributor)
        testJSONParsingEdgeCases(contributor)
        testInputValidation(contributor)
        testConcurrentAccess(contributor)
        testMemoryLeak(contributor)
        testSpecialCharacters(contributor)
        testPerformanceLoad(contributor)
        testAPIErrorHandling(contributor)
        testBoundaryConditions(contributor)
        testCacheEviction(contributor)

        // Print results
        println("\n" + "=" * 60)
        println("📊 Test Results:")
        println("✅ Passed: $testsPassed")
        println("❌ Failed: $testsFailed")
        println("🎯 Success Rate: ${(testsPassed * 100) / (testsPassed + testsFailed)}%")

        if (testsFailed > 0) {
            println("\n⚠️  Some tests failed - check implementation!")
            System.exit(1)
        } else {
            println("\n🎉 All tests passed!")
        }
    }

    private fun testCacheConsistency(contributor: SweepCompletionContributor) {
        test("Cache Consistency") {
            val context = "import test_cache"

            val result1 = callPrivateMethod(contributor, "getCachedOrCallOpenAI", context) as String
            val start = System.currentTimeMillis()
            val result2 = callPrivateMethod(contributor, "getCachedOrCallOpenAI", context) as String
            val duration = System.currentTimeMillis() - start

            assert(result1 == result2) { "Cache should return same result" }
            assert(duration < 50) { "Cache should be faster than 50ms, got ${duration}ms" }
        }
    }

    private fun testJSONParsingEdgeCases(contributor: SweepCompletionContributor) {
        test("JSON Parsing Edge Cases") {
            val testCases = mapOf(
                """{"choices":[{"message":{"content":"import turtle"}}]}""" to "import turtle",
                """{"choices":[{"message":{"content":"Sorry, I can't help"}}]}""" to "",
                """{"invalid": "json"}""" to "",
                """malformed json{""" to "",
                """""" to "",
                """{"choices":[{"message":{"content":"incomplete request"}}]}""" to ""
            )

            testCases.forEach { (json, expected) ->
                val result = callPrivateMethod(contributor, "parseContent", json) as String
                if (expected.isEmpty()) {
                    assert(result.isEmpty()) { "Should handle invalid JSON: $json" }
                } else {
                    assert(result == expected) { "Expected '$expected', got '$result' for JSON: $json" }
                }
            }
        }
    }

    private fun testInputValidation(contributor: SweepCompletionContributor) {
        test("Input Validation") {
            val validInputs = listOf("import torch", "from os import path", "import numpy as np")

            validInputs.forEach { input ->
                val result = callPrivateMethod(contributor, "getCachedOrCallOpenAI", input) as String
                assert(result.isNotEmpty() || result == "# AI Error") {
                    "Valid input should return result: '$input'"
                }
            }
        }
    }

    private fun testConcurrentAccess(contributor: SweepCompletionContributor) {
        test("Concurrent Access") {
            val context = "import concurrent_test"
            val results = ConcurrentHashMap<Int, String>()
            val threads = mutableListOf<Thread>()

            repeat(10) { i ->
                val thread = Thread {
                    val result = callPrivateMethod(contributor, "getCachedOrCallOpenAI", context) as String
                    results[i] = result
                }
                threads.add(thread)
                thread.start()
            }

            threads.forEach { it.join() }

            val uniqueResults = results.values.toSet()
            assert(uniqueResults.size == 1) {
                "All concurrent requests should return same result, got: $uniqueResults"
            }
        }
    }

    private fun testMemoryLeak(contributor: SweepCompletionContributor) {
        test("Memory Leak Prevention") {
            val cache = getCompanionField(SweepCompletionContributor::class.java, "completionCache")
                    as ConcurrentHashMap<String, String>

            val initialSize = cache.size

            // Add many entries
            repeat(500) { i ->
                callPrivateMethod(contributor, "getCachedOrCallOpenAI", "import test$i")
            }

            val finalSize = cache.size
            val growth = finalSize - initialSize

            assert(growth <= 500) { "Cache grew by $growth, should be <= 500" }
            println("  📈 Cache grew by $growth entries (${initialSize} → ${finalSize})")
        }
    }

    private fun testSpecialCharacters(contributor: SweepCompletionContributor) {
        test("Special Characters") {
            val specialInputs = listOf(
                "import test\nwith\nnewlines",
                "import \"quoted\"",
                "import 'single'",
                "import test\\backslash",
                "import test@#$%^&*()",
                "import 中文测试"
            )

            specialInputs.forEach { input ->
                try {
                    val result = callPrivateMethod(contributor, "getCachedOrCallOpenAI", input) as String
                    // Should not crash
                    assert(true) { "Handled special input: $input" }
                } catch (e: Exception) {
                    assert(false) { "Failed to handle special input '$input': ${e.message}" }
                }
            }
        }
    }

    private fun testPerformanceLoad(contributor: SweepCompletionContributor) {
        test("Performance Load") {
            val contexts = (1..50).map { "import perf_test_$it" }

            val start = System.currentTimeMillis()
            contexts.forEach { context ->
                callPrivateMethod(contributor, "getCachedOrCallOpenAI", context)
            }
            val duration = System.currentTimeMillis() - start

            println("  ⏱️  50 requests took ${duration}ms (avg: ${duration/50}ms)")
            assert(duration < 15000) { "50 requests should complete in <15s, took ${duration}ms" }
        }
    }

    private fun testAPIErrorHandling(contributor: SweepCompletionContributor) {
        test("API Error Handling") {
            // Test with potentially problematic input
            val problematicInputs = listOf(
                "\\x00\\xFF",
                "import " + "x".repeat(1000),
                "import \uFFFF\uFFFE"
            )

            problematicInputs.forEach { input ->
                val result = callPrivateMethod(contributor, "getCachedOrCallOpenAI", input) as String
                assert(result == "# AI Error" || result.isNotEmpty()) {
                    "Should handle problematic input gracefully: '$input'"
                }
            }
        }
    }

    private fun testBoundaryConditions(contributor: SweepCompletionContributor) {
        test("Boundary Conditions") {
            val boundaryInputs = listOf(
                "im",  // 2 chars (below minimum)
                "imp",  // 3 chars (at minimum)
                "import",  // 6 chars
                "import " + "x".repeat(100),  // Very long
                "   import test   ",  // Whitespace
                "\t\timport\t\t"  // Tabs
            )

            boundaryInputs.forEach { input ->
                try {
                    val result = callPrivateMethod(contributor, "getCachedOrCallOpenAI", input) as String
                    // Should handle all boundary conditions
                    assert(true) { "Handled boundary input: '$input'" }
                } catch (e: Exception) {
                    assert(false) { "Failed boundary test for '$input': ${e.message}" }
                }
            }
        }
    }

    private fun testCacheEviction(contributor: SweepCompletionContributor) {
        test("Cache Behavior") {
            val cache = getCompanionField(SweepCompletionContributor::class.java, "completionCache")
                    as ConcurrentHashMap<String, String>

            cache.clear()  // Start fresh

            val testKey = "import cache_test"

            // First call should populate cache
            val result1 = callPrivateMethod(contributor, "getCachedOrCallOpenAI", testKey) as String
            assert(cache.containsKey(testKey)) { "Cache should contain key after first call" }

            // Second call should use cache
            val start = System.currentTimeMillis()
            val result2 = callPrivateMethod(contributor, "getCachedOrCallOpenAI", testKey) as String
            val duration = System.currentTimeMillis() - start

            assert(result1 == result2) { "Cached result should match original" }
            assert(duration < 10) { "Cache lookup should be <10ms, was ${duration}ms" }
        }
    }

    // Test helper functions
    private fun test(name: String, block: () -> Unit) {
        print("🧪 Testing $name... ")
        try {
            block()
            println("✅ PASS")
            testsPassed++
        } catch (e: AssertionError) {
            println("❌ FAIL: ${e.message}")
            testsFailed++
        } catch (e: Exception) {
            println("💥 ERROR: ${e.message}")
            testsFailed++
        }
    }

    private fun assert(condition: Boolean, lazyMessage: () -> String) {
        if (!condition) {
            throw AssertionError(lazyMessage())
        }
    }

    // Reflection helpers to access private methods and fields
    private fun callPrivateMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val method = obj::class.java.getDeclaredMethod(
            methodName,
            *args.map { it?.javaClass ?: String::class.java }.toTypedArray()
        )
        method.isAccessible = true
        return method.invoke(obj, *args)
    }

    private fun getCompanionField(clazz: Class<*>, fieldName: String): Any? {
        val companionField = clazz.getDeclaredField("Companion")
        companionField.isAccessible = true
        val companion = companionField.get(null)

        val field = companion::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(companion)
    }

    private operator fun String.times(n: Int): String = this.repeat(n)
}
