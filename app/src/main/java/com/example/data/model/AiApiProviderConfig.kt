package com.example.data.model

enum class ApiProtocolType(val label: String, val shortTag: String) {
  FIREBASE_AI_LOGIC_KEYLESS("Firebase AI Logic (Built-in Keyless)", "KEYLESS"),
  OPENAI_COMPATIBLE_REST("OpenAI-Compatible REST (/v1/chat/completions)", "OPENAI-REST"),
  GEMINI_DIRECT_REST("Google Gemini Direct REST (v1beta)", "GEMINI-REST")
}

data class AiApiProviderEntry(
  val id: String,
  val serviceName: String,
  val protocolType: ApiProtocolType,
  val baseUrl: String,
  val modelId: String,
  val apiKey: String = "",
  val isFreeTierPreset: Boolean = true,
  val enabled: Boolean = false,
  val notes: String = ""
) {
  val hasValidKeyOrKeyless: Boolean
    get() = protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS || apiKey.trim().isNotEmpty()

  val maskedKeyPreview: String
    get() {
      if (protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS) {
        return "No API Key Required (App Check Attested)"
      }
      val trimmed = apiKey.trim()
      if (trimmed.isEmpty()) return "No API key set"
      return if (trimmed.length <= 8) {
        "••••••••"
      } else {
        "${trimmed.take(4)}••••••••${trimmed.takeLast(4)}"
      }
    }
}

object DefaultAiProviderCatalog {
  const val BUILTIN_KEYLESS_ID = "builtin_firebase_ai_logic"

  val presets: List<AiApiProviderEntry> = listOf(
    AiApiProviderEntry(
      id = BUILTIN_KEYLESS_ID,
      serviceName = "Built-in Firebase AI Logic (Zero-Key Default)",
      protocolType = ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS,
      baseUrl = "firebase://ai-logic/app-check",
      modelId = "gemini-3.8-flash",
      apiKey = "",
      isFreeTierPreset = true,
      enabled = true,
      notes = "Works automatically out-of-the-box with zero API key needed."
    ),
    AiApiProviderEntry(
      id = "preset_openrouter_free",
      serviceName = "OpenRouter (Free Academic Models)",
      protocolType = ApiProtocolType.OPENAI_COMPATIBLE_REST,
      baseUrl = "https://openrouter.ai/api/v1/chat/completions",
      modelId = "deepseek/deepseek-r1:free",
      apiKey = "",
      isFreeTierPreset = true,
      enabled = false,
      notes = "Supports free-tier models (e.g. deepseek/deepseek-r1:free, meta-llama/llama-3.3-70b-instruct:free)."
    ),
    AiApiProviderEntry(
      id = "preset_groq_free",
      serviceName = "Groq Cloud (Free Fast Inference API)",
      protocolType = ApiProtocolType.OPENAI_COMPATIBLE_REST,
      baseUrl = "https://api.groq.com/openai/v1/chat/completions",
      modelId = "llama-3.3-70b-versatile",
      apiKey = "",
      isFreeTierPreset = true,
      enabled = false,
      notes = "Ultra-low-latency free API tier for Llama 3.3 70B & DeepSeek R1 Distill."
    ),
    AiApiProviderEntry(
      id = "preset_gemini_direct_key",
      serviceName = "Google AI Studio (Direct Gemini API Key)",
      protocolType = ApiProtocolType.GEMINI_DIRECT_REST,
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      modelId = "gemini-3.8-flash",
      apiKey = "",
      isFreeTierPreset = true,
      enabled = false,
      notes = "Use your own personal Google AI Studio free-tier API key via direct REST."
    ),
    AiApiProviderEntry(
      id = "preset_huggingface_free",
      serviceName = "Hugging Face Router (Free Inference API)",
      protocolType = ApiProtocolType.OPENAI_COMPATIBLE_REST,
      baseUrl = "https://router.huggingface.co/v1/chat/completions",
      modelId = "Qwen/Qwen2.5-72B-Instruct",
      apiKey = "",
      isFreeTierPreset = true,
      enabled = false,
      notes = "Use a free Hugging Face access token (hf_...) with OpenAI-compatible chat completions."
    )
  )
}
