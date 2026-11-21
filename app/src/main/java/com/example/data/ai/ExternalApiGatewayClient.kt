package com.example.data.ai

import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object ExternalApiGatewayClient {

  suspend fun testProviderConnection(provider: AiApiProviderEntry): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (provider.protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS) {
          return@runCatching "Built-in Firebase AI Logic (App Check Attested) is active and ready. Zero API key needed."
        }
        val key = provider.apiKey.trim()
        require(key.isNotEmpty()) {
          "Please enter an API key for '${provider.serviceName}' before testing."
        }
        val startMs = System.currentTimeMillis()
        val reply = executeCompletion(
          provider = provider,
          systemPrompt = "You are an academic peer-review connectivity probe. Reply in 1 short sentence.",
          userPrompt = "Confirm that API service '${provider.serviceName}' (${provider.modelId}) is online for Paper Critic.",
          expectJsonObject = false
        )
        val elapsedMs = System.currentTimeMillis() - startMs
        "Connected (${elapsedMs}ms) • ${provider.modelId}: ${reply.take(120)}"
      }
    }

  suspend fun executeCompletion(
    provider: AiApiProviderEntry,
    systemPrompt: String,
    userPrompt: String,
    expectJsonObject: Boolean = false
  ): String = withContext(Dispatchers.IO) {
    val apiKey = provider.apiKey.trim()
    require(apiKey.isNotEmpty()) {
      "Missing API key for ${provider.serviceName}."
    }

    when (provider.protocolType) {
      ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS ->
        throw IllegalArgumentException("FIREBASE_AI_LOGIC_KEYLESS is handled directly by Firebase.ai.")

      ApiProtocolType.OPENAI_COMPATIBLE_REST ->
        callOpenAiCompatibleEndpoint(
          baseUrl = provider.baseUrl.trim(),
          modelId = provider.modelId.trim(),
          apiKey = apiKey,
          systemPrompt = systemPrompt,
          userPrompt = userPrompt,
          expectJsonObject = expectJsonObject
        )

      ApiProtocolType.GEMINI_DIRECT_REST ->
        callGeminiDirectRestEndpoint(
          baseUrl = provider.baseUrl.trim(),
          modelId = provider.modelId.trim(),
          apiKey = apiKey,
          systemPrompt = systemPrompt,
          userPrompt = userPrompt,
          expectJsonObject = expectJsonObject
        )
    }
  }

  private fun callOpenAiCompatibleEndpoint(
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    userPrompt: String,
    expectJsonObject: Boolean
  ): String {
    val normalizedUrl = if (baseUrl.endsWith("/chat/completions")) {
      baseUrl
    } else {
      baseUrl.trimEnd('/') + "/chat/completions"
    }

    val payload = JSONObject().apply {
      put("model", modelId.ifBlank { "deepseek/deepseek-r1:free" })
      put("temperature", if (expectJsonObject) 0.15 else 0.35)
      val messages = JSONArray().apply {
        if (systemPrompt.isNotBlank()) {
          put(
            JSONObject().apply {
              put("role", "system")
              put("content", systemPrompt)
            }
          )
        }
        put(
          JSONObject().apply {
            put("role", "user")
            put("content", userPrompt)
          }
        )
      }
      put("messages", messages)
    }

    val rawResponse = postJson(
      endpointUrl = normalizedUrl,
      headers = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "HTTP-Referer" to "https://aistudio.google.com/papercritic",
        "X-Title" to "Paper Critic Academic Workbench"
      ),
      bodyJson = payload.toString()
    )

    val root = JSONObject(rawResponse)
    val choices = root.optJSONArray("choices")
      ?: throw IllegalStateException("No choices array returned from $normalizedUrl: ${rawResponse.take(240)}")
    if (choices.length() == 0) {
      throw IllegalStateException("Empty choices returned from $normalizedUrl.")
    }
    val messageObj = choices.getJSONObject(0).optJSONObject("message")
    val primaryContent = messageObj?.optString("content", "").orEmpty().trim()
    val fallbackReasoning = messageObj?.optString("reasoning_content", "").orEmpty().trim()
    val content = primaryContent.ifBlank { fallbackReasoning }
    if (content.isBlank()) {
      throw IllegalStateException("Empty message content returned by $modelId.")
    }
    return if (expectJsonObject) extractCleanJsonObject(content) else content
  }

  private fun callGeminiDirectRestEndpoint(
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    userPrompt: String,
    expectJsonObject: Boolean
  ): String {
    val cleanBase = baseUrl.trimEnd('/')
    val cleanModel = modelId.removePrefix("models/").ifBlank { "gemini-3.8-flash" }
    val endpoint = "$cleanBase/models/$cleanModel:generateContent?key=$apiKey"

    val combinedPrompt = if (systemPrompt.isNotBlank()) {
      "$systemPrompt\n\n$userPrompt"
    } else {
      userPrompt
    }

    val payload = JSONObject().apply {
      val contents = JSONArray().apply {
        put(
          JSONObject().apply {
            put(
              "parts",
              JSONArray().apply {
                put(JSONObject().apply { put("text", combinedPrompt) })
              }
            )
          }
        )
      }
      put("contents", contents)
      if (expectJsonObject) {
        put(
          "generationConfig",
          JSONObject().apply {
            put("responseMimeType", "application/json")
            put("temperature", 0.15)
          }
        )
      }
    }

    val rawResponse = postJson(
      endpointUrl = endpoint,
      headers = mapOf("Content-Type" to "application/json"),
      bodyJson = payload.toString()
    )

    val root = JSONObject(rawResponse)
    val candidates = root.optJSONArray("candidates")
      ?: throw IllegalStateException("No candidates returned from Gemini REST endpoint.")
    if (candidates.length() == 0) {
      throw IllegalStateException("Empty candidates returned from Gemini REST endpoint.")
    }
    val parts = candidates.getJSONObject(0)
      .optJSONObject("content")
      ?.optJSONArray("parts")
    val text = parts?.optJSONObject(0)?.optString("text", "").orEmpty().trim()
    if (text.isBlank()) {
      throw IllegalStateException("Empty response text from Gemini REST endpoint.")
    }
    return if (expectJsonObject) extractCleanJsonObject(text) else text
  }

  private fun postJson(
    endpointUrl: String,
    headers: Map<String, String>,
    bodyJson: String
  ): String {
    val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 25_000
      readTimeout = 90_000
      doInput = true
      doOutput = true
      headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }

    return try {
      OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
        writer.write(bodyJson)
        writer.flush()
      }
      val statusCode = connection.responseCode
      val stream = if (statusCode in 200..299) {
        connection.inputStream
      } else {
        connection.errorStream ?: connection.inputStream
      }
      val body = stream?.let {
        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
      }.orEmpty()
      if (statusCode !in 200..299) {
        throw IllegalStateException("HTTP $statusCode from API provider: ${body.take(260)}")
      }
      body
    } finally {
      connection.disconnect()
    }
  }

  fun extractCleanJsonObject(rawText: String): String {
    val noThink = rawText
      .replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "")
      .trim()
    val trimmed = noThink
      .removePrefix("```json")
      .removePrefix("```JSON")
      .removePrefix("```")
      .removeSuffix("```")
      .trim()
    val firstBrace = trimmed.indexOf('{')
    val lastBrace = trimmed.lastIndexOf('}')
    return if (firstBrace != -1 && lastBrace > firstBrace) {
      trimmed.substring(firstBrace, lastBrace + 1)
    } else {
      trimmed
    }
  }
}
