package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.auth.GoogleCloudSyncDialog
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOut
import com.example.ui.components.IosSquircleIconBadge
import com.example.ui.components.IosSquircleTabItem
import com.example.ui.components.LocalNeumorphicPalette
import com.example.ui.components.SquircleShape
import com.example.ui.components.neuRaised
import com.example.ui.components.neuRecessed
import com.example.ui.screens.ArchiveScreen
import com.example.ui.screens.IosSettingsScreen
import com.example.ui.screens.OutputTabScreen
import com.example.ui.screens.WorkbenchScreen
import com.example.ui.theme.AmberGold400
import com.example.ui.theme.CupertinoBlue
import com.example.ui.theme.CupertinoGreen
import com.example.ui.theme.CupertinoIndigo
import com.example.ui.theme.CupertinoTeal
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SlateNavy900
import com.example.ui.viewmodel.ArchiveUiState
import com.example.ui.viewmodel.PaperCriticViewModel
import com.example.ui.viewmodel.PipelineExecutionState
import com.example.ui.viewmodel.ThemeModePreference
import com.example.util.configureAppCheck
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow

private sealed interface IncomingPaperIntent {
  data class DocumentUri(val uri: Uri) : IncomingPaperIntent
  data class SharedText(val subject: String?, val text: String) : IncomingPaperIntent
}

class MainActivity : ComponentActivity() {

  private val pendingIncomingPaper = MutableStateFlow<IncomingPaperIntent?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureAppCheck(this, intent)
    enableEdgeToEdge()
    captureIncomingPaperIntent(intent)

    setContent {
      val context = LocalContext.current
      val scope = rememberCoroutineScope()
      val credentialManager = remember(context) { CredentialManager.create(context) }

      val currentUser by Firebase.auth.authStateFlow().collectAsStateWithLifecycle(
        initialValue = Firebase.auth.currentUser
      )

      // Attempt silent CredentialManager Google Sign-In in the background without blocking the UI
      LaunchedEffect(Unit) {
        if (Firebase.auth.currentUser == null) {
          attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {},
            onUnauthenticated = {},
            scope = scope
          )
        }
      }

      val viewModel: PaperCriticViewModel = viewModel(
        factory = PaperCriticViewModel.factory(currentUser?.uid)
      )

      LaunchedEffect(currentUser?.uid) {
        viewModel.onAuthenticatedUserChanged(currentUser?.uid)
      }

      // Consume incoming Android Share / Open-With intents seamlessly
      val incoming by pendingIncomingPaper.collectAsStateWithLifecycle()
      LaunchedEffect(incoming) {
        val payload = incoming ?: return@LaunchedEffect
        pendingIncomingPaper.value = null
        when (payload) {
          is IncomingPaperIntent.DocumentUri -> {
            viewModel.selectMainTab(0)
            viewModel.importManuscriptFromUri(context, payload.uri)
          }
          is IncomingPaperIntent.SharedText -> {
            viewModel.importSharedManuscriptText(payload.subject, payload.text)
          }
        }
      }

