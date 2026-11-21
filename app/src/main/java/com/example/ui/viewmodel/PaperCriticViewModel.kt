package com.example.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.ai.ChatTurnInput
import com.example.data.ai.ChatbotRolePersona
import com.example.data.ai.ExternalApiGatewayClient
import com.example.data.ai.ForensicPreFlightReport
import com.example.data.ai.GeminiModelTier
import com.example.data.ai.GroundedWebSource
import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.SearchGroundingResult
import com.example.data.local.PaperCriticSqlDatabase
import com.example.data.local.SqlChatMessageEntity
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import com.example.data.model.BenchmarkManuscript
import com.example.data.model.BenchmarkManuscriptCatalog
import com.example.data.model.DefaultAiProviderCatalog
import com.example.data.model.PaperReview
import com.example.data.model.PipelineOptions
import com.example.data.repository.LocalReviewRepository
import com.example.data.repository.ReviewRepository
import com.example.util.ManuscriptExtractor
import com.example.util.ManuscriptInputFormat
import com.example.util.NetworkHealthChecker
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PaperCriticVM"
private const val STOP_TIMEOUT_MILLIS = 5000L

enum class ThemeModePreference(val label: String) {
  SYSTEM("System"),
  IOS_LIGHT("Light"),
  IOS_DARK("Dark")
}

data class AppSettingsState(
  val themeMode: ThemeModePreference = ThemeModePreference.IOS_LIGHT,
  val compactDossierMode: Boolean = false,
  val monospaceEquations: Boolean = true,
  val reviewStrictness: String = "AGGRESSIVE_REVIEWER2",
  val autoSaveToCloud: Boolean = true,
  val enablePdfVisionPages: Boolean = true,
  val enableLegalScreen: Boolean = true,
  val enableGrimStatsCheck: Boolean = true,
  val useCustomApiProvider: Boolean = false,
  val autoFallbackToKeylessEngine: Boolean = true,
  val activeProviderId: String = DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID,
  val defaultVenue: String = "Top-Tier Peer-Reviewed Journal / Conference",
  val defaultOptions: PipelineOptions = PipelineOptions(),
  val reportTextScale: Float = 1.0f
)

sealed interface ArchiveUiState {
  data object Loading : ArchiveUiState
  data class Success(val reviews: List<PaperReview>) : ArchiveUiState
  data class Error(val message: String) : ArchiveUiState
}

sealed interface PipelineExecutionState {
  data object Idle : PipelineExecutionState
  data class Running(
    val stageCode: String,
    val stageLabel: String,
    val progress: Float
  ) : PipelineExecutionState
  data class Completed(val review: PaperReview, val savedToCloud: Boolean) : PipelineExecutionState
  data class Error(val message: String) : PipelineExecutionState
}

sealed interface FollowUpQaState {
  data object Idle : FollowUpQaState
  data object Loading : FollowUpQaState
  data class Streaming(val question: String, val partialAnswer: String) : FollowUpQaState
  data class Success(val question: String, val answer: String) : FollowUpQaState
  data class Error(val message: String) : FollowUpQaState
}

sealed interface SearchGroundingUiState {
  data object Idle : SearchGroundingUiState
  data object Loading : SearchGroundingUiState
  data class Success(val result: SearchGroundingResult) : SearchGroundingUiState
  data class Error(val message: String) : SearchGroundingUiState
}

data class RefereeChatMessageUi(
  val id: Long = 0L,
  val role: String, // "user" or "model"
  val persona: ChatbotRolePersona = ChatbotRolePersona.BLUE_TEAM_COACH,
  val modelTier: GeminiModelTier = GeminiModelTier.GENERAL_FLASH,
  val text: String,
  val searchQueries: List<String> = emptyList(),
  val sources: List<GroundedWebSource> = emptyList()
)

data class RefereeChatUiState(
  val messages: List<RefereeChatMessageUi> = emptyList(),
  val selectedPersona: ChatbotRolePersona = ChatbotRolePersona.BLUE_TEAM_COACH,
  val selectedModelTier: GeminiModelTier = GeminiModelTier.GENERAL_FLASH,
  val enableSearchGrounding: Boolean = true,
  val isStreaming: Boolean = false,
  val streamingPartialText: String = "",
  val streamingQueries: List<String> = emptyList(),
  val streamingSources: List<GroundedWebSource> = emptyList(),
  val errorMessage: String? = null
)

data class WorkbenchFormState(
  val inputModeIndex: Int = 0, // 0 = PDF/Word/Text File, 1 = Paste/Edit Text, 2 = Sample Papers
  val title: String = BenchmarkManuscriptCatalog.samples.first().title,
  val authors: String = BenchmarkManuscriptCatalog.samples.first().authors,
  val year: String = BenchmarkManuscriptCatalog.samples.first().year,
  val venueOrCitation: String = BenchmarkManuscriptCatalog.samples.first().venue,
  val manuscriptText: String = BenchmarkManuscriptCatalog.samples.first().manuscriptText,
  val importedFileName: String? = "yell_at_it_reviewer2.pdf (Benchmark Sample)",
  val importedFormat: ManuscriptInputFormat = ManuscriptInputFormat.PDF,
  val importedWordCount: Int = 240,
  val importedPageCount: Int = 1,
  val pdfPageBitmaps: List<Bitmap> = emptyList(),
  val importedFileBanner: String? = "Ready: Benchmark #1 — Yell at It (reviewer2)",
  val replicationCodeFileName: String? = null,
  val replicationCodeText: String = "",
  val options: PipelineOptions = PipelineOptions()
)

