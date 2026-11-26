package com.ps5translator.pro

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class GeminiTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 3, TimeUnit.MINUTES))
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var activeCall: Call? = null

    private val history = ArrayDeque<Pair<String, String>>(6)
    private val cache = LinkedHashMap<String, String>(200, 0.75f, true)

    fun cancelActive() {
        activeCall?.cancel()
        activeCall = null
    }

    fun clearHistory() {
        synchronized(history) { history.clear() }
    }

    fun translateStreaming(
        apiKey: String,
        model: String,
        englishText: String,
        dialect: DialectMode,
        gamePreset: String,
        onPartial: (String) -> Unit,
        onComplete: (translated: String, latencyMs: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        if (apiKey.isBlank()) {
            onError("أدخل مفتاح Gemini API في الإعدادات أولاً")
            return
        }

        val cacheKey = "${dialect.name}|${OcrPostProcessor.normalizeForComparison(englishText)}"
        synchronized(cache) {
            cache[cacheKey]?.let { cached ->
                onPartial(cached)
                onComplete(cached, 0L)
                return
            }
        }

        cancelActive()

        val startTime = System.currentTimeMillis()
        val systemPrompt = buildSystemPrompt(dialect, gamePreset)
        val userPrompt = buildUserPrompt(englishText)

        val bodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.25)
                put("maxOutputTokens", 140)
                put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            })
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:streamGenerateContent?alt=sse&key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        val call = client.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(180) ?: ""
                    onError("خطأ API (${response.code}): $errBody")
                    return
                }

                val stream = response.body?.byteStream() ?: run {
                    onError("رد فارغ من الخادم")
                    return
                }

                val accumulated = StringBuilder()
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim() ?: continue
                        if (!trimmed.startsWith("data:")) continue
                        val payload = trimmed.removePrefix("data:").trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue

                        val chunkText = extractTextFromChunk(payload)
                        if (chunkText.isNotEmpty()) {
                            accumulated.append(chunkText)
                            val liveClean = cleanOutput(accumulated.toString())
                            if (liveClean.isNotEmpty()) {
                                onPartial(liveClean)
                            }
                        }
                    }
                }

                val finalArabic = cleanOutput(accumulated.toString())
                val elapsed = System.currentTimeMillis() - startTime
                if (finalArabic.isNotEmpty()) {
                    synchronized(cache) {
                        if (cache.size >= 250) {
                            val firstKey = cache.keys.firstOrNull()
                            if (firstKey != null) cache.remove(firstKey)
                        }
                        cache[cacheKey] = finalArabic
                    }
                    synchronized(history) {
                        if (history.size >= 5) history.removeFirst()
                        history.addLast(englishText to finalArabic)
                    }
                    onComplete(finalArabic, elapsed)
                }
            }
        } catch (e: IOException) {
            if (!call.isCanceled()) {
                onError("تعذر الاتصال: ${e.message ?: "تحقق من الإنترنت"}")
            }
        } catch (e: Exception) {
            onError("خطأ غير متوقع: ${e.message}")
        }
    }

    private fun extractTextFromChunk(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanOutput(raw: String): String {
        return raw
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '«', '»')
    }

    private fun buildSystemPrompt(dialect: DialectMode, gamePreset: String): String {
        return when (dialect) {
            DialectMode.ORIGINAL_MSA -> """
                You are an elite AAA video game localization translator specializing in $gamePreset.
                Translate the English game subtitle into natural, authentic, cinematic Arabic matching official studio localization quality.
                Rules:
                1. Preserve the exact original meaning, tone, street slang intent, sarcasm, and urgency (NEVER literal Google-Translate style).
                2. Silently fix obvious OCR typos from context (e.g. "quy" -> "guy", "l'm" -> "I'm").
                3. Keep character names natural in Arabic if prefixed.
                4. Output ONLY the final Arabic subtitle line. No notes, no English, no quotation marks.
            """.trimIndent()

            DialectMode.IRAQI -> """
                You are an elite video game translator specializing in $gamePreset.
                Translate the English game subtitle into natural, authentic Iraqi Arabic (Baghdadi street/conversational Iraqi dialect).
                Rules:
                1. Keep the EXACT original meaning, attitude, street slang energy, and emotion — expressed naturally in Iraqi Arabic.
                2. Silently fix obvious OCR typos from context (e.g. "quy" -> "guy", "l'm" -> "I'm").
                3. Keep it concise and punchy so it reads fast like real game subtitles and sounds natural when read aloud by TTS.
                4. Output ONLY the final Iraqi Arabic subtitle line. No notes, no English, no quotation marks.
            """.trimIndent()
        }
    }

    private fun buildUserPrompt(currentLine: String): String {
        val ctx = synchronized(history) {
            if (history.isEmpty()) ""
            else history.joinToString("\n") { (en, ar) -> "- $en => $ar" }
        }
        return if (ctx.isEmpty()) {
            "Subtitle: $currentLine"
        } else {
            "Recent context:\n$ctx\n\nTranslate ONLY this new subtitle:\n$currentLine"
        }
    }
}