      // Keep screen awake ONLY while the multi-pass 30-stage AI pipeline is actively running
      val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
      val isPipelineRunning = pipelineState is PipelineExecutionState.Running
      DisposableEffect(isPipelineRunning) {
        if (isPipelineRunning) {
          window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }

      val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
      val systemDark = isSystemInDarkTheme()
      val useDarkTheme = when (settingsState.themeMode) {
        ThemeModePreference.SYSTEM -> systemDark
        ThemeModePreference.IOS_LIGHT -> false
        ThemeModePreference.IOS_DARK -> true
      }

      MyApplicationTheme(darkTheme = useDarkTheme) {
        PaperCriticAuthenticatedShell(
          viewModel = viewModel,
          isSignedIn = currentUser != null,
          userDisplayName = currentUser?.displayName ?: "",
          userEmail = currentUser?.email ?: ""
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    captureIncomingPaperIntent(intent)
  }

  private fun captureIncomingPaperIntent(incomingIntent: Intent?) {
    if (incomingIntent == null) return
    when (incomingIntent.action) {
      Intent.ACTION_SEND -> {
        val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
          @Suppress("DEPRECATION")
          incomingIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
        if (streamUri != null) {
          pendingIncomingPaper.value = IncomingPaperIntent.DocumentUri(streamUri)
        } else {
          val sharedText = incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
          if (!sharedText.isNullOrBlank()) {
            val subject = incomingIntent.getStringExtra(Intent.EXTRA_SUBJECT)
            pendingIncomingPaper.value = IncomingPaperIntent.SharedText(subject, sharedText)
          }
        }
      }
      Intent.ACTION_VIEW -> {
        val dataUri = incomingIntent.data
        if (dataUri != null) {
          pendingIncomingPaper.value = IncomingPaperIntent.DocumentUri(dataUri)
        }
      }
    }
  }
}

@Composable
fun PaperCriticAuthenticatedShell(
  viewModel: PaperCriticViewModel,
  isSignedIn: Boolean,
  userDisplayName: String,
  userEmail: String
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  val activeMainTab by viewModel.activeMainTab.collectAsStateWithLifecycle()
  val settingsState by viewModel.settingsState.collectAsStateWithLifecycle()
  val formState by viewModel.formState.collectAsStateWithLifecycle()
  val preFlightReport by viewModel.preFlightReport.collectAsStateWithLifecycle()
  val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
  val archiveState by viewModel.archiveState.collectAsStateWithLifecycle()
  val selectedDossier by viewModel.selectedDossier.collectAsStateWithLifecycle()
  val followUpState by viewModel.followUpState.collectAsStateWithLifecycle()
  val refereeChatState by viewModel.refereeChatState.collectAsStateWithLifecycle()
  val searchGroundingState by viewModel.searchGroundingState.collectAsStateWithLifecycle()
  val sqlCachedReviewCount by viewModel.sqlCachedReviewCount.collectAsStateWithLifecycle()
  val offlineCachedIds by viewModel.offlineCachedIds.collectAsStateWithLifecycle()
  val sqlTotalChatMessageCount by viewModel.sqlTotalChatMessageCount.collectAsStateWithLifecycle()
  val apiProviders by viewModel.apiProviders.collectAsStateWithLifecycle()
  val isTestingApiKey by viewModel.isTestingApiKey.collectAsStateWithLifecycle()
  val apiTestResultMessage by viewModel.apiTestResultMessage.collectAsStateWithLifecycle()
  val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
  val unsyncedLocalCount by viewModel.unsyncedLocalCount.collectAsStateWithLifecycle()
  val isSyncingPortfolio by viewModel.isSyncingPortfolio.collectAsStateWithLifecycle()

  var showCloudSyncDialog by remember { mutableStateOf(false) }

  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(statusBanner) {
    statusBanner?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      viewModel.dismissStatusBanner()
    }
  }

  val allReviews = when (val s = archiveState) {
    is ArchiveUiState.Success -> s.reviews
    else -> emptyList()
  }

  if (showCloudSyncDialog) {
    GoogleCloudSyncDialog(
      isSignedIn = isSignedIn,
      userDisplayName = userDisplayName,
      userEmail = userEmail,
      syncedCount = allReviews.count { !it.id.startsWith("local_") && it.id.isNotBlank() },
      unsyncedLocalCount = unsyncedLocalCount,
      isSyncing = isSyncingPortfolio,
      onSyncNow = viewModel::syncPortfolioToCloud,
      onSignOut = {
        signOut(credentialManager = credentialManager, scope = scope)
      },
      onDismiss = { showCloudSyncDialog = false }
    )
  }

  BackHandler(enabled = activeMainTab != 0) {
    viewModel.selectMainTab(0)
  }

  val tabs = listOf(
    Triple(0, "Upload", Icons.Default.UploadFile),
    Triple(1, "Output", Icons.Default.Analytics),
    Triple(2, "Review History", Icons.Default.History),
    Triple(3, "Settings", Icons.Default.Settings)
  )

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isExpandedScreen = maxWidth >= 700.dp

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        val systemDark = isSystemInDarkTheme()
        val isDarkActive = when (settingsState.themeMode) {
          ThemeModePreference.SYSTEM -> systemDark
          ThemeModePreference.IOS_LIGHT -> false
          ThemeModePreference.IOS_DARK -> true
        }
        val usesCustomProvider = settingsState.useCustomApiProvider
        Column {
          IosTopHeaderBar(
            activeTab = activeMainTab,
            reviewCount = allReviews.size,
            isSignedIn = isSignedIn,
            isDarkActive = isDarkActive,
            usesCustomProvider = usesCustomProvider,
            onToggleTheme = {
              val nextMode = if (isDarkActive) ThemeModePreference.IOS_LIGHT else ThemeModePreference.IOS_DARK
              viewModel.updateSettings { it.copy(themeMode = nextMode) }
            },
            onOpenApiSettings = { viewModel.selectMainTab(3) },
            onOpenAccountSettings = { showCloudSyncDialog = true }
          )
          val runningState = pipelineState as? PipelineExecutionState.Running
          AnimatedVisibility(visible = runningState != null && activeMainTab != 0) {
            if (runningState != null) {
              GlobalLivePipelineBanner(
                stageCode = runningState.stageCode,
                stageLabel = runningState.stageLabel,
                progress = runningState.progress,
                onReturnToWorkbench = { viewModel.selectMainTab(0) },
                onCancel = viewModel::cancelRunningPipeline
              )
            }
          }
        }
      },
      bottomBar = {
        if (!isExpandedScreen) {
          IosSquircleBottomTabBar(
            activeTab = activeMainTab,
            hasOutputBadge = selectedDossier != null || allReviews.isNotEmpty(),
            libraryCount = allReviews.size,
            onSelectTab = viewModel::selectMainTab
          )
        }
      }
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
      ) {
        if (isExpandedScreen) {
          NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface
          ) {
            Spacer(modifier = Modifier.height(12.dp))
            tabs.forEach { (idx, label, icon) ->
              NavigationRailItem(
                selected = activeMainTab == idx,
                onClick = { viewModel.selectMainTab(idx) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
                modifier = Modifier.testTag("nav_rail_$idx")
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
        ) {
          when (activeMainTab) {
            0 -> {
              WorkbenchScreen(
                formState = formState,
                preFlight = preFlightReport,
                pipelineState = pipelineState,
                onInputModeChange = viewModel::updateInputModeIndex,
                onTitleChange = viewModel::updateTitle,
                onAuthorsChange = viewModel::updateAuthors,
                onYearChange = viewModel::updateYear,
                onVenueChange = viewModel::updateVenue,
                onManuscriptTextChange = viewModel::updateManuscriptText,
                onReplicationCodeChange = viewModel::updateReplicationCodeText,
                onOptionsChange = viewModel::updatePipelineOptions,
                onSelectBenchmark = viewModel::loadBenchmarkManuscript,
                onClearForm = viewModel::clearManuscriptForm,
                onImportDocumentUri = { uri -> viewModel.importManuscriptFromUri(context, uri) },
                onImportReplicationCodeUri = { uri -> viewModel.importReplicationCodeFromUri(context, uri) },
                onRunPipeline = viewModel::runAdversarialReviewAndSave,
                onCancelPipeline = viewModel::cancelRunningPipeline,
                onOpenCompletedDossier = viewModel::openDossier
              )
            }

            1 -> {
              OutputTabScreen(
                review = selectedDossier,
                allReviews = allReviews,
                offlineCachedIds = offlineCachedIds,
                followUpState = followUpState,
                refereeChatState = refereeChatState,
                searchGroundingState = searchGroundingState,
                reportTextScale = settingsState.reportTextScale,
                onSelectReview = viewModel::openDossier,
                onGoToAnalyze = { viewModel.selectMainTab(0) },
                onToggleBookmark = viewModel::toggleBookmark,
                onSaveOfflineCache = viewModel::cacheReviewOfflineNow,
                onSavePersonalNotes = viewModel::savePersonalNotes,
                onDeleteReview = viewModel::deleteReview,
                onAskFollowUp = viewModel::askFollowUpQuestion,
                onSendChatMessage = viewModel::sendMultiTurnChatMessage,
                onClearChatHistory = viewModel::clearMultiTurnChatHistory,
                onUpdateChatPersona = viewModel::updateChatPersona,
                onUpdateChatModelTier = viewModel::updateChatModelTier,
                onUpdateChatSearchGrounding = viewModel::updateChatSearchGrounding,
                onRunGoogleSearchVerification = viewModel::runStage03bGoogleSearchVerification
              )
            }

            2 -> {
              ArchiveScreen(
                archiveState = archiveState,
                offlineCachedIds = offlineCachedIds,
                isSignedIn = isSignedIn,
                userEmail = userEmail,
                unsyncedLocalCount = unsyncedLocalCount,
                sqlCachedCount = sqlCachedReviewCount,
                sqlChatMessagesCount = sqlTotalChatMessageCount,
                isSyncingPortfolio = isSyncingPortfolio,
                onSyncPortfolioNow = viewModel::syncPortfolioToCloud,
                onOpenReview = viewModel::openDossier,
                onToggleBookmark = viewModel::toggleBookmark,
                onDeleteReview = viewModel::deleteReview,
                onGoToWorkbench = { viewModel.selectMainTab(0) }
              )
            }

            3 -> {
              IosSettingsScreen(
                settingsState = settingsState,
                apiProviders = apiProviders,
                isTestingApiKey = isTestingApiKey,
                apiTestResultMessage = apiTestResultMessage,
                isSignedIn = isSignedIn,
                userDisplayName = userDisplayName,
                userEmail = userEmail,
                syncedReviewCount = allReviews.count { !it.id.startsWith("local_") && it.id.isNotBlank() },
                unsyncedLocalCount = unsyncedLocalCount,
                isSyncingPortfolio = isSyncingPortfolio,
                onSyncPortfolioNow = viewModel::syncPortfolioToCloud,
                onUpdateSettings = viewModel::updateSettings,
                onSelectActiveProvider = viewModel::selectActiveApiProvider,
                onSaveProvider = viewModel::saveOrUpdateApiProvider,
                onAddCustomProvider = viewModel::addCustomApiProvider,
                onDeleteCustomProvider = viewModel::deleteCustomApiProvider,
                onTestProviderConnection = viewModel::testApiProviderKey,
                onClearDraft = {
                  viewModel.clearManuscriptForm()
                  viewModel.selectMainTab(0)
                },
                onSignOut = {
                  signOut(credentialManager = credentialManager, scope = scope)
                }
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun IosTopHeaderBar(
  activeTab: Int,
  reviewCount: Int,
  isSignedIn: Boolean,
  isDarkActive: Boolean,
  usesCustomProvider: Boolean,
  onToggleTheme: () -> Unit,
  onOpenApiSettings: () -> Unit,
  onOpenAccountSettings: () -> Unit
) {
  val palette = LocalNeumorphicPalette.current
  val subtitle = when (activeTab) {
    0 -> "30-STAGE PEER REVIEW"
    1 -> "REFEREE DOSSIER"
    2 -> "HISTORY ($reviewCount)"
    else -> "PREFERENCES"
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(palette.canvas)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        IosSquircleIconBadge(
          icon = Icons.Default.Gavel,
          tint = MaterialTheme.colorScheme.primary,
          size = 42.dp,
          cornerRadius = 21.dp
        )
        Column {
          Text(
            text = "Paper Critic",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.25).sp,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.5.sp,
            letterSpacing = 0.85.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
      ) {
        // Active AI Engine Raised Clay Pill
        val engineColor = if (usesCustomProvider) CupertinoIndigo else CupertinoTeal
        val engineShape = SquircleShape(cornerRadius = 12.dp, smoothing = 0.86f)
        Row(
          modifier = Modifier
            .neuRaised(
              cornerRadius = 12.dp,
              elevation = 4.5.dp,
              surfaceColor = palette.surfaceRaised
            )
            .clip(engineShape)
            .clickable { onOpenApiSettings() }
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag("top_bar_api_engine_badge"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Key,
            contentDescription = "AI Engine",
            tint = engineColor,
            modifier = Modifier.size(12.dp)
          )
          Text(
            text = if (usesCustomProvider) "CUSTOM" else "KEYLESS",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = engineColor
          )
        }

        // Cloud Sync Raised Clay Pill
        val badgeColor = if (isSignedIn) CupertinoGreen else MaterialTheme.colorScheme.primary
        val badgeShape = SquircleShape(cornerRadius = 12.dp, smoothing = 0.86f)
        Row(
          modifier = Modifier
            .neuRaised(
              cornerRadius = 12.dp,
              elevation = 4.5.dp,
              surfaceColor = palette.surfaceRaised
            )
            .clip(badgeShape)
            .clickable { onOpenAccountSettings() }
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag("top_bar_cloud_sync_badge"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Icon(
            imageVector = if (isSignedIn) Icons.Default.CloudDone else Icons.Default.AccountCircle,
            contentDescription = if (isSignedIn) "Cloud Synced" else "Sign In",
            tint = badgeColor,
            modifier = Modifier.size(13.dp)
          )
          Text(
            text = if (isSignedIn) "SYNCED" else "SIGN IN",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = badgeColor
          )
        }

        // Tactile Circular Light / Dark Theme Medallion (48dp accessible touch target)
        val themeBtnShape = SquircleShape(cornerRadius = 19.dp, smoothing = 0.86f)
        IconButton(
          onClick = onToggleTheme,
          modifier = Modifier
            .size(48.dp)
            .testTag("top_bar_theme_toggle")
        ) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .neuRaised(
                cornerRadius = 19.dp,
                elevation = 4.5.dp,
                surfaceColor = palette.surfaceRaised
              )
              .clip(themeBtnShape),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (isDarkActive) Icons.Default.LightMode else Icons.Default.DarkMode,
              contentDescription = if (isDarkActive) "Switch to Light Mode" else "Switch to Dark Mode",
              tint = if (isDarkActive) AmberGold400 else MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(17.dp)
            )
          }
        }
      }
    }
  }
}

@Composable
private fun IosSquircleBottomTabBar(
  activeTab: Int,
  hasOutputBadge: Boolean,
  libraryCount: Int,
  onSelectTab: (Int) -> Unit
) {
  val palette = LocalNeumorphicPalette.current

  // Directly modeled on the reference Neumorphic bottom bar:
  // 4 independent sculpted clay tiles on #E0E5EC where inactive = raised & active = deeply carved inset.
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(palette.canvas)
      .navigationBarsPadding()
      .padding(horizontal = 16.dp, vertical = 12.dp)
      .testTag("bottom_navigation_bar")
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IosSquircleTabItem(
        selected = activeTab == 0,
        label = "Upload",
        icon = Icons.Default.UploadFile,
        onClick = { onSelectTab(0) },
        modifier = Modifier.testTag("nav_tab_0")
      )
      IosSquircleTabItem(
        selected = activeTab == 1,
        label = "Output",
        icon = Icons.Default.Analytics,
        badgeText = if (hasOutputBadge) "✓" else null,
        onClick = { onSelectTab(1) },
        modifier = Modifier.testTag("nav_tab_1")
      )
      IosSquircleTabItem(
        selected = activeTab == 2,
        label = "History",
        icon = Icons.Default.History,
        badgeText = if (libraryCount > 0) libraryCount.toString() else null,
        onClick = { onSelectTab(2) },
        modifier = Modifier.testTag("nav_tab_2")
      )
      IosSquircleTabItem(
        selected = activeTab == 3,
        label = "Settings",
        icon = Icons.Default.Settings,
        onClick = { onSelectTab(3) },
        modifier = Modifier.testTag("nav_tab_3")
      )
    }
  }
}

@Composable
private fun GlobalLivePipelineBanner(
  stageCode: String,
  stageLabel: String,
  progress: Float,
  onReturnToWorkbench: () -> Unit,
  onCancel: () -> Unit
) {
  val palette = LocalNeumorphicPalette.current
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(palette.canvas)
      .padding(horizontal = 16.dp, vertical = 4.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .neuRecessed(
          cornerRadius = 16.dp,
          depth = 4.dp,
          cavityColor = palette.surfaceRecessed
        )
        .clip(SquircleShape(cornerRadius = 16.dp, smoothing = 0.86f))
        .clickable { onReturnToWorkbench() }
        .padding(horizontal = 14.dp, vertical = 9.dp)
        .testTag("global_live_pipeline_banner"),
      verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
          Text(
            text = "STAGE [$stageCode]",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary
          )
          Text(
            text = stageLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = "${(progress * 100).toInt()}%",
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary
          )
          IconButton(
            onClick = onCancel,
            modifier = Modifier
              .size(48.dp)
              .testTag("global_pipeline_cancel_button")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Cancel running review",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(16.dp)
            )
          }
        }
      }
      com.example.ui.components.NeuProgressBar(
        progress = progress,
        height = 9.dp,
        fillColor = MaterialTheme.colorScheme.primary
      )
    }
  }
}
