package com.example.data.ai

import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object ExternalApiGatewayClient {

  private val BLOCKED_HOST_SUFFIXES = listOf(
    ".local", ".localhost", ".internal", ".home.arpa", ".lan", ".corp", ".localdomain"
  )

  private val BLOCKED_EXACT_HOSTS = setOf(
    "localhost", "0.0.0.0", "127.0.0.1", "::1", "[::1]", "169.254.169.254", "metadata.google.internal"
  )

  /**
   * Validates custom AI provider URLs before saving or sending manuscripts/API keys:
   * - Requires strict HTTPS transport (`https://`) to protect manuscripts and credentials in transit.
   * - Rejects embedded URL credentials (`user:pass@host`).
   * - Rejects loopback, link-local, cloud metadata, and private/internal network destinations
   *   unless explicitly opted in for local diagnostics.
   */
  @Throws(IllegalArgumentException::class)
  fun validateEndpointUrl(
    rawUrl: String,
    resolveDns: Boolean = false
  ): URL {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotBlank()) { "Provider endpoint URL must not be blank." }

    val uri = try {
      URI(trimmed)
    } catch (e: Exception) {
      throw IllegalArgumentException("Invalid provider endpoint URL: ${e.message}", e)
    }

    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
    require(scheme == "https") {
      "Custom API endpoints must use HTTPS (found '${scheme.ifBlank { "missing scheme" }}'). Plaintext HTTP is blocked to protect manuscripts and API keys."
    }

    require(uri.userInfo.isNullOrBlank()) {
      "Provider endpoint URL must not embed credentials in the URL."
    }

    val host = uri.host?.trim()?.lowercase(Locale.US).orEmpty()
    require(host.isNotBlank()) { "Provider endpoint URL must include a valid hostname." }

    require(host !in BLOCKED_EXACT_HOSTS && BLOCKED_HOST_SUFFIXES.none { host.endsWith(it) }) {
      "Provider host '$host' is a local or internal destination and is blocked."
    }

    require(!isLiteralPrivateOrLoopbackIp(host)) {
      "Provider host '$host' resolves to a private, loopback, or link-local IP address and is blocked."
    }

    if (resolveDns) {
      val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
      if (addresses != null) {
        for (addr in addresses) {
          require(!isDisallowedInetAddress(addr)) {
            "Provider host '$host' resolved to a restricted internal/loopback IP address and is blocked."
          }
        }
      }
    }

    return URL(trimmed)
  }

  private fun isLiteralPrivateOrLoopbackIp(rawHost: String): Boolean {
    val host = rawHost.removePrefix("[").removeSuffix("]")
    // IPv4 literal check (including single-integer / octal tricks via regex or dotted quad)
    val ipv4Parts = host.split('.')
    if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
      val a = ipv4Parts[0].toInt()
      val b = ipv4Parts[1].toInt()
      if (a == 0 || a == 10 || a == 127) return true
      if (a == 169 && b == 254) return true
      if (a == 172 && b in 16..31) return true
      if (a == 192 && b == 168) return true
      if (a == 100 && b in 64..127) return true // CGNAT 100.64.0.0/10
      if (a >= 224) return true // Multicast / reserved
      return false
    }
    // Single-integer IPv4 encoding (e.g., 2130706433 == 127.0.0.1) or hex IP
    if (host.matches(Regex("""^(?:0x[0-9a-f]+|\d+)$""", RegexOption.IGNORE_CASE))) {
      return true
    }
    // IPv6 literal checks (loopback ::1, unspecified ::, ULA fc00::/7, link-local fe80::/10, IPv4-mapped ::ffff:)
    if (host.contains(':')) {
      val lower = host.lowercase(Locale.US)
      if (lower == "::1" || lower == "::" || lower.startsWith("fc") || lower.startsWith("fd") ||
        lower.startsWith("fe8") || lower.startsWith("fe9") || lower.startsWith("fea") || lower.startsWith("feb") ||
        lower.startsWith("::ffff:")
      ) {
        return true
      }
    }
    return false
  }

  private fun isDisallowedInetAddress(addr: InetAddress): Boolean {
    return addr.isAnyLocalAddress ||
      addr.isLoopbackAddress ||
      addr.isLinkLocalAddress ||
      addr.isSiteLocalAddress ||
      addr.isMulticastAddress ||
      isLiteralPrivateOrLoopbackIp(addr.hostAddress.orEmpty())
  }

  suspend fun testProviderConnection(provider: AiApiProviderEntry): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (provider.protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS) {
          return@runCatching "Built-in Firebase AI Logic (App Check Attested) is active and ready. Zero API key needed."
        }
        validateEndpointUrl(provider.baseUrl, resolveDns = true)
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
    val validatedUrl = validateEndpointUrl(normalizedUrl, resolveDns = true)

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
      endpointUrl = validatedUrl,
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
      ?: throw IllegalStateException("No choices array returned from provider: ${rawResponse.take(240)}")
    if (choices.length() == 0) {
      throw IllegalStateException("Empty choices returned from provider.")
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

  internal fun buildGeminiDirectRestUrl(baseUrl: String, modelId: String): URL {
    val cleanBase = baseUrl.trim().trimEnd('/')
    val cleanModel = modelId.trim().removePrefix("models/").ifBlank { "gemini-3.8-flash" }
    require(cleanModel.matches(Regex("""^[a-zA-Z0-9._\-]+$"""))) {
      "Invalid Gemini model identifier: '$cleanModel'."
    }
    // Pass API key strictly via the x-goog-api-key request header — NEVER in the URL query string.
    return validateEndpointUrl("$cleanBase/models/$cleanModel:generateContent", resolveDns = false)
  }

  private fun callGeminiDirectRestEndpoint(
    baseUrl: String,
    modelId: String,
    apiKey: String,
    systemPrompt: String,
    userPrompt: String,
    expectJsonObject: Boolean
  ): String {
    val endpointUrl = buildGeminiDirectRestUrl(baseUrl, modelId)
    val validatedUrl = validateEndpointUrl(endpointUrl.toString(), resolveDns = true)

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
      endpointUrl = validatedUrl,
      headers = mapOf(
        "Content-Type" to "application/json",
        "x-goog-api-key" to apiKey
      ),
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
    endpointUrl: URL,
    headers: Map<String, String>,
    bodyJson: String
  ): String {
    val connection = (endpointUrl.openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      instanceFollowRedirects = false // Prevent HTTPS-to-HTTP or internal-host redirect downgrades
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
