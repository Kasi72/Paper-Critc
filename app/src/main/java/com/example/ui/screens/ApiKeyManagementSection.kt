package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import com.example.data.model.DefaultAiProviderCatalog
import com.example.ui.components.IosGroupedCard
import com.example.ui.components.IosInsetDivider
import com.example.ui.components.IosSecondarySquircleButton
import com.example.ui.components.IosSegmentedControl
import com.example.ui.components.IosSettingToggleRow
import com.example.ui.components.IosSquircleButton
import com.example.ui.components.IosSquircleIconBadge
import com.example.ui.components.SquircleShape
import com.example.ui.theme.CupertinoAmber
import com.example.ui.theme.CupertinoBlue
import com.example.ui.theme.CupertinoGreen
import com.example.ui.theme.CupertinoIndigo
import com.example.ui.theme.CupertinoRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.AppSettingsState

@Composable
fun ApiKeysAndProvidersSettingsContent(
  settingsState: AppSettingsState,
  apiProviders: List<AiApiProviderEntry>,
  isTestingApiKey: Boolean,
  apiTestResultMessage: String?,
  onUpdateSettings: ((AppSettingsState) -> AppSettingsState) -> Unit,
  onSelectActiveProvider: (String) -> Unit,
  onSaveProvider: (AiApiProviderEntry) -> Unit,
  onAddCustomProvider: (
    serviceName: String,
    protocolType: ApiProtocolType,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    notes: String
  ) -> Unit,
  onDeleteCustomProvider: (String) -> Unit,
  onTestProviderConnection: (AiApiProviderEntry) -> Unit
) {
  val activeProvider = apiProviders.find { it.id == settingsState.activeProviderId }
    ?: apiProviders.firstOrNull()
    ?: DefaultAiProviderCatalog.presets.first()

  var showAddCustomCard by rememberSaveable { mutableStateOf(false) }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    // 1. Master AI Provider Routing Card (iOS Cupertino Switches)
    IosGroupedCard(
      header = "AI Analysis Routing & Free API Key Controls",
      footer = "By default, Paper Critic uses Built-in Keyless Firebase AI Logic (zero API key required). Toggle custom routing below whenever you want to run analyses using your own Free API services or personal API keys."
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        IosSquircleIconBadge(
          icon = Icons.Default.Key,
          tint = if (settingsState.useCustomApiProvider) CupertinoGreen else CupertinoBlue,
          size = 36.dp,
          cornerRadius = 8.dp
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Active Provider: ${activeProvider.serviceName}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "${activeProvider.protocolType.shortTag} • Model: ${activeProvider.modelId} • ${activeProvider.maskedKeyPreview}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            color = if (activeProvider.hasValidKeyOrKeyless) CupertinoGreen else CupertinoAmber
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))
      IosInsetDivider()

      IosSettingToggleRow(
        icon = Icons.Default.Key,
        iconTint = CupertinoBlue,
        title = "Use Custom / Free API Key Service",
        subtitle = "Route 30-stage reviews & chat through your selected API key provider below",
        checked = settingsState.useCustomApiProvider,
        onCheckedChange = { enabled ->
          onUpdateSettings { current ->
            val nextId = if (enabled && current.activeProviderId == DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID) {
              apiProviders.firstOrNull {
                it.id != DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID && it.apiKey.isNotBlank()
              }?.id ?: "preset_openrouter_free"
            } else if (!enabled) {
              DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID
            } else {
              current.activeProviderId
            }
            current.copy(
              useCustomApiProvider = enabled,
              activeProviderId = nextId
            )
          }
        },
        modifier = Modifier.testTag("toggle_use_custom_api_provider")
      )

      IosInsetDivider()

      IosSettingToggleRow(
        icon = Icons.Default.Security,
        iconTint = CupertinoGreen,
        title = "Auto-Fallback to Built-in Keyless Engine",
        subtitle = "Automatically fall back to built-in Firebase AI Logic if a free API key hits rate limits",
        checked = settingsState.autoFallbackToKeylessEngine,
        onCheckedChange = { enabled ->
          onUpdateSettings { it.copy(autoFallbackToKeylessEngine = enabled) }
        },
        modifier = Modifier.testTag("toggle_auto_fallback_keyless")
      )
    }

    // 2. Service Selector & API Key Editor Card
    IosGroupedCard(
      header = "Configure Free API Services & API Keys (${apiProviders.size} Services)"
    ) {
      Text(
        text = "TAP A SERVICE TO EDIT ITS API KEY, MODEL ID, OR ENDPOINT",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Horizontal squircle pills for switching between services
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        apiProviders.forEach { provider ->
          val isSelected = provider.id == activeProvider.id
          val pillShape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
          val hasKey = provider.hasValidKeyOrKeyless
          Box(
            modifier = Modifier
              .clip(pillShape)
              .background(
                if (isSelected) CupertinoBlue
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
              )
              .border(
                width = 0.75.dp,
                color = if (isSelected) CupertinoBlue
                else if (hasKey) CupertinoGreen.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                shape = pillShape
              )
              .clickable { onSelectActiveProvider(provider.id) }
              .padding(horizontal = 11.dp, vertical = 8.dp)
              .testTag("api_provider_chip_${provider.id}")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
              Text(
                text = provider.serviceName.substringBefore(" ("),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
              )
              if (hasKey) {
                Text(
                  text = "●",
                  fontSize = 10.sp,
                  color = if (isSelected) Color.White else CupertinoGreen
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
      IosInsetDivider()
      Spacer(modifier = Modifier.height(10.dp))

      ProviderDetailEditor(
        provider = activeProvider,
        isTestingApiKey = isTestingApiKey,
        apiTestResultMessage = apiTestResultMessage,
        onSaveProvider = onSaveProvider,
        onTestConnection = onTestProviderConnection,
        onDeleteCustom = if (!activeProvider.isFreeTierPreset &&
          activeProvider.id != DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID
        ) {
          { onDeleteCustomProvider(activeProvider.id) }
        } else null
      )
    }

    // 3. Add Unlimited Custom Free / OpenAI-Compatible API Services Card
    IosGroupedCard(
      header = "Add Custom Free / OpenAI-Compatible API Service"
    ) {
      if (!showAddCustomCard) {
        Text(
          text = "Add any custom OpenAI-compatible or Gemini REST endpoint (e.g., Cerebras Free, Together AI, Mistral Free Tier, DeepSeek API, or self-hosted vLLM/Ollama tunnel).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        IosSecondarySquircleButton(
          text = "+ Add New Free / Custom API Service & Key",
          onClick = { showAddCustomCard = true },
          icon = Icons.Default.AddCircleOutline,
          tintColor = CupertinoBlue,
          cornerRadius = 9.dp,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("open_add_custom_api_button")
        )
      } else {
        AddCustomApiServiceForm(
          onCancel = { showAddCustomCard = false },
          onAdd = { name, proto, url, model, key, notes ->
            onAddCustomProvider(name, proto, url, model, key, notes)
            showAddCustomCard = false
          }
        )
      }
    }
  }
}

@Composable
private fun ProviderDetailEditor(
  provider: AiApiProviderEntry,
  isTestingApiKey: Boolean,
  apiTestResultMessage: String?,
  onSaveProvider: (AiApiProviderEntry) -> Unit,
  onTestConnection: (AiApiProviderEntry) -> Unit,
  onDeleteCustom: (() -> Unit)?
) {
  var editedKey by remember(provider.id, provider.apiKey) { mutableStateOf(provider.apiKey) }
  var editedModelId by remember(provider.id, provider.modelId) { mutableStateOf(provider.modelId) }
  var editedBaseUrl by remember(provider.id, provider.baseUrl) { mutableStateOf(provider.baseUrl) }
  var showRawKey by rememberSaveable { mutableStateOf(false) }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = provider.serviceName,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = provider.notes,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      if (onDeleteCustom != null) {
        IconButton(onClick = onDeleteCustom) {
          Icon(
            imageVector = Icons.Default.DeleteOutline,
            contentDescription = "Delete custom provider",
            tint = CupertinoRed
          )
        }
      }
    }

    if (provider.protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS) {
      val badgeShape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(badgeShape)
          .background(CupertinoGreen.copy(alpha = 0.12f))
          .border(0.6.dp, CupertinoGreen.copy(alpha = 0.35f), badgeShape)
          .padding(12.dp)
      ) {
        Text(
          text = "✓ Built-in Keyless Engine: Uses Firebase AI Logic (gemini-3.8-flash / gemini-4-argon / gemini-3.5-flash-lite) with automatic App Check attestation. No API key entry is needed for this mode.",
          style = MaterialTheme.typography.bodySmall,
          color = CupertinoGreen,
          fontWeight = FontWeight.SemiBold
        )
      }
    } else {
      // API Key Input Field with Show/Hide toggle
      OutlinedTextField(
        value = editedKey,
        onValueChange = { editedKey = it },
        label = { Text("API Key (${provider.serviceName})") },
        placeholder = { Text("Paste your API key (sk-..., gsk_..., AIza..., hf_...)") },
        visualTransformation = if (showRawKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
          IconButton(onClick = { showRawKey = !showRawKey }) {
            Icon(
              imageVector = if (showRawKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = if (showRawKey) "Hide API Key" else "Show API Key",
              tint = CupertinoBlue
            )
          }
        },
        singleLine = true,
        shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CupertinoBlue),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("api_key_input_field")
      )

      // Quick Free Model Presets based on service
      val suggestedModels = when (provider.id) {
        "preset_openrouter_free" -> listOf(
          "deepseek/deepseek-r1:free",
          "meta-llama/llama-3.3-70b-instruct:free",
          "qwen/qwen-2.5-72b-instruct:free",
          "google/gemma-3-27b-it:free"
        )
        "preset_groq_free" -> listOf(
          "llama-3.3-70b-versatile",
          "deepseek-r1-distill-llama-70b",
          "llama-3.1-8b-instant"
        )
        "preset_gemini_direct_key" -> listOf(
          "gemini-3.8-flash",
          "gemini-3.5-flash-lite",
          "gemini-4-argon"
        )
        "preset_huggingface_free" -> listOf(
          "Qwen/Qwen2.5-72B-Instruct",
          "meta-llama/Llama-3.3-70B-Instruct"
        )
        else -> emptyList()
      }

      if (suggestedModels.isNotEmpty()) {
        Text(
          text = "QUICK-SELECT FREE MODEL ID",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          fontSize = 9.5.sp,
          fontWeight = FontWeight.Bold,
          color = CupertinoIndigo
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          suggestedModels.forEach { modelSuggestion ->
            val chipShape = SquircleShape(cornerRadius = 7.dp, smoothing = 0.88f)
            val isCurrent = editedModelId.trim() == modelSuggestion
            Box(
              modifier = Modifier
                .clip(chipShape)
                .background(
                  if (isCurrent) CupertinoIndigo
                  else CupertinoIndigo.copy(alpha = 0.10f)
                )
                .border(0.6.dp, CupertinoIndigo.copy(alpha = 0.35f), chipShape)
                .clickable { editedModelId = modelSuggestion }
                .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
              Text(
                text = modelSuggestion,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = if (isCurrent) Color.White else CupertinoIndigo
              )
            }
          }
        }
      }

      OutlinedTextField(
        value = editedModelId,
        onValueChange = { editedModelId = it },
        label = { Text("Model ID") },
        singleLine = true,
        shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CupertinoBlue),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("api_model_id_input_field")
      )

      OutlinedTextField(
        value = editedBaseUrl,
        onValueChange = { editedBaseUrl = it },
        label = { Text("API Endpoint Base URL") },
        singleLine = true,
        shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CupertinoBlue),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("api_base_url_input_field")
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IosSecondarySquircleButton(
          text = if (isTestingApiKey) "Testing..." else "Test API Key",
          onClick = {
            onTestConnection(
              provider.copy(
                apiKey = editedKey.trim(),
                modelId = editedModelId.trim(),
                baseUrl = editedBaseUrl.trim()
              )
            )
          },
          icon = Icons.Default.NetworkCheck,
          tintColor = CupertinoIndigo,
          cornerRadius = 9.dp,
          modifier = Modifier
            .weight(1f)
            .testTag("test_api_key_button")
        )

        IosSquircleButton(
          text = "Save & Activate Key",
          onClick = {
            onSaveProvider(
              provider.copy(
                apiKey = editedKey.trim(),
                modelId = editedModelId.trim(),
                baseUrl = editedBaseUrl.trim(),
                enabled = editedKey.isNotBlank()
              )
            )
          },
          icon = Icons.Default.CheckCircle,
          containerColor = CupertinoBlue,
          cornerRadius = 9.dp,
          modifier = Modifier
            .weight(1f)
            .testTag("save_api_key_button")
        )
      }
    }

    if (!apiTestResultMessage.isNullOrBlank()) {
      val isSuccess = apiTestResultMessage.startsWith("✅")
      val statusColor = if (isSuccess) CupertinoGreen else if (apiTestResultMessage.startsWith("❌")) CupertinoRed else CupertinoBlue
      val boxShape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(boxShape)
          .background(statusColor.copy(alpha = 0.10f))
          .border(0.6.dp, statusColor.copy(alpha = 0.35f), boxShape)
          .padding(10.dp)
      ) {
        Text(
          text = apiTestResultMessage,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          color = statusColor
        )
      }
    }
  }
}