class PaperCriticViewModel(
  private val repository: ReviewRepository,
  private val localRepository: LocalReviewRepository,
  private val pipelineService: Reviewer2PipelineService,
  private val prefs: SharedPreferences?,
  initialUserId: String?,
  private val isNetworkAvailable: () -> Boolean = { true }
) : ViewModel() {

  private val _authenticatedUserId = MutableStateFlow(initialUserId)
  val authenticatedUserId: StateFlow<String?> = _authenticatedUserId.asStateFlow()

  private val _localReviews = MutableStateFlow<List<PaperReview>>(emptyList())

  private val sqlReviewsFlow: StateFlow<List<PaperReview>> = localRepository
    .cachedReviews
    .catch { emit(emptyList()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val offlineCachedIds: StateFlow<Set<String>> = sqlReviewsFlow
    .map { list -> list.map { it.id }.filter { it.isNotBlank() }.toSet() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptySet())

  val sqlCachedReviewCount: StateFlow<Int> = localRepository
    .cachedReviewCount
    .catch { emit(0) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

  val sqlTotalChatMessageCount: StateFlow<Int> = localRepository
    .totalChatMessageCount
    .catch { emit(0) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

  val apiProviders: StateFlow<List<AiApiProviderEntry>> = localRepository
    .apiProviders
    .map { sqlList ->
      val sqlById = sqlList.associate { it.id to it.toDomain() }
      val mergedPresets = DefaultAiProviderCatalog.presets.map { preset ->
        sqlById[preset.id] ?: preset
      }
      val presetIds = DefaultAiProviderCatalog.presets.map { it.id }.toSet()
      val customEntries = sqlList
        .filter { it.id !in presetIds }
        .map { it.toDomain() }
      mergedPresets + customEntries
    }
    .catch { emit(DefaultAiProviderCatalog.presets) }
    .stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      DefaultAiProviderCatalog.presets
    )

  private val _isTestingApiKey = MutableStateFlow(false)
  val isTestingApiKey: StateFlow<Boolean> = _isTestingApiKey.asStateFlow()

  private val _apiTestResultMessage = MutableStateFlow<String?>(null)
  val apiTestResultMessage: StateFlow<String?> = _apiTestResultMessage.asStateFlow()

  val unsyncedLocalCount: StateFlow<Int> = combine(
    _localReviews,
    sqlReviewsFlow
  ) { memList, sqlList ->
    val combined = (memList + sqlList).distinctBy { it.id.ifBlank { it.title } }
    combined.count { it.id.startsWith("local_") || it.id.startsWith("sql_") || it.userId.isBlank() }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

  private val _isSyncingPortfolio = MutableStateFlow(false)
  val isSyncingPortfolio: StateFlow<Boolean> = _isSyncingPortfolio.asStateFlow()

  @OptIn(ExperimentalCoroutinesApi::class)
  private val cloudReviewsFlow: StateFlow<ArchiveUiState> = _authenticatedUserId
    .flatMapLatest { uid ->
      if (!uid.isNullOrBlank()) {
        repository.observeReviews(uid)
          .onEach { cloudList ->
            runCatching {
              localRepository.cacheReviewsOffline(cloudList)
            }
          }
          .map<List<PaperReview>, ArchiveUiState> { ArchiveUiState.Success(it) }
          .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing user reviews", rootError)
            emit(ArchiveUiState.Error(rootError.message ?: "Failed to load cloud dossiers."))
          }
      } else {
        flowOf(ArchiveUiState.Success(emptyList()))
      }
    }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = ArchiveUiState.Success(emptyList())
    )

  val archiveState: StateFlow<ArchiveUiState> = combine(
    cloudReviewsFlow,
    _localReviews,
    sqlReviewsFlow
  ) { cloudState, localList, sqlList ->
    val mergedLocalAndSql = (localList + sqlList).distinctBy { it.id.ifBlank { it.title } }
    when (cloudState) {
      is ArchiveUiState.Loading -> {
        if (mergedLocalAndSql.isNotEmpty()) ArchiveUiState.Success(mergedLocalAndSql) else ArchiveUiState.Loading
      }
      is ArchiveUiState.Error -> {
        if (mergedLocalAndSql.isNotEmpty()) ArchiveUiState.Success(mergedLocalAndSql) else cloudState
      }
      is ArchiveUiState.Success -> {
        val cloudIds = cloudState.reviews.map { it.id }.toSet()
        val unsyncedLocal = mergedLocalAndSql.filter { it.id !in cloudIds }
        ArchiveUiState.Success((unsyncedLocal + cloudState.reviews).distinctBy { it.id.ifBlank { it.title } })
      }
    }
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
    initialValue = ArchiveUiState.Success(emptyList())
  )

  // Main iOS Tab Index: 0 = Upload (Input), 1 = Output (Analysis Results), 2 = Review History (Dossiers), 3 = Settings
  private val _activeMainTab = MutableStateFlow(0)
  val activeMainTab: StateFlow<Int> = _activeMainTab.asStateFlow()

  private val initialSettings = loadSettingsFromPrefs(prefs)
  private val _settingsState = MutableStateFlow(initialSettings)
  val settingsState: StateFlow<AppSettingsState> = _settingsState.asStateFlow()

  private val _formState = MutableStateFlow(
    WorkbenchFormState(
      venueOrCitation = BenchmarkManuscriptCatalog.samples.first().venue,
      options = initialSettings.defaultOptions
    )
  )
  val formState: StateFlow<WorkbenchFormState> = _formState.asStateFlow()

  // Off-main-thread debounced deterministic pre-flight forensic & quantitative audit
  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  val preFlightReport: StateFlow<ForensicPreFlightReport> = _formState
    .map { Triple(it.manuscriptText, it.options.isEmpirical, it.replicationCodeText) }
    .distinctUntilChanged()
    .debounce(260L)
    .mapLatest { (text, isEmpirical, code) ->
      withContext(Dispatchers.Default) {
        ManuscriptForensicEngine.analyzeManuscript(
          text = text,
          isEmpiricalTrack = isEmpirical,
          replicationCode = code
        )
      }
    }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = ManuscriptForensicEngine.analyzeManuscript(
        text = BenchmarkManuscriptCatalog.samples.first().manuscriptText,
        isEmpiricalTrack = initialSettings.defaultOptions.isEmpirical,
        replicationCode = ""
      )
    )

  private var activePipelineJob: Job? = null

  private val _pipelineState = MutableStateFlow<PipelineExecutionState>(PipelineExecutionState.Idle)
  val pipelineState: StateFlow<PipelineExecutionState> = _pipelineState.asStateFlow()

  private val _selectedDossier = MutableStateFlow<PaperReview?>(null)
  val selectedDossier: StateFlow<PaperReview?> = _selectedDossier.asStateFlow()

  private val _followUpState = MutableStateFlow<FollowUpQaState>(FollowUpQaState.Idle)
  val followUpState: StateFlow<FollowUpQaState> = _followUpState.asStateFlow()

  private val _searchGroundingState = MutableStateFlow<SearchGroundingUiState>(SearchGroundingUiState.Idle)
  val searchGroundingState: StateFlow<SearchGroundingUiState> = _searchGroundingState.asStateFlow()

  // Multi-Turn Gemini Referee Chatbot State
  private val _chatConfigState = MutableStateFlow(RefereeChatUiState())

  @OptIn(ExperimentalCoroutinesApi::class)
  private val activeDossierChatEntities: StateFlow<List<SqlChatMessageEntity>> = _selectedDossier
    .map { it?.id?.ifBlank { "global_workbench_chat" } ?: "global_workbench_chat" }
    .flatMapLatest { reviewKey ->
      localRepository.observeChatMessages(reviewKey).catch { emit(emptyList()) }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val refereeChatState: StateFlow<RefereeChatUiState> = combine(
    _chatConfigState,
    activeDossierChatEntities
  ) { config, sqlMessages ->
    val mappedMessages = sqlMessages.map { entity ->
      val persona = ChatbotRolePersona.entries.find { it.id == entity.personaId }
        ?: ChatbotRolePersona.BLUE_TEAM_COACH
      val tier = GeminiModelTier.entries.find { it.modelId == entity.modelTierId }
        ?: GeminiModelTier.GENERAL_FLASH
      val parsedQueries = entity.searchGroundedQueries
        .split("||")
        .map { it.trim() }
        .filter { it.isNotBlank() }
      val parsedSources = entity.searchGroundedSources
        .lines()
        .mapNotNull { line ->
          val parts = line.split("|~|")
          if (parts.size == 2 && parts[1].isNotBlank()) {
            GroundedWebSource(title = parts[0], url = parts[1])
          } else null
        }
      RefereeChatMessageUi(
        id = entity.messageId,
        role = entity.role,
        persona = persona,
        modelTier = tier,
        text = entity.content,
        searchQueries = parsedQueries,
        sources = parsedSources
      )
    }
    config.copy(messages = mappedMessages)
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
    initialValue = RefereeChatUiState()
  )

  private val _statusBanner = MutableStateFlow<String?>(null)
  val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

  fun onAuthenticatedUserChanged(newUserId: String?) {
    val previous = _authenticatedUserId.value
    _authenticatedUserId.value = newUserId
    if (!newUserId.isNullOrBlank() && previous != newUserId) {
      syncPortfolioToCloud()
    }
  }

  fun syncPortfolioToCloud() {
    val uid = _authenticatedUserId.value
    if (uid.isNullOrBlank()) {
      _statusBanner.value = "Sign in with Google to sync your paper review portfolio across devices."
      return
    }
    if (_isSyncingPortfolio.value) return

    viewModelScope.launch {
      _isSyncingPortfolio.value = true
      try {
        val candidatePool = (_localReviews.value + sqlReviewsFlow.value)
          .distinctBy { it.id.ifBlank { it.title } }
        val pendingLocal = candidatePool.filter {
          it.id.startsWith("local_") || it.id.startsWith("sql_") || it.userId.isBlank()
        }
        var uploadedCount = 0
        for (localItem in pendingLocal) {
          val saveResult = repository.createReview(localItem.copy(userId = uid))
          if (saveResult.isSuccess) {
            val newCloudId = saveResult.getOrThrow()
            uploadedCount++
            val syncedItem = repository.getReviewById(newCloudId, uid).getOrNull()
              ?: localItem.copy(id = newCloudId, userId = uid)
            runCatching {
              localRepository.migrateChatHistory(localItem.id, newCloudId)
              localRepository.removeCachedReview(localItem.id)
              localRepository.cacheReviewOffline(syncedItem)
            }
            _localReviews.update { currentList ->
              currentList.filterNot { it.id == localItem.id }
            }
            if (_selectedDossier.value?.id == localItem.id) {
              _selectedDossier.value = syncedItem
            }
          }
        }
        val refreshResult = repository.getUserReviews()
        val cloudList = refreshResult.getOrNull().orEmpty()
        if (cloudList.isNotEmpty()) {
          runCatching {
            localRepository.cacheReviewsOffline(cloudList)
          }
        }
        val totalSynced = cloudList.size
        _statusBanner.value = if (uploadedCount > 0) {
          "Synced $uploadedCount local SQL review(s) to Google Cloud ($totalSynced total in portfolio)."
        } else {
          "Portfolio synced across SQL cache & Google Cloud ($totalSynced dossier(s) up to date)."
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Portfolio sync encountered an error", e)
        _statusBanner.value = "Sync error: ${e.localizedMessage ?: "Could not reach Firestore"}"
      } finally {
        _isSyncingPortfolio.value = false
      }
    }
  }

  fun selectMainTab(index: Int) {
    _activeMainTab.value = index.coerceIn(0, 3)
  }

  fun updateInputModeIndex(index: Int) {
    _formState.update { it.copy(inputModeIndex = index.coerceIn(0, 2)) }
  }

  fun updateTitle(value: String) {
    _formState.update { it.copy(title = value) }
  }

  fun updateAuthors(value: String) {
    _formState.update { it.copy(authors = value) }
  }

  fun updateYear(value: String) {
    _formState.update { it.copy(year = value) }
  }

  fun updateVenue(value: String) {
    _formState.update { it.copy(venueOrCitation = value) }
  }

  fun updateManuscriptText(value: String) {
    val words = if (value.isBlank()) 0 else value.trim().split(Regex("""\s+""")).size
    _formState.update {
      it.copy(
        manuscriptText = value,
        importedWordCount = words,
        importedPageCount = (words / 350).coerceAtLeast(1)
      )
    }
  }

  fun updateReplicationCodeText(value: String) {
    _formState.update { it.copy(replicationCodeText = value) }
  }

  fun updatePipelineOptions(transform: (PipelineOptions) -> PipelineOptions) {
    _formState.update { it.copy(options = transform(it.options)) }
  }

  fun updateSettings(transform: (AppSettingsState) -> AppSettingsState) {
    val previous = _settingsState.value
    val updated = transform(previous)
    _settingsState.value = updated
    saveSettingsToPrefs(prefs, updated)
    if (updated.defaultOptions != previous.defaultOptions) {
      _formState.update { form ->
        form.copy(options = updated.defaultOptions)
      }
    }
  }

  fun loadBenchmarkManuscript(sample: BenchmarkManuscript) {
    val words = sample.manuscriptText.trim().split(Regex("""\s+""")).size
    _formState.update {
      it.copy(
        title = sample.title,
        authors = sample.authors,
        year = sample.year,
        venueOrCitation = sample.venue,
        manuscriptText = sample.manuscriptText,
        importedFileName = "${sample.id}.txt",
        importedFormat = ManuscriptInputFormat.TEXT,
        importedWordCount = words,
        importedPageCount = 1,
        pdfPageBitmaps = emptyList(),
        importedFileBanner = "Loaded ${sample.badge}",
        options = it.options.copy(isEmpirical = sample.isEmpirical)
      )
    }
    _statusBanner.value = "Loaded sample paper: ${sample.title}"
  }

  fun clearManuscriptForm() {
    _formState.update {
      WorkbenchFormState(
        inputModeIndex = it.inputModeIndex,
        title = "",
        authors = "",
        year = "2026",
        venueOrCitation = _settingsState.value.defaultVenue,
        manuscriptText = "",
        importedFileName = null,
        importedWordCount = 0,
        importedPageCount = 0,
        pdfPageBitmaps = emptyList(),
        importedFileBanner = null,
        options = _settingsState.value.defaultOptions
      )
    }
    _statusBanner.value = "Cleared manuscript input."
  }

  fun importManuscriptFromUri(context: Context, uri: Uri) {
    viewModelScope.launch {
      _statusBanner.value = "Parsing document (PDF / Word / Text)..."
      val renderVision = _settingsState.value.enablePdfVisionPages
      val result = ManuscriptExtractor.extractFromUri(context, uri, renderPdfBitmaps = renderVision)
      result.onSuccess { extracted ->
        val sampleAuthorsSet = BenchmarkManuscriptCatalog.samples.map { it.authors }.toSet()
        val sampleVenuesSet = BenchmarkManuscriptCatalog.samples.map { it.venue }.toSet()
        _formState.update { current ->
          val wasBenchmark = current.authors in sampleAuthorsSet ||
            current.venueOrCitation in sampleVenuesSet ||
            (current.importedFileBanner?.contains("Benchmark", ignoreCase = true) == true)
          current.copy(
            title = extracted.inferredTitle.ifBlank { current.title },
            authors = if (wasBenchmark) "" else current.authors,
            year = if (wasBenchmark) "" else current.year,
            venueOrCitation = if (wasBenchmark) "" else current.venueOrCitation,
            manuscriptText = extracted.extractedText,
            importedFileName = extracted.fileName,
            importedFormat = extracted.format,
            importedWordCount = extracted.wordCount,
            importedPageCount = extracted.pageCount,
            pdfPageBitmaps = extracted.pdfPageBitmaps,
            importedFileBanner = extracted.summaryNotice
          )
        }
        _statusBanner.value = extracted.summaryNotice
      }.onFailure { error ->
        Log.e(TAG, "Failed to extract manuscript file", error)
        _statusBanner.value = "Could not extract file: ${error.localizedMessage ?: "Unknown error"}"
      }
    }
  }

  fun importSharedManuscriptText(subject: String?, sharedText: String) {
    val cleaned = sharedText.trim()
    if (cleaned.isBlank()) return
    val words = cleaned.split(Regex("""\s+""")).size
    val inferredTitle = subject?.trim()?.takeIf { it.length in 4..180 }
      ?: cleaned.lineSequence().firstOrNull { it.trim().length in 10..160 }?.trim()
      ?: "Shared Academic Manuscript"
    _formState.update { current ->
      current.copy(
        inputModeIndex = 1,
        title = inferredTitle,
        authors = "",
        year = "",
        venueOrCitation = "",
        manuscriptText = cleaned,
        importedFileName = "shared_manuscript.txt",
        importedFormat = ManuscriptInputFormat.TEXT,
        importedWordCount = words,
        importedPageCount = (words / 350).coerceAtLeast(1),
        pdfPageBitmaps = emptyList(),
        importedFileBanner = "Imported shared text ($words words)"
      )
    }
    _activeMainTab.value = 0
    _statusBanner.value = "Imported shared manuscript text ($words words). Ready to analyze."
  }

  fun importReplicationCodeFromUri(context: Context, uri: Uri) {
    viewModelScope.launch {
      _statusBanner.value = "Extracting replication code package (Stage 00_prep_code)..."
      val result = ManuscriptExtractor.extractReplicationCodeFromUri(context, uri)
      result.onSuccess { (fileName, codeText) ->
        _formState.update {
          it.copy(
            replicationCodeFileName = fileName,
            replicationCodeText = codeText,
            options = it.options.copy(enableCodeAudit = true)
          )
        }
        _statusBanner.value = "Attached replication code: $fileName (${codeText.lines().size} lines)"
      }.onFailure { error ->
        Log.e(TAG, "Failed to extract replication code", error)
        _statusBanner.value = "Could not load code package: ${error.localizedMessage ?: "Unknown error"}"
      }
    }
  }

  fun cancelRunningPipeline() {
    activePipelineJob?.cancel()
    activePipelineJob = null
    _pipelineState.value = PipelineExecutionState.Idle
    _statusBanner.value = "Analysis cancelled."
  }

  fun runAdversarialReviewAndSave() {
    if (activePipelineJob?.isActive == true) return
    val form = _formState.value
    val settings = _settingsState.value
    if (form.manuscriptText.isBlank() && form.pdfPageBitmaps.isEmpty()) {
      _pipelineState.value = PipelineExecutionState.Error(
        "Please import a PDF, Word (.docx/.doc), or Text file—or paste your paper's text—before running the analysis."
      )
      return
    }
    if (!isNetworkAvailable()) {
      _pipelineState.value = PipelineExecutionState.Error(
        "No internet connection detected. Connect to Wi-Fi or mobile data to run the 30-stage AI analysis. Your cached dossiers and PDF exports in Review History remain fully available offline."
      )
      _statusBanner.value = "Offline mode: reconnect to run new AI analyses."
      return
    }

    activePipelineJob = viewModelScope.launch {
      _pipelineState.value = PipelineExecutionState.Running(
        stageCode = "00a",
        stageLabel = "Initializing Reviewer2 + PhD-Skills Pipeline...",
        progress = 0.08f
      )
      _followUpState.value = FollowUpQaState.Idle
      _searchGroundingState.value = SearchGroundingUiState.Idle
      try {
        val activeExternal = resolveActiveExternalProvider()
        val generatedReview = pipelineService.runAdversarialPipeline(
          title = form.title,
          authors = form.authors,
          year = form.year,
          venueOrCitation = form.venueOrCitation.ifBlank { settings.defaultVenue },
          manuscriptText = form.manuscriptText,
          replicationCodeText = form.replicationCodeText,
          pdfPageBitmaps = if (settings.enablePdfVisionPages) form.pdfPageBitmaps else emptyList(),
          reviewStrictness = settings.reviewStrictness,
          options = form.options,
          externalProvider = activeExternal,
          autoFallbackToKeylessFirebase = settings.autoFallbackToKeylessEngine,
          onStageUpdate = { code, label, progress ->
            _pipelineState.value = PipelineExecutionState.Running(
              stageCode = code,
              stageLabel = label,
              progress = progress
            )
          }
        )

        val activeUid = _authenticatedUserId.value
        val finalReview = if (settings.autoSaveToCloud && !activeUid.isNullOrBlank()) {
          val saveResult = repository.createReview(generatedReview)
          if (saveResult.isSuccess) {
            val newId = saveResult.getOrThrow()
            val refreshed = repository.getReviewById(newId, activeUid).getOrNull()
            _statusBanner.value = "Analysis complete & saved to Room Offline DB + Cloud Library."
            refreshed ?: generatedReview.copy(id = newId, userId = activeUid)
          } else {
            val localId = "local_${UUID.randomUUID()}"
            val fallback = generatedReview.copy(id = localId)
            _localReviews.update { listOf(fallback) + it }
            _statusBanner.value = "Analysis complete (cached in Room Offline Database)."
            fallback
          }
        } else {
          val localId = "local_${UUID.randomUUID()}"
          val localReview = generatedReview.copy(id = localId)
          _localReviews.update { listOf(localReview) + it }
          _statusBanner.value = "Analysis complete & cached in Room Offline Database!"
          localReview
        }

        // Persist to local Room SQL database for offline access
        runCatching {
          localRepository.cacheReviewOffline(finalReview)
        }

        _pipelineState.value = PipelineExecutionState.Completed(
          review = finalReview,
          savedToCloud = !activeUid.isNullOrBlank() && !finalReview.id.startsWith("local_")
        )
        _selectedDossier.value = finalReview
        // Automatically switch to the dedicated Output tab (Tab 1)
        _activeMainTab.value = 1
      } catch (e: CancellationException) {
        if (_pipelineState.value is PipelineExecutionState.Running) {
          _pipelineState.value = PipelineExecutionState.Idle
        }
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Pipeline execution failed", e)
        _pipelineState.value = PipelineExecutionState.Error(
          Reviewer2PipelineService.formatThrowableDetails(e)
        )
      } finally {
        activePipelineJob = null
      }
    }
  }

  fun cacheReviewOfflineNow(review: PaperReview) {
    viewModelScope.launch {
      val ensured = if (review.id.isBlank()) {
        review.copy(id = "sql_${UUID.randomUUID()}")
      } else {
        review
      }
      runCatching {
        localRepository.cacheReviewOffline(ensured)
      }.onSuccess {
        _localReviews.update { list ->
          if (list.any { it.id == ensured.id }) list.map { if (it.id == ensured.id) ensured else it } else list + ensured
        }
        if (_selectedDossier.value?.id == review.id || _selectedDossier.value?.id == ensured.id) {
          _selectedDossier.value = ensured
        }
        _statusBanner.value = "Saved '${ensured.title.take(30)}' to Room offline cache."
      }.onFailure { err ->
        _statusBanner.value = "Offline cache failed: ${err.localizedMessage}"
      }
    }
  }

  fun openDossier(review: PaperReview) {
    _selectedDossier.value = review
    _followUpState.value = FollowUpQaState.Idle
    _searchGroundingState.value = SearchGroundingUiState.Idle
    _activeMainTab.value = 1
  }

  fun closeDossier() {
    _activeMainTab.value = 0
  }

  fun toggleBookmark(review: PaperReview) {
    val activeUid = _authenticatedUserId.value
    val toggled = review.copy(bookmarked = !review.bookmarked)
    _localReviews.update { list ->
      list.map { if (it.id == review.id) toggled else it }
    }
    if (_selectedDossier.value?.id == review.id) {
      _selectedDossier.value = toggled
    }
    viewModelScope.launch {
      runCatching { localRepository.cacheReviewOffline(toggled) }
    }
    if (activeUid.isNullOrBlank() || review.id.startsWith("local_") || review.id.startsWith("sql_") || review.createdAt == null) {
      _statusBanner.value = if (toggled.bookmarked) "Starred in offline Room cache." else "Removed star from offline Room cache."
      return
    }
    viewModelScope.launch {
      val result = repository.updateReview(toggled)
      result.onSuccess {
        _statusBanner.value = if (toggled.bookmarked) "Added to starred dossiers." else "Removed from starred dossiers."
      }.onFailure {
        _statusBanner.value = if (toggled.bookmarked) {
          "Starred locally in Room cache (cloud sync pending)."
        } else {
          "Unstarred locally in Room cache (cloud sync pending)."
        }
      }
    }
  }

  fun savePersonalNotes(review: PaperReview, notes: String) {
    val activeUid = _authenticatedUserId.value
    val updated = review.copy(personalNotes = notes)
    _localReviews.update { list ->
      list.map { if (it.id == review.id) updated else it }
    }
    if (_selectedDossier.value?.id == review.id) {
      _selectedDossier.value = updated
    }
    viewModelScope.launch {
      runCatching { localRepository.cacheReviewOffline(updated) }
    }
    if (activeUid.isNullOrBlank() || review.id.startsWith("local_") || review.id.startsWith("sql_") || review.createdAt == null) {
      _statusBanner.value = "Notes saved to Room offline dossier."
      return
    }
    viewModelScope.launch {
      val result = repository.updateReview(updated)
      result.onSuccess {
        _statusBanner.value = "Referee notes saved to Room & Cloud Library."
      }.onFailure {
        _statusBanner.value = "Notes saved offline to Room cache (cloud sync pending)."
      }
    }
  }

  fun deleteReview(reviewId: String) {
    val activeUid = _authenticatedUserId.value
    if (reviewId.isBlank()) return
    _localReviews.update { list -> list.filterNot { it.id == reviewId } }
    if (_selectedDossier.value?.id == reviewId) {
      _selectedDossier.value = null
    }
    viewModelScope.launch {
      runCatching {
        localRepository.removeCachedReview(reviewId)
      }
    }
    if (reviewId.startsWith("local_") || reviewId.startsWith("sql_") || activeUid.isNullOrBlank()) {
      _statusBanner.value = "Review dossier deleted from offline Room cache."
      return
    }
    viewModelScope.launch {
      val result = repository.deleteReview(reviewId)
      result.onSuccess {
        _statusBanner.value = "Review dossier deleted."
      }.onFailure { error ->
        _statusBanner.value = "Removed locally; cloud delete failed: ${error.localizedMessage}"
      }
    }
  }

  // ==========================================================================
  // STAGE 03b: Live Google Search Grounding for Citations & Literature
  // ==========================================================================
  fun runStage03bGoogleSearchVerification(review: PaperReview) {
    if (_searchGroundingState.value is SearchGroundingUiState.Loading) return
    viewModelScope.launch {
      _searchGroundingState.value = SearchGroundingUiState.Loading
      try {
        val claimsBlock = buildString {
          appendLine("Abstract Summary: ${review.abstractSummary}")
          appendLine("Research Question: ${review.researchQuestion}")
          appendLine("Key Methodology: ${review.keyMethodology}")
          appendLine("Claimed Contributions (00c): ${review.coreContributions}")
        }.trim()

        val result = pipelineService.runGoogleSearchLiteratureVerification(
          title = review.title,
          authors = review.authors,
          year = review.publicationYear,
          venue = review.venueOrCitation,
          claimsAndSummary = claimsBlock
        )
        _searchGroundingState.value = SearchGroundingUiState.Success(result)
        runCatching {
          localRepository.cacheReviewOffline(
            review = review,
            searchGroundingReport = result.summaryText
          )
        }
        _statusBanner.value = "Stage 03b Google Search Grounding complete (${result.sources.size} web source(s))."
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Google Search Grounding timed out", e)
        _searchGroundingState.value = SearchGroundingUiState.Error(
          "Stage 03b Google Search Grounding timed out after 90 seconds."
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Google Search Grounding failed", e)
        _searchGroundingState.value = SearchGroundingUiState.Error(
          Reviewer2PipelineService.formatThrowableDetails(e)
        )
      }
    }
  }

  // ==========================================================================
  // MULTI-TURN GEMINI REFEREE CHATBOT (startChat + sendMessageStream + SQL)
  // ==========================================================================
  fun updateChatPersona(persona: ChatbotRolePersona) {
    _chatConfigState.update {
      it.copy(
        selectedPersona = persona,
        enableSearchGrounding = if (persona == ChatbotRolePersona.SEARCH_FACT_CHECKER) true else it.enableSearchGrounding
      )
    }
  }

  fun updateChatModelTier(modelTier: GeminiModelTier) {
    _chatConfigState.update { it.copy(selectedModelTier = modelTier) }
  }

  fun updateChatSearchGrounding(enabled: Boolean) {
    _chatConfigState.update { it.copy(enableSearchGrounding = enabled) }
  }

  fun sendMultiTurnChatMessage(review: PaperReview?, userMessage: String) {
    val trimmed = userMessage.trim()
    if (trimmed.isBlank() || _chatConfigState.value.isStreaming) return

    val currentConfig = refereeChatState.value
    val reviewKey = review?.id?.ifBlank { "global_workbench_chat" } ?: "global_workbench_chat"
    val priorHistory = currentConfig.messages.map {
      ChatTurnInput(role = it.role, text = it.text)
    }

    viewModelScope.launch {
      // 1. Persist user message to SQLite
      runCatching {
        localRepository.insertChatMessage(
          SqlChatMessageEntity(
            reviewId = reviewKey,
            role = "user",
            personaId = currentConfig.selectedPersona.id,
            modelTierId = currentConfig.selectedModelTier.modelId,
            content = trimmed
          )
        )
      }

      _chatConfigState.update {
        it.copy(
          isStreaming = true,
          streamingPartialText = "",
          streamingQueries = emptyList(),
          streamingSources = emptyList(),
          errorMessage = null
        )
      }

      var finalReply = ""
      var finalQueries: List<String> = emptyList()
      var finalSources: List<GroundedWebSource> = emptyList()

      try {
        val activeExternal = resolveActiveExternalProvider()
        pipelineService.streamMultiTurnRefereeChat(
          review = review,
          history = priorHistory,
          userMessage = trimmed,
          persona = currentConfig.selectedPersona,
          modelTier = currentConfig.selectedModelTier,
          enableSearchGrounding = currentConfig.enableSearchGrounding,
          externalProvider = activeExternal,
          autoFallbackToKeylessFirebase = _settingsState.value.autoFallbackToKeylessEngine
        ).collect { chunk ->
          finalReply = chunk.partialText
          finalQueries = chunk.searchQueries
          finalSources = chunk.sources
          _chatConfigState.update {
            it.copy(
              isStreaming = true,
              streamingPartialText = chunk.partialText,
              streamingQueries = chunk.searchQueries,
              streamingSources = chunk.sources
            )
          }
        }

        if (finalReply.isNotBlank()) {
          val serializedQueries = finalQueries.joinToString("||")
          val serializedSources = finalSources.joinToString("\n") { "${it.title}|~|${it.url}" }
          runCatching {
            localRepository.insertChatMessage(
              SqlChatMessageEntity(
                reviewId = reviewKey,
                role = "model",
                personaId = currentConfig.selectedPersona.id,
                modelTierId = currentConfig.selectedModelTier.modelId,
                content = finalReply,
                searchGroundedQueries = serializedQueries,
                searchGroundedSources = serializedSources
              )
            )
          }
          _chatConfigState.update {
            it.copy(
              isStreaming = false,
              streamingPartialText = "",
              streamingQueries = emptyList(),
              streamingSources = emptyList()
            )
          }
        } else {
          _chatConfigState.update {
            it.copy(
              isStreaming = false,
              errorMessage = "Gemini returned an empty response."
            )
          }
        }
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Multi-turn chat stream timed out", e)
        _chatConfigState.update {
          it.copy(
            isStreaming = false,
            errorMessage = "Gemini chat response timed out after 90 seconds."
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Multi-turn chat stream failed", e)
        _chatConfigState.update {
          it.copy(
            isStreaming = false,
            errorMessage = Reviewer2PipelineService.formatThrowableDetails(e)
          )
        }
      }
    }
  }

  fun clearMultiTurnChatHistory(review: PaperReview?) {
    val reviewKey = review?.id?.ifBlank { "global_workbench_chat" } ?: "global_workbench_chat"
    viewModelScope.launch {
      runCatching { localRepository.clearChatHistory(reviewKey) }
      _chatConfigState.update {
        it.copy(
          isStreaming = false,
          streamingPartialText = "",
          streamingQueries = emptyList(),
          streamingSources = emptyList(),
          errorMessage = null
        )
      }
      _statusBanner.value = "Cleared Gemini Referee chat history."
    }
  }

  // ==========================================================================
  // CUSTOM / FREE AI API PROVIDERS & API KEYS MANAGEMENT (SQL-PERSISTED)
  // ==========================================================================
  private fun resolveActiveExternalProvider(): AiApiProviderEntry? {
    val settings = _settingsState.value
    if (!settings.useCustomApiProvider) return null
    val selected = apiProviders.value.find { it.id == settings.activeProviderId }
    return if (selected != null &&
      selected.protocolType != ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS &&
      selected.apiKey.isNotBlank()
    ) {
      selected
    } else {
      null
    }
  }

  fun saveOrUpdateApiProvider(entry: AiApiProviderEntry) {
    viewModelScope.launch {
      val updatedEntry = entry.copy(
        enabled = entry.apiKey.isNotBlank() || entry.protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS
      )
      runCatching {
        localRepository.upsertApiProvider(updatedEntry)
      }
      if (updatedEntry.apiKey.isNotBlank() &&
        updatedEntry.protocolType != ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS
      ) {
        updateSettings {
          it.copy(
            activeProviderId = updatedEntry.id,
            useCustomApiProvider = true
          )
        }
      }
      _statusBanner.value = "Saved API key & configuration for ${updatedEntry.serviceName}."
    }
  }

  fun addCustomApiProvider(
    serviceName: String,
    protocolType: ApiProtocolType,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    notes: String
  ) {
    val cleanName = serviceName.trim()
    if (cleanName.isEmpty()) return
    val newEntry = AiApiProviderEntry(
      id = "custom_api_${UUID.randomUUID()}",
      serviceName = cleanName,
      protocolType = protocolType,
      baseUrl = baseUrl.trim().ifBlank { "https://openrouter.ai/api/v1/chat/completions" },
      modelId = modelId.trim().ifBlank { "deepseek/deepseek-r1:free" },
      apiKey = apiKey.trim(),
      isFreeTierPreset = false,
      enabled = apiKey.isNotBlank(),
      notes = notes.trim().ifBlank { "Custom user-added AI API service" }
    )
    saveOrUpdateApiProvider(newEntry)
  }

  fun selectActiveApiProvider(providerId: String) {
    val target = apiProviders.value.find { it.id == providerId } ?: return
    val isKeyless = target.protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS
    updateSettings {
      it.copy(
        activeProviderId = providerId,
        useCustomApiProvider = !isKeyless
      )
    }
    _statusBanner.value = "Active AI Analysis Provider: ${target.serviceName}"
  }

  fun deleteCustomApiProvider(providerId: String) {
    if (providerId == DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID) return
    viewModelScope.launch {
      runCatching { localRepository.deleteApiProvider(providerId) }
      if (_settingsState.value.activeProviderId == providerId) {
        updateSettings {
          it.copy(
            activeProviderId = DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID,
            useCustomApiProvider = false
          )
        }
      }
      _statusBanner.value = "Removed custom API service."
    }
  }

  fun testApiProviderKey(entry: AiApiProviderEntry) {
    if (_isTestingApiKey.value) return
    viewModelScope.launch {
      _isTestingApiKey.value = true
      _apiTestResultMessage.value = "Testing connection to ${entry.serviceName} (${entry.modelId})..."
      val result = ExternalApiGatewayClient.testProviderConnection(entry)
      result.onSuccess { message ->
        _apiTestResultMessage.value = "✅ $message"
        _statusBanner.value = "API key verified for ${entry.serviceName}!"
      }.onFailure { error ->
        _apiTestResultMessage.value = "❌ ${error.localizedMessage ?: "Connection test failed."}"
      }
      _isTestingApiKey.value = false
    }
  }

  fun askFollowUpQuestion(review: PaperReview, question: String) {
    sendMultiTurnChatMessage(review, question)
  }

  fun dismissStatusBanner() {
    _statusBanner.value = null
  }

  companion object {
    private const val PREFS_NAME = "paper_critic_settings"

    private fun loadSettingsFromPrefs(prefs: SharedPreferences?): AppSettingsState {
      if (prefs == null) return AppSettingsState()
      val themeName = prefs.getString("themeMode", ThemeModePreference.IOS_LIGHT.name)
      val themeMode = ThemeModePreference.entries.find { it.name == themeName }
        ?: ThemeModePreference.IOS_LIGHT
      return AppSettingsState(
        themeMode = themeMode,
        compactDossierMode = prefs.getBoolean("compactDossierMode", false),
        monospaceEquations = prefs.getBoolean("monospaceEquations", true),
        reviewStrictness = prefs.getString("reviewStrictness", "AGGRESSIVE_REVIEWER2")
          ?: "AGGRESSIVE_REVIEWER2",
        autoSaveToCloud = prefs.getBoolean("autoSaveToCloud", true),
        enablePdfVisionPages = prefs.getBoolean("enablePdfVisionPages", true),
        enableLegalScreen = prefs.getBoolean("enableLegalScreen", true),
        enableGrimStatsCheck = prefs.getBoolean("enableGrimStatsCheck", true),
        useCustomApiProvider = prefs.getBoolean("useCustomApiProvider", false),
        autoFallbackToKeylessEngine = prefs.getBoolean("autoFallbackToKeylessEngine", true),
        activeProviderId = prefs.getString("activeProviderId", DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID)
          ?: DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID,
        defaultVenue = prefs.getString("defaultVenue", "Top-Tier Peer-Reviewed Journal / Conference")
          ?: "Top-Tier Peer-Reviewed Journal / Conference",
        defaultOptions = PipelineOptions(
          isEmpirical = prefs.getBoolean("opt_isEmpirical", true),
          enableMathAudit = prefs.getBoolean("opt_enableMathAudit", true),
          enableCodeAudit = prefs.getBoolean("opt_enableCodeAudit", true),
          enableEditorNote = prefs.getBoolean("opt_enableEditorNote", true),
          enableCopyeditWriterMode = prefs.getBoolean("opt_enableCopyeditWriterMode", true)
        ),
        reportTextScale = prefs.getFloat("reportTextScale", 1.0f)
      )
    }

    private fun saveSettingsToPrefs(prefs: SharedPreferences?, state: AppSettingsState) {
      prefs?.edit()?.apply {
        putString("themeMode", state.themeMode.name)
        putBoolean("compactDossierMode", state.compactDossierMode)
        putBoolean("monospaceEquations", state.monospaceEquations)
        putString("reviewStrictness", state.reviewStrictness)
        putBoolean("autoSaveToCloud", state.autoSaveToCloud)
        putBoolean("enablePdfVisionPages", state.enablePdfVisionPages)
        putBoolean("enableLegalScreen", state.enableLegalScreen)
        putBoolean("enableGrimStatsCheck", state.enableGrimStatsCheck)
        putBoolean("useCustomApiProvider", state.useCustomApiProvider)
        putBoolean("autoFallbackToKeylessEngine", state.autoFallbackToKeylessEngine)
        putString("activeProviderId", state.activeProviderId)
        putString("defaultVenue", state.defaultVenue)
        putBoolean("opt_isEmpirical", state.defaultOptions.isEmpirical)
        putBoolean("opt_enableMathAudit", state.defaultOptions.enableMathAudit)
        putBoolean("opt_enableCodeAudit", state.defaultOptions.enableCodeAudit)
        putBoolean("opt_enableEditorNote", state.defaultOptions.enableEditorNote)
        putBoolean("opt_enableCopyeditWriterMode", state.defaultOptions.enableCopyeditWriterMode)
        putFloat("reportTextScale", state.reportTextScale)
        apply()
      }
    }

    fun factory(currentUserId: String?) = viewModelFactory {
      initializer {
        val app = checkNotNull(this[APPLICATION_KEY]) {
          "APPLICATION_KEY missing from CreationExtras"
        }
        val databaseId = app.getString(R.string.firestore_database_id)
        val db = FirebaseFirestore.getInstance(databaseId)
        val sqlDatabase = PaperCriticSqlDatabase.getDatabase(app)
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        PaperCriticViewModel(
          repository = ReviewRepository(db),
          localRepository = LocalReviewRepository(sqlDatabase.sqlDao()),
          pipelineService = Reviewer2PipelineService(),
          prefs = prefs,
          initialUserId = currentUserId,
          isNetworkAvailable = { NetworkHealthChecker.isInternetReachable(app) }
        )
      }
    }
  }
}
