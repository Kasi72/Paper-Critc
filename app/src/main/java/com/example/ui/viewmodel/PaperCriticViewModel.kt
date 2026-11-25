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
import com.example.data.local.ANONYMOUS_OWNER_SCOPE
import com.example.data.local.PaperCriticSqlDatabase
import com.example.data.local.SqlChatMessageEntity
import com.example.data.local.normalizeOwnerScope
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import com.example.data.model.BenchmarkManuscript
import com.example.data.model.BenchmarkManuscriptCatalog
import com.example.data.model.DefaultAiProviderCatalog
import com.example.data.model.PaperReview
import com.example.data.model.PipelineOptions
import com.example.data.model.ReviewMode
import com.example.data.repository.LocalReviewRepository
import com.example.data.repository.ReviewRepository
import com.example.util.DocumentCoverageReport
import com.example.util.ManuscriptExtractor
import com.example.util.ManuscriptInputFormat
import com.example.util.NetworkHealthChecker
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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

sealed interface AiProviderRoute {
  data object BuiltInKeyless : AiProviderRoute
  data class CustomProvider(val provider: AiApiProviderEntry) : AiProviderRoute
  data class UnavailableCustomProvider(val providerId: String, val reason: String) : AiProviderRoute
}

data class AppSettingsState(
  val themeMode: ThemeModePreference = ThemeModePreference.IOS_LIGHT,
  val compactDossierMode: Boolean = false,
  val monospaceEquations: Boolean = true,
  val reviewStrictness: String = "BALANCED_AREA_CHAIR",
  val autoSaveToCloud: Boolean = true,
  val confidentialLocalOnly: Boolean = false,
  val enablePdfVisionPages: Boolean = true,
  val enableLegalScreen: Boolean = true,
  val enableGrimStatsCheck: Boolean = true,
  val useCustomApiProvider: Boolean = false,
  val autoFallbackToKeylessEngine: Boolean = false,
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
  val coverageReport: DocumentCoverageReport? = DocumentCoverageReport.forCompleteText(
    text = BenchmarkManuscriptCatalog.samples.first().manuscriptText,
    engine = "Benchmark Sample Text"
  ),
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

  private val activeOwnerScope: StateFlow<String> = _authenticatedUserId
    .map { normalizeOwnerScope(it) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, normalizeOwnerScope(initialUserId))

  private val sessionGeneration = AtomicLong(0L)

  internal fun currentOwnerScope(): String = normalizeOwnerScope(_authenticatedUserId.value)

  internal fun currentSessionGeneration(): Long = sessionGeneration.get()

  private val _localReviews = MutableStateFlow<List<PaperReview>>(emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  private val sqlReviewsFlow: StateFlow<List<PaperReview>> = activeOwnerScope
    .flatMapLatest { scope ->
      localRepository.observeCachedReviews(scope).catch { emit(emptyList()) }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  /**
   * Local reports authored while signed out remain quarantined in [ANONYMOUS_OWNER_SCOPE] until
   * a signed-in user explicitly adopts them.
   */
  val adoptableAnonymousReviews: StateFlow<List<PaperReview>> = combine(
    _authenticatedUserId,
    localRepository.observeAnonymousReviews().catch { emit(emptyList()) }
  ) { uid, anonymousList ->
    if (uid.isNullOrBlank()) emptyList() else anonymousList
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

  val offlineCachedIds: StateFlow<Set<String>> = sqlReviewsFlow
    .map { list -> list.map { it.id }.filter { it.isNotBlank() }.toSet() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptySet())

  @OptIn(ExperimentalCoroutinesApi::class)
  val sqlCachedReviewCount: StateFlow<Int> = activeOwnerScope
    .flatMapLatest { scope ->
      localRepository.observeCachedReviewCount(scope).catch { emit(0) }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

  @OptIn(ExperimentalCoroutinesApi::class)
  val sqlTotalChatMessageCount: StateFlow<Int> = activeOwnerScope
    .flatMapLatest { scope ->
      localRepository.observeTotalChatMessageCount(scope).catch { emit(0) }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

  @OptIn(ExperimentalCoroutinesApi::class)
  val apiProviders: StateFlow<List<AiApiProviderEntry>> = activeOwnerScope
    .flatMapLatest { scope ->
      localRepository.observeApiProviders(scope)
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
    }
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
    _authenticatedUserId,
    _localReviews,
    sqlReviewsFlow
  ) { uid, memList, sqlList ->
    val combined = (memList + sqlList).distinctBy { it.id.ifBlank { it.title } }
    if (uid.isNullOrBlank()) {
      combined.count { it.id.startsWith("local_") || it.id.startsWith("sql_") || it.userId.isBlank() }
    } else {
      combined.count {
        it.userId == uid && (it.id.startsWith("local_") || it.id.startsWith("sql_"))
      }
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

  private val _isSyncingPortfolio = MutableStateFlow(false)
  val isSyncingPortfolio: StateFlow<Boolean> = _isSyncingPortfolio.asStateFlow()

  @OptIn(ExperimentalCoroutinesApi::class)
  private val cloudReviewsFlow: StateFlow<ArchiveUiState> = _authenticatedUserId
    .flatMapLatest { uid ->
      if (!uid.isNullOrBlank()) {
        repository.observeReviews(uid)
          .onEach { cloudList ->
            if (_authenticatedUserId.value == uid) {
              runSuspendCatching {
                localRepository.cacheReviewsOffline(cloudList, ownerScope = uid)
              }
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

  private val initialSettings = loadSettingsFromPrefs(prefs, normalizeOwnerScope(initialUserId))
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
    .map {
      listOf(it.manuscriptText, it.options.isEmpirical, it.replicationCodeText, it.coverageReport)
    }
    .distinctUntilChanged()
    .debounce(260L)
    .mapLatest { tuple ->
      val text = tuple[0] as String
      val isEmpirical = tuple[1] as Boolean
      val code = tuple[2] as String
      val cov = tuple[3] as DocumentCoverageReport?
      val resolvedCov = cov ?: if (text.isNotBlank()) {
        DocumentCoverageReport.forDirectPastedText(text, engine = "Direct Text Editor")
      } else {
        DocumentCoverageReport.unknown("Empty manuscript editor")
      }
      withContext(Dispatchers.Default) {
        ManuscriptForensicEngine.analyzeManuscript(
          text = text,
          isEmpiricalTrack = isEmpirical,
          replicationCode = code,
          coverageReport = resolvedCov
        )
      }
    }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
      initialValue = ManuscriptForensicEngine.analyzeManuscript(
        text = BenchmarkManuscriptCatalog.samples.first().manuscriptText,
        isEmpiricalTrack = initialSettings.defaultOptions.isEmpirical,
        replicationCode = "",
        coverageReport = DocumentCoverageReport.forCompleteText(
          text = BenchmarkManuscriptCatalog.samples.first().manuscriptText,
          engine = "Benchmark Sample Text"
        )
      )
    )

  private var activePipelineJob: Job? = null
  private var activeChatJob: Job? = null
  private var activeSearchGroundingJob: Job? = null
  private var activeSyncJob: Job? = null

  private suspend inline fun <T> runSuspendCatching(crossinline block: suspend () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }

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
  private val activeDossierChatEntities: StateFlow<List<SqlChatMessageEntity>> = combine(
    _selectedDossier,
    activeOwnerScope
  ) { dossier, scope ->
    val reviewKey = dossier?.id?.ifBlank { "global_workbench_chat" } ?: "global_workbench_chat"
    reviewKey to scope
  }.flatMapLatest { (reviewKey, scope) ->
    localRepository.observeChatMessages(reviewKey, scope).catch { emit(emptyList()) }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

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

  init {
    viewModelScope.launch {
      runSuspendCatching { localRepository.migratePlaintextApiKeysIfNeeded() }
        .onSuccess { migrated ->
          if (migrated > 0) {
            Log.i(TAG, "Migrated $migrated legacy API key(s) to Android Keystore AES-256-GCM.")
          } else if (migrated < 0) {
            _statusBanner.value = "Encrypted credential storage (Android Keystore) is unavailable; legacy API keys are locked."
          }
        }
        .onFailure { err ->
          Log.w(TAG, "API key migration check failed", err)
        }
    }
  }

  /**
   * Enforces strict account/session isolation on every login, logout, or account switch:
   * - Bumps [sessionGeneration] so any in-flight inference or sync from the previous session is discarded.
   * - Cancels active pipeline, chat, grounding, and sync jobs.
   * - Clears all account-scoped in-memory reviews, selected dossiers, and provider test banners.
   * - Reloads account-scoped provider/engine preferences so Account B never inherits Account A's config.
   */
  fun onAuthenticatedUserChanged(newUserId: String?) {
    val previousScope = currentOwnerScope()
    val nextScope = normalizeOwnerScope(newUserId)
    if (previousScope == nextScope && _authenticatedUserId.value == newUserId) {
      return
    }

    sessionGeneration.incrementAndGet()
    activePipelineJob?.cancel()
    activePipelineJob = null
    activeChatJob?.cancel()
    activeChatJob = null
    activeSearchGroundingJob?.cancel()
    activeSearchGroundingJob = null
    activeSyncJob?.cancel()
    activeSyncJob = null

    _localReviews.value = emptyList()
    _selectedDossier.value = null
    _pipelineState.value = PipelineExecutionState.Idle
    _followUpState.value = FollowUpQaState.Idle
    _searchGroundingState.value = SearchGroundingUiState.Idle
    _apiTestResultMessage.value = null
    _chatConfigState.update {
      it.copy(
        isStreaming = false,
        streamingPartialText = "",
        streamingQueries = emptyList(),
        streamingSources = emptyList(),
        errorMessage = null
      )
    }

    _authenticatedUserId.value = newUserId
    val reloadedSettings = loadSettingsFromPrefs(prefs, nextScope)
    _settingsState.value = reloadedSettings

    if (!newUserId.isNullOrBlank() &&
      shouldAllowPortfolioSync(
        confidentialLocalOnly = reloadedSettings.confidentialLocalOnly,
        autoSaveToCloud = reloadedSettings.autoSaveToCloud,
        isAutomatic = true
      )
    ) {
      syncPortfolioToCloud(isAutomatic = true)
    }
  }

  /**
   * Explicitly adopts anonymous local drafts created while signed out into the currently
   * authenticated user's scope so they can be viewed and optionally synced to Firestore.
   */
  fun adoptAnonymousReviewsForCurrentUser(reviewIds: Collection<String>? = null) {
    val uid = _authenticatedUserId.value?.takeIf { it.isNotBlank() }
    if (uid == null) {
      _statusBanner.value = "Sign in with Google before adopting local anonymous reports."
      return
    }
    val sessionAtStart = sessionGeneration.get()
    viewModelScope.launch {
      val idsToAdopt = reviewIds?.toList()
        ?: localRepository.getCachedReviewsOnce(ANONYMOUS_OWNER_SCOPE).map { it.id }
      if (idsToAdopt.isEmpty()) {
        _statusBanner.value = "No local anonymous drafts found to adopt."
        return@launch
      }
      if (!canCommitInferenceResult(uid, sessionAtStart, currentOwnerScope(), sessionGeneration.get())) {
        return@launch
      }
      val adoptedCount = runSuspendCatching {
        localRepository.adoptAnonymousReviewsToAccount(idsToAdopt, uid)
      }.getOrDefault(0)
      if (adoptedCount > 0) {
        _statusBanner.value = "Adopted $adoptedCount local anonymous report(s) into your account. Tap Sync Now to upload."
      } else {
        _statusBanner.value = "No local anonymous reports were adopted."
      }
    }
  }

  fun syncPortfolioToCloud() = syncPortfolioToCloud(isAutomatic = false)

  fun syncPortfolioToCloud(isAutomatic: Boolean) {
    val settings = _settingsState.value
    if (settings.confidentialLocalOnly) {
      if (!isAutomatic) {
        _statusBanner.value = "Local-Only Report Storage is active. Disable it in Settings → Cloud to sync to Firestore."
      }
      return
    }
    if (!shouldAllowPortfolioSync(
        confidentialLocalOnly = settings.confidentialLocalOnly,
        autoSaveToCloud = settings.autoSaveToCloud,
        isAutomatic = isAutomatic
      )
    ) {
      return
    }
    val uid = _authenticatedUserId.value?.takeIf { it.isNotBlank() }
    if (uid == null) {
      if (!isAutomatic) {
        _statusBanner.value = "Sign in with Google to sync your paper review portfolio across devices."
      }
      return
    }
    if (_isSyncingPortfolio.value) return
    val initiatingSession = sessionGeneration.get()

    activeSyncJob = viewModelScope.launch {
      _isSyncingPortfolio.value = true
      try {
        // Strictly scope upload candidates to records already owned by [uid] — NEVER auto-upload
        // anonymous drafts or another user's cached records.
        val scopedSqlReviews = localRepository.getCachedReviewsOnce(uid)
        val candidatePool = (_localReviews.value + scopedSqlReviews)
          .distinctBy { it.id.ifBlank { it.title } }
        val pendingLocal = candidatePool.filter {
          it.userId == uid && (it.id.startsWith("local_") || it.id.startsWith("sql_"))
        }
        var uploadedCount = 0
        for (localItem in pendingLocal) {
          if (!canCommitInferenceResult(uid, initiatingSession, currentOwnerScope(), sessionGeneration.get())) {
            return@launch
          }
          val saveResult = repository.createReview(localItem.copy(userId = uid))
          if (saveResult.isSuccess) {
            val newCloudId = saveResult.getOrThrow()
            uploadedCount++
            val syncedItem = repository.getReviewById(newCloudId, uid).getOrNull()
              ?: localItem.copy(id = newCloudId, userId = uid)
            if (!canCommitInferenceResult(uid, initiatingSession, currentOwnerScope(), sessionGeneration.get())) {
              return@launch
            }
            runSuspendCatching {
              localRepository.migrateChatHistory(localItem.id, newCloudId, uid)
              localRepository.removeCachedReview(localItem.id, uid)
              localRepository.cacheReviewOffline(syncedItem, ownerScope = uid)
            }
            _localReviews.update { currentList ->
              currentList.filterNot { it.id == localItem.id }
            }
            if (_selectedDossier.value?.id == localItem.id) {
              _selectedDossier.value = syncedItem
            }
          }
        }
        if (!canCommitInferenceResult(uid, initiatingSession, currentOwnerScope(), sessionGeneration.get())) {
          return@launch
        }
        val refreshResult = repository.getUserReviews()
        val cloudList = refreshResult.getOrNull().orEmpty()
        if (cloudList.isNotEmpty() &&
          canCommitInferenceResult(uid, initiatingSession, currentOwnerScope(), sessionGeneration.get())
        ) {
          runSuspendCatching {
            localRepository.cacheReviewsOffline(cloudList, ownerScope = uid)
          }
        }
        val totalSynced = cloudList.size
        _statusBanner.value = if (uploadedCount > 0) {
          "Synced $uploadedCount account review(s) to Google Cloud ($totalSynced total in portfolio)."
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
        activeSyncJob = null
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
    _formState.update { current ->
      if (value == current.manuscriptText) return@update current
      val updatedCoverage = if (value.isBlank()) {
        DocumentCoverageReport.unknown("Manuscript editor is empty.")
      } else {
        DocumentCoverageReport.forDirectPastedText(value, engine = "Direct Text Editor")
      }
      current.copy(
        manuscriptText = value,
        importedFormat = ManuscriptInputFormat.TEXT,
        importedWordCount = words,
        importedPageCount = (words / 350).coerceAtLeast(1),
        pdfPageBitmaps = emptyList(),
        coverageReport = updatedCoverage
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
    saveSettingsToPrefs(prefs, updated, currentOwnerScope())
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
        coverageReport = DocumentCoverageReport.forCompleteText(
          text = sample.manuscriptText,
          engine = "Benchmark Sample Text"
        ),
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
        year = "",
        venueOrCitation = _settingsState.value.defaultVenue,
        manuscriptText = "",
        importedFileName = null,
        importedWordCount = 0,
        importedPageCount = 0,
        pdfPageBitmaps = emptyList(),
        coverageReport = null,
        importedFileBanner = null,
        options = _settingsState.value.defaultOptions
      )
    }
    _statusBanner.value = "Cleared manuscript input."
  }

  fun importManuscriptFromUri(context: Context, uri: Uri) {
    viewModelScope.launch {
      _statusBanner.value = "Extracting full document text & page coordinates..."
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
            coverageReport = extracted.coverageReport,
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
        coverageReport = DocumentCoverageReport.forDirectPastedText(
          text = cleaned,
          engine = "Shared Plain Text"
        ),
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

    // Fail-closed provider routing: NEVER silently route to built-in Firebase AI Logic if
    // custom provider mode is enabled and the selected provider/key is unavailable.
    val providerRoute = resolveAiProviderRoute(settings, apiProviders.value)
    val activeExternal = when (providerRoute) {
      is AiProviderRoute.BuiltInKeyless -> null
      is AiProviderRoute.CustomProvider -> providerRoute.provider
      is AiProviderRoute.UnavailableCustomProvider -> {
        _pipelineState.value = PipelineExecutionState.Error(providerRoute.reason)
        _statusBanner.value = providerRoute.reason
        return
      }
    }

    // Capture initiating account & session generation BEFORE inference starts
    val initiatingUid = _authenticatedUserId.value?.takeIf { it.isNotBlank() }
    val initiatingOwnerScope = normalizeOwnerScope(initiatingUid)
    val initiatingSessionGen = sessionGeneration.get()

    activePipelineJob = viewModelScope.launch {
      _pipelineState.value = PipelineExecutionState.Running(
        stageCode = "00a",
        stageLabel = "Initializing Reviewer2 + PhD-Skills Pipeline...",
        progress = 0.08f
      )
      _followUpState.value = FollowUpQaState.Idle
      _searchGroundingState.value = SearchGroundingUiState.Idle
      try {
        val generatedReview = pipelineService.runAdversarialPipeline(
          title = form.title,
          authors = form.authors,
          year = form.year,
          venueOrCitation = form.venueOrCitation.ifBlank { settings.defaultVenue },
          manuscriptText = form.manuscriptText,
          replicationCodeText = form.replicationCodeText,
          pdfPageBitmaps = if (settings.enablePdfVisionPages) form.pdfPageBitmaps else emptyList(),
          coverageReport = form.coverageReport ?: if (form.manuscriptText.isNotBlank()) {
            DocumentCoverageReport.forDirectPastedText(
              text = form.manuscriptText,
              engine = "Direct Text Editor"
            )
          } else {
            DocumentCoverageReport.unknown("No text or file coverage report attached")
          },
          reviewStrictness = settings.reviewStrictness,
          options = form.options,
          externalProvider = activeExternal,
          autoFallbackToKeylessFirebase = settings.autoFallbackToKeylessEngine,
          onStageUpdate = { code, label, progress ->
            if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
              _pipelineState.value = PipelineExecutionState.Running(
                stageCode = code,
                stageLabel = label,
                progress = progress
              )
            }
          }
        )

        // Session-bound commit guard: if the account changed or signed out mid-run, discard result
        if (!canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          Log.w(TAG, "Account session switched during pipeline run; discarding result to prevent cross-account contamination.")
          return@launch
        }

        val scopedReview = generatedReview.copy(userId = initiatingUid.orEmpty())
        val shouldSyncCloud = !settings.confidentialLocalOnly && settings.autoSaveToCloud && initiatingUid != null
        val finalReview = if (shouldSyncCloud && initiatingUid != null) {
          val saveResult = repository.createReview(scopedReview)
          if (!canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
            return@launch
          }
          if (saveResult.isSuccess) {
            val newId = saveResult.getOrThrow()
            val refreshed = repository.getReviewById(newId, initiatingUid).getOrNull()
            _statusBanner.value = "Analysis complete & saved to Room Offline DB + Cloud Library."
            refreshed ?: scopedReview.copy(id = newId, userId = initiatingUid)
          } else {
            val localId = "local_${UUID.randomUUID()}"
            val fallback = scopedReview.copy(id = localId)
            _localReviews.update { listOf(fallback) + it }
            _statusBanner.value = "Analysis complete (cached in Room Offline Database)."
            fallback
          }
        } else {
          val localId = "local_${UUID.randomUUID()}"
          val localReview = scopedReview.copy(id = localId)
          _localReviews.update { listOf(localReview) + it }
          _statusBanner.value = if (settings.confidentialLocalOnly) {
            "Local-only report storage: saved strictly to on-device Room DB (not synced to cloud)."
          } else {
            "Analysis complete & cached in Room Offline Database!"
          }
          localReview
        }

        if (!canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          return@launch
        }

        // Persist strictly under the initiating account's ownerScope
        runSuspendCatching {
          localRepository.cacheReviewOffline(finalReview, ownerScope = initiatingOwnerScope)
        }

        _pipelineState.value = PipelineExecutionState.Completed(
          review = finalReview,
          savedToCloud = initiatingUid != null && !finalReview.id.startsWith("local_")
        )
        _selectedDossier.value = finalReview
        _activeMainTab.value = 1
      } catch (e: CancellationException) {
        if (_pipelineState.value is PipelineExecutionState.Running) {
          _pipelineState.value = PipelineExecutionState.Idle
        }
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Pipeline execution failed", e)
        if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          _pipelineState.value = PipelineExecutionState.Error(
            Reviewer2PipelineService.formatThrowableDetails(e)
          )
        }
      } finally {
        activePipelineJob = null
      }
    }
  }

  fun cacheReviewOfflineNow(review: PaperReview) {
    val ownerScope = currentOwnerScope()
    val sessionAtStart = sessionGeneration.get()
    viewModelScope.launch {
      val ensured = if (review.id.isBlank()) {
        review.copy(id = "sql_${UUID.randomUUID()}", userId = _authenticatedUserId.value.orEmpty())
      } else {
        review
      }
      runSuspendCatching {
        localRepository.cacheReviewOffline(ensured, ownerScope = ownerScope)
      }.onSuccess {
        if (!canCommitInferenceResult(ownerScope, sessionAtStart, currentOwnerScope(), sessionGeneration.get())) {
          return@onSuccess
        }
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
    if (_selectedDossier.value?.id != review.id) {
      activeChatJob?.cancel()
      activeChatJob = null
      activeSearchGroundingJob?.cancel()
      activeSearchGroundingJob = null
      _chatConfigState.update {
        it.copy(
          isStreaming = false,
          streamingPartialText = "",
          streamingQueries = emptyList(),
          streamingSources = emptyList(),
          errorMessage = null
        )
      }
    }
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
    val ownerScope = currentOwnerScope()
    val newBookmarked = !review.bookmarked
    _localReviews.update { list ->
      list.map { if (it.id == review.id) it.copy(bookmarked = newBookmarked) else it }
    }
    _selectedDossier.update { current ->
      if (current?.id == review.id) current.copy(bookmarked = newBookmarked) else current
    }
    viewModelScope.launch {
      runSuspendCatching {
        localRepository.updateBookmark(review, bookmarked = newBookmarked, ownerScope = ownerScope)
      }
    }
    if (activeUid.isNullOrBlank() || review.id.startsWith("local_") || review.id.startsWith("sql_") || review.createdAt == null) {
      _statusBanner.value = if (newBookmarked) "Starred in offline Room cache." else "Removed star from offline Room cache."
      return
    }
    viewModelScope.launch {
      // Field-level partial update so concurrent note edits never overwrite bookmark state
      val result = repository.updateBookmark(review.id, newBookmarked)
      result.onSuccess {
        _statusBanner.value = if (newBookmarked) "Added to starred dossiers." else "Removed from starred dossiers."
      }.onFailure {
        _statusBanner.value = if (newBookmarked) {
          "Starred locally in Room cache (cloud sync pending)."
        } else {
          "Unstarred locally in Room cache (cloud sync pending)."
        }
      }
    }
  }

  fun savePersonalNotes(review: PaperReview, notes: String) {
    val activeUid = _authenticatedUserId.value
    val ownerScope = currentOwnerScope()
    _localReviews.update { list ->
      list.map { if (it.id == review.id) it.copy(personalNotes = notes) else it }
    }
    _selectedDossier.update { current ->
      if (current?.id == review.id) current.copy(personalNotes = notes) else current
    }
    viewModelScope.launch {
      runSuspendCatching {
        localRepository.updatePersonalNotes(review, personalNotes = notes, ownerScope = ownerScope)
      }
    }
    if (activeUid.isNullOrBlank() || review.id.startsWith("local_") || review.id.startsWith("sql_") || review.createdAt == null) {
      _statusBanner.value = "Notes saved to Room offline dossier."
      return
    }
    viewModelScope.launch {
      // Field-level partial update so concurrent bookmark toggles never overwrite notes
      val result = repository.updatePersonalNotes(review.id, notes)
      result.onSuccess {
        _statusBanner.value = "Referee notes saved to Room & Cloud Library."
      }.onFailure {
        _statusBanner.value = "Notes saved offline to Room cache (cloud sync pending)."
      }
    }
  }

  fun deleteReview(reviewId: String) {
    val activeUid = _authenticatedUserId.value
    val ownerScope = currentOwnerScope()
    if (reviewId.isBlank()) return
    _localReviews.update { list -> list.filterNot { it.id == reviewId } }
    if (_selectedDossier.value?.id == reviewId) {
      activeChatJob?.cancel()
      activeChatJob = null
      activeSearchGroundingJob?.cancel()
      activeSearchGroundingJob = null
      _selectedDossier.value = null
    }
    viewModelScope.launch {
      runSuspendCatching {
        localRepository.removeCachedReview(reviewId, ownerScope)
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
    if (activeSearchGroundingJob?.isActive == true || _searchGroundingState.value is SearchGroundingUiState.Loading) return
    val initiatingOwnerScope = currentOwnerScope()
    val initiatingSessionGen = sessionGeneration.get()
    activeSearchGroundingJob = viewModelScope.launch {
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
        if (!canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          return@launch
        }
        _searchGroundingState.value = SearchGroundingUiState.Success(result)
        runSuspendCatching {
          localRepository.cacheReviewOffline(
            review = review,
            ownerScope = initiatingOwnerScope,
            searchGroundingReport = result.summaryText
          )
        }
        _statusBanner.value = "Stage 03b Google Search Grounding complete (${result.sources.size} web source(s))."
      } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Google Search Grounding timed out", e)
        if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          _searchGroundingState.value = SearchGroundingUiState.Error(
            "Stage 03b Google Search Grounding timed out after 90 seconds."
          )
        }
      } catch (e: CancellationException) {
        if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get()) &&
          _searchGroundingState.value is SearchGroundingUiState.Loading
        ) {
          _searchGroundingState.value = SearchGroundingUiState.Idle
        }
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Google Search Grounding failed", e)
        if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          _searchGroundingState.value = SearchGroundingUiState.Error(
            Reviewer2PipelineService.formatThrowableDetails(e)
          )
        }
      } finally {
        activeSearchGroundingJob = null
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
    if (trimmed.isBlank() || _chatConfigState.value.isStreaming || activeChatJob?.isActive == true) return

    // Fail-closed provider routing for multi-turn chat too
    val providerRoute = resolveAiProviderRoute(_settingsState.value, apiProviders.value)
    val activeExternal = when (providerRoute) {
      is AiProviderRoute.BuiltInKeyless -> null
      is AiProviderRoute.CustomProvider -> providerRoute.provider
      is AiProviderRoute.UnavailableCustomProvider -> {
        _chatConfigState.update { it.copy(errorMessage = providerRoute.reason) }
        _statusBanner.value = providerRoute.reason
        return
      }
    }

    val currentConfig = refereeChatState.value
    val reviewKey = review?.id?.ifBlank { "global_workbench_chat" } ?: "global_workbench_chat"
    val priorHistory = currentConfig.messages.map {
      ChatTurnInput(role = it.role, text = it.text)
    }
    val initiatingOwnerScope = currentOwnerScope()
    val initiatingSessionGen = sessionGeneration.get()

    activeChatJob = viewModelScope.launch {
      // 1. Persist user message to SQLite under initiatingOwnerScope
      runSuspendCatching {
        localRepository.insertChatMessage(
          SqlChatMessageEntity(
            reviewId = reviewKey,
            ownerScope = initiatingOwnerScope,
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
          if (!canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
            return@collect
          }
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

        if (!canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          return@launch
        }

        if (finalReply.isNotBlank()) {
          val serializedQueries = finalQueries.joinToString("||")
          val serializedSources = finalSources.joinToString("\n") { "${it.title}|~|${it.url}" }
          runSuspendCatching {
            localRepository.insertChatMessage(
              SqlChatMessageEntity(
                reviewId = reviewKey,
                ownerScope = initiatingOwnerScope,
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
        if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          _chatConfigState.update {
            it.copy(
              isStreaming = false,
              errorMessage = "Gemini chat response timed out after 90 seconds."
            )
          }
        }
      } catch (e: CancellationException) {
        if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          _chatConfigState.update {
            it.copy(
              isStreaming = false,
              streamingPartialText = "",
              streamingQueries = emptyList(),
              streamingSources = emptyList()
            )
          }
        }
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Multi-turn chat stream failed", e)
        if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
          _chatConfigState.update {
            it.copy(
              isStreaming = false,
              errorMessage = Reviewer2PipelineService.formatThrowableDetails(e)
            )
          }
        }
      } finally {
        activeChatJob = null
      }
    }
  }

  fun clearMultiTurnChatHistory(review: PaperReview?) {
    activeChatJob?.cancel()
    activeChatJob = null
    val reviewKey = review?.id?.ifBlank { "global_workbench_chat" } ?: "global_workbench_chat"
    val ownerScope = currentOwnerScope()
    viewModelScope.launch {
      runSuspendCatching { localRepository.clearChatHistory(reviewKey, ownerScope) }
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
  // CUSTOM / FREE AI API PROVIDERS & API KEYS MANAGEMENT (ACCOUNT-SCOPED SQL)
  // ==========================================================================
  fun saveOrUpdateApiProvider(entry: AiApiProviderEntry) {
    val ownerScope = currentOwnerScope()
    val sessionAtStart = sessionGeneration.get()
    viewModelScope.launch {
      if (entry.protocolType != ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS) {
        val urlValidation = runCatching {
          ExternalApiGatewayClient.validateEndpointUrl(entry.baseUrl, resolveDns = false)
        }
        if (urlValidation.isFailure) {
          val msg = urlValidation.exceptionOrNull()?.localizedMessage
            ?: "Invalid provider endpoint URL. HTTPS is required."
          _apiTestResultMessage.value = "❌ $msg"
          _statusBanner.value = msg
          return@launch
        }
      }
      val updatedEntry = entry.copy(
        enabled = entry.apiKey.isNotBlank() || entry.protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS
      )
      val saveOutcome = runSuspendCatching {
        localRepository.upsertApiProvider(updatedEntry, ownerScope)
      }
      if (!canCommitInferenceResult(ownerScope, sessionAtStart, currentOwnerScope(), sessionGeneration.get())) {
        return@launch
      }
      if (saveOutcome.isFailure) {
        val err = saveOutcome.exceptionOrNull()
        val msg = err?.localizedMessage
          ?: "Encrypted credential storage (Android Keystore) is unavailable. Key was not saved."
        _apiTestResultMessage.value = "❌ $msg"
        _statusBanner.value = msg
        return@launch
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
      _statusBanner.value = "Saved encrypted API key & HTTPS configuration for ${updatedEntry.serviceName}."
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
    val ownerScope = currentOwnerScope()
    viewModelScope.launch {
      runSuspendCatching { localRepository.deleteApiProvider(providerId, ownerScope) }
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
    val initiatingOwnerScope = currentOwnerScope()
    val initiatingSessionGen = sessionGeneration.get()
    viewModelScope.launch {
      _isTestingApiKey.value = true
      _apiTestResultMessage.value = "Testing connection to ${entry.serviceName} (${entry.modelId})..."
      val result = ExternalApiGatewayClient.testProviderConnection(entry)
      if (canCommitInferenceResult(initiatingOwnerScope, initiatingSessionGen, currentOwnerScope(), sessionGeneration.get())) {
        result.onSuccess { message ->
          _apiTestResultMessage.value = "✅ $message"
          _statusBanner.value = "API key verified for ${entry.serviceName}!"
        }.onFailure { error ->
          _apiTestResultMessage.value = "❌ ${error.localizedMessage ?: "Connection test failed."}"
        }
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

  override fun onCleared() {
    activePipelineJob?.cancel()
    activeChatJob?.cancel()
    activeSearchGroundingJob?.cancel()
    activeSyncJob?.cancel()
    activePipelineJob = null
    activeChatJob = null
    activeSearchGroundingJob = null
    activeSyncJob = null
    _formState.update { it.copy(pdfPageBitmaps = emptyList()) }
    super.onCleared()
  }

  companion object {
    private const val PREFS_NAME = "paper_critic_settings"

    /**
     * Authoritative session-commit guard: ensures results from an in-flight inference or sync
     * can ONLY commit if both the account owner scope and monotonic session generation still match.
     */
    internal fun canCommitInferenceResult(
      initiatingOwnerScope: String,
      initiatingSessionGen: Long,
      currentOwnerScope: String,
      currentSessionGen: Long
    ): Boolean = initiatingOwnerScope == currentOwnerScope && initiatingSessionGen == currentSessionGen

    /**
     * Fail-closed AI provider router:
     * - When [AppSettingsState.useCustomApiProvider] is false -> [AiProviderRoute.BuiltInKeyless].
     * - When true, the selected provider MUST exist and have a readable, non-blank API key;
     *   otherwise returns [AiProviderRoute.UnavailableCustomProvider] so the caller aborts
     *   loudly instead of silently falling back to built-in Firebase AI Logic.
     */
    internal fun resolveAiProviderRoute(
      settings: AppSettingsState,
      providers: List<AiApiProviderEntry>
    ): AiProviderRoute {
      if (!settings.useCustomApiProvider) {
        return AiProviderRoute.BuiltInKeyless
      }
      val selected = providers.find { it.id == settings.activeProviderId }
        ?: return AiProviderRoute.UnavailableCustomProvider(
          providerId = settings.activeProviderId,
          reason = "Selected custom API provider is not configured for this account. Configure it in Settings → API Keys or switch back to Built-in Keyless Engine."
        )
      if (selected.protocolType == ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS) {
        return AiProviderRoute.BuiltInKeyless
      }
      if (!selected.enabled || selected.apiKey.isBlank()) {
        return AiProviderRoute.UnavailableCustomProvider(
          providerId = selected.id,
          reason = "Custom API provider '${selected.serviceName}' has no readable API key in this account's encrypted vault. Re-enter the API key in Settings → API Keys or explicitly switch back to Built-in Keyless Engine."
        )
      }
      return AiProviderRoute.CustomProvider(selected)
    }

    internal fun shouldAllowPortfolioSync(
      confidentialLocalOnly: Boolean,
      autoSaveToCloud: Boolean,
      isAutomatic: Boolean
    ): Boolean {
      if (confidentialLocalOnly) return false
      if (isAutomatic && !autoSaveToCloud) return false
      return true
    }

    private fun scopedPrefKey(ownerScope: String, baseKey: String): String =
      "scope_${ownerScope}_$baseKey"

    internal fun loadSettingsFromPrefs(
      prefs: SharedPreferences?,
      ownerScope: String = ANONYMOUS_OWNER_SCOPE
    ): AppSettingsState {
      if (prefs == null) return AppSettingsState()
      val safeScope = normalizeOwnerScope(ownerScope)
      val themeName = prefs.getString("themeMode", ThemeModePreference.IOS_LIGHT.name)
      val themeMode = ThemeModePreference.entries.find { it.name == themeName }
        ?: ThemeModePreference.IOS_LIGHT
      val reviewMode = ReviewMode.fromNameOrDefault(
        prefs.getString("opt_reviewMode", ReviewMode.POST_PUBLICATION_APPRAISAL.name)
      )
      // Provider & cloud-sync privacy settings are strictly scoped per account
      val useCustomProvider = prefs.getBoolean(scopedPrefKey(safeScope, "useCustomApiProvider"), false)
      val autoFallback = prefs.getBoolean(scopedPrefKey(safeScope, "autoFallbackToKeylessEngine"), false)
      val activeProviderId = prefs.getString(
        scopedPrefKey(safeScope, "activeProviderId"),
        DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID
      ) ?: DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID
      val autoSaveToCloud = prefs.getBoolean(scopedPrefKey(safeScope, "autoSaveToCloud"), true)
      val confidentialLocalOnly = prefs.getBoolean(scopedPrefKey(safeScope, "confidentialLocalOnly"), false)

      return AppSettingsState(
        themeMode = themeMode,
        compactDossierMode = prefs.getBoolean("compactDossierMode", false),
        monospaceEquations = prefs.getBoolean("monospaceEquations", true),
        reviewStrictness = prefs.getString("reviewStrictness", "BALANCED_AREA_CHAIR")
          ?: "BALANCED_AREA_CHAIR",
        autoSaveToCloud = autoSaveToCloud,
        confidentialLocalOnly = confidentialLocalOnly,
        enablePdfVisionPages = prefs.getBoolean("enablePdfVisionPages", true),
        enableLegalScreen = prefs.getBoolean("enableLegalScreen", true),
        enableGrimStatsCheck = prefs.getBoolean("enableGrimStatsCheck", true),
        useCustomApiProvider = useCustomProvider,
        autoFallbackToKeylessEngine = autoFallback,
        activeProviderId = activeProviderId,
        defaultVenue = prefs.getString("defaultVenue", "Top-Tier Peer-Reviewed Journal / Conference")
          ?: "Top-Tier Peer-Reviewed Journal / Conference",
        defaultOptions = PipelineOptions(
          isEmpirical = prefs.getBoolean("opt_isEmpirical", true),
          enableMathAudit = prefs.getBoolean("opt_enableMathAudit", true),
          enableCodeAudit = prefs.getBoolean("opt_enableCodeAudit", true),
          enableEditorNote = prefs.getBoolean("opt_enableEditorNote", true),
          enableCopyeditWriterMode = prefs.getBoolean("opt_enableCopyeditWriterMode", true),
          reviewMode = reviewMode
        ),
        reportTextScale = prefs.getFloat("reportTextScale", 1.0f)
      )
    }

    internal fun saveSettingsToPrefs(
      prefs: SharedPreferences?,
      state: AppSettingsState,
      ownerScope: String = ANONYMOUS_OWNER_SCOPE
    ) {
      val safeScope = normalizeOwnerScope(ownerScope)
      prefs?.edit()?.apply {
        putString("themeMode", state.themeMode.name)
        putBoolean("compactDossierMode", state.compactDossierMode)
        putBoolean("monospaceEquations", state.monospaceEquations)
        putString("reviewStrictness", state.reviewStrictness)
        putBoolean(scopedPrefKey(safeScope, "autoSaveToCloud"), state.autoSaveToCloud)
        putBoolean(scopedPrefKey(safeScope, "confidentialLocalOnly"), state.confidentialLocalOnly)
        putBoolean("enablePdfVisionPages", state.enablePdfVisionPages)
        putBoolean("enableLegalScreen", state.enableLegalScreen)
        putBoolean("enableGrimStatsCheck", state.enableGrimStatsCheck)
        putBoolean(scopedPrefKey(safeScope, "useCustomApiProvider"), state.useCustomApiProvider)
        putBoolean(scopedPrefKey(safeScope, "autoFallbackToKeylessEngine"), state.autoFallbackToKeylessEngine)
        putString(scopedPrefKey(safeScope, "activeProviderId"), state.activeProviderId)
        putString("defaultVenue", state.defaultVenue)
        putBoolean("opt_isEmpirical", state.defaultOptions.isEmpirical)
        putBoolean("opt_enableMathAudit", state.defaultOptions.enableMathAudit)
        putBoolean("opt_enableCodeAudit", state.defaultOptions.enableCodeAudit)
        putBoolean("opt_enableEditorNote", state.defaultOptions.enableEditorNote)
        putBoolean("opt_enableCopyeditWriterMode", state.defaultOptions.enableCopyeditWriterMode)
        putString("opt_reviewMode", state.defaultOptions.reviewMode.name)
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