@Composable
private fun AddCustomApiServiceForm(
  onCancel: () -> Unit,
  onAdd: (
    serviceName: String,
    protocolType: ApiProtocolType,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    notes: String
  ) -> Unit
) {
  var serviceName by rememberSaveable { mutableStateOf("") }
  var selectedProtocolIdx by rememberSaveable { mutableIntStateOf(0) } // 0 = OpenAI REST, 1 = Gemini REST
  var baseUrl by rememberSaveable { mutableStateOf("https://openrouter.ai/api/v1/chat/completions") }
  var modelId by rememberSaveable { mutableStateOf("deepseek/deepseek-r1:free") }
  var apiKey by rememberSaveable { mutableStateOf("") }
  var notes by rememberSaveable { mutableStateOf("Custom free/BYOK API endpoint") }

  val protocolOptions = listOf(
    ApiProtocolType.OPENAI_COMPATIBLE_REST,
    ApiProtocolType.GEMINI_DIRECT_REST
  )

  Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
    Text(
      text = "NEW CUSTOM / FREE API SERVICE",
      style = MaterialTheme.typography.labelSmall,
      fontFamily = JetBrainsMonoFamily,
      fontWeight = FontWeight.Bold,
      color = CupertinoBlue
    )

    IosSegmentedControl(
      items = listOf("OpenAI-Compatible REST", "Gemini Direct REST"),
      selectedIndex = selectedProtocolIdx,
      onSelect = { idx ->
        selectedProtocolIdx = idx
        if (idx == 1 && baseUrl.contains("openrouter")) {
          baseUrl = "https://generativelanguage.googleapis.com/v1beta"
          modelId = "gemini-3.8-flash"
        } else if (idx == 0 && baseUrl.contains("googleapis")) {
          baseUrl = "https://openrouter.ai/api/v1/chat/completions"
          modelId = "deepseek/deepseek-r1:free"
        }
      }
    )

    OutlinedTextField(
      value = serviceName,
      onValueChange = { serviceName = it },
      label = { Text("Service Name (e.g. Cerebras Free / Together AI)") },
      singleLine = true,
      shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("new_custom_api_name_input")
    )

    OutlinedTextField(
      value = baseUrl,
      onValueChange = { baseUrl = it },
      label = { Text("Endpoint URL") },
      singleLine = true,
      shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
      modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
      value = modelId,
      onValueChange = { modelId = it },
      label = { Text("Model ID") },
      singleLine = true,
      shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
      modifier = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
      value = apiKey,
      onValueChange = { apiKey = it },
      label = { Text("API Key") },
      singleLine = true,
      shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("new_custom_api_key_input")
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IosSecondarySquircleButton(
        text = "Cancel",
        onClick = onCancel,
        tintColor = CupertinoRed,
        cornerRadius = 9.dp,
        modifier = Modifier.weight(1f)
      )
      IosSquircleButton(
        text = "Add API Service",
        onClick = {
          if (serviceName.isNotBlank()) {
            onAdd(
              serviceName,
              protocolOptions[selectedProtocolIdx],
              baseUrl,
              modelId,
              apiKey,
              notes
            )
          }
        },
        enabled = serviceName.isNotBlank(),
        icon = Icons.Default.AddCircleOutline,
        containerColor = CupertinoBlue,
        cornerRadius = 9.dp,
        modifier = Modifier
          .weight(1f)
          .testTag("confirm_add_custom_api_button")
      )
    }
  }
}
