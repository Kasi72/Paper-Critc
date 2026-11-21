package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.ChatbotRolePersona
import com.example.data.ai.GeminiModelTier
import com.example.data.ai.GroundedWebSource
import com.example.data.model.PaperReview
import com.example.ui.components.IosCredibilityArcGauge
import com.example.ui.components.IosGroupedCard
import com.example.ui.components.IosInsetDivider
import com.example.ui.components.IosSecondarySquircleButton
import com.example.ui.components.IosSegmentedControl
import com.example.ui.components.IosSettingToggleRow
import com.example.ui.components.IosSquircleButton
import com.example.ui.components.IosSquircleIconBadge
import com.example.ui.components.IosSubTabBar
import com.example.ui.components.IosSubTabEntry
import com.example.ui.components.LocalNeumorphicPalette
import com.example.ui.components.SquircleShape
import com.example.ui.components.neuRaised
import com.example.ui.components.neuRecessed
import com.example.ui.theme.CupertinoAmber
import com.example.ui.theme.CupertinoBlue
import com.example.ui.theme.CupertinoGreen
import com.example.ui.theme.CupertinoIndigo
import com.example.ui.theme.CupertinoRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.FollowUpQaState
import com.example.ui.viewmodel.RefereeChatMessageUi
import com.example.ui.viewmodel.RefereeChatUiState
import com.example.ui.viewmodel.SearchGroundingUiState
import com.example.util.CalloutTone
import com.example.util.DossierFormattingEngine
import com.example.util.PdfReportExporter
import com.example.util.ReportBlock
import com.example.util.SeverityLevel
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun OutputTabScreen(
  review: PaperReview?,
  allReviews: List<PaperReview>,
  offlineCachedIds: Set<String> = emptySet(),
  followUpState: FollowUpQaState,
  refereeChatState: RefereeChatUiState = RefereeChatUiState(),
  searchGroundingState: SearchGroundingUiState = SearchGroundingUiState.Idle,
  reportTextScale: Float,
  onSelectReview: (PaperReview) -> Unit,
  onGoToAnalyze: () -> Unit,
  onToggleBookmark: (PaperReview) -> Unit,
  onSaveOfflineCache: (PaperReview) -> Unit = {},
  onSavePersonalNotes: (PaperReview, String) -> Unit,
  onDeleteReview: (String) -> Unit,
  onAskFollowUp: (PaperReview, String) -> Unit,
  onSendChatMessage: (PaperReview?, String) -> Unit = { rev, msg ->
    if (rev != null) onAskFollowUp(rev, msg)
  },
  onClearChatHistory: (PaperReview?) -> Unit = {},
  onUpdateChatPersona: (ChatbotRolePersona) -> Unit = {},
  onUpdateChatModelTier: (GeminiModelTier) -> Unit = {},
  onUpdateChatSearchGrounding: (Boolean) -> Unit = {},
  onRunGoogleSearchVerification: (PaperReview) -> Unit = {}
) {
  val activeReview = review ?: allReviews.firstOrNull()
  var emptyModeTab by rememberSaveable { mutableIntStateOf(0) }
  var chatDraft by rememberSaveable { mutableStateOf("") }

  if (activeReview == null) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        IosSegmentedControl(
          items = listOf("30-Stage Dossier Output", "Multi-Turn Gemini Chat"),
          selectedIndex = emptyModeTab,
          onSelect = { emptyModeTab = it }
        )
      }

      if (emptyModeTab == 0) {
        item {
          IosGroupedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              IosSquircleIconBadge(
                icon = Icons.Default.Analytics,
                tint = CupertinoBlue,
                size = 48.dp,
                cornerRadius = 10.dp
              )
              Text(
                text = "No Paper Analysed Yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Import a PDF, Word (.docx/.doc), or Text manuscript in the Upload tab to generate a complete 30-stage critical referee report, or switch above to chat directly with the Multi-Turn Gemini Referee.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              IosSquircleButton(
                text = "Go to Upload Tab",
                onClick = onGoToAnalyze,
                icon = Icons.Default.AutoAwesome,
                containerColor = CupertinoBlue,
                cornerRadius = 10.dp,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("output_empty_go_analyze_button")
              )
            }
          }
        }
      } else {
        item {
          MultiTurnGeminiChatbotCard(
            chatState = refereeChatState,
            inputDraft = chatDraft,
            onInputDraftChange = { chatDraft = it },
            onSendMessage = {
              if (chatDraft.isNotBlank()) {
                onSendChatMessage(null, chatDraft)
                chatDraft = ""
              }
            },
            onClearHistory = { onClearChatHistory(null) },
            onSelectPersona = onUpdateChatPersona,
            onSelectModelTier = onUpdateChatModelTier,
            onToggleSearchGrounding = onUpdateChatSearchGrounding
          )
        }
      }
    }
    return
  }

  DossierDetailView(
    review = activeReview,
    recentReviews = allReviews.take(6),
    isCachedOffline = activeReview.id in offlineCachedIds,
    followUpState = followUpState,
    refereeChatState = refereeChatState,
    searchGroundingState = searchGroundingState,
    reportTextScale = reportTextScale,
    onSelectReview = onSelectReview,
    onBackToAnalyze = onGoToAnalyze,
    onToggleBookmark = { onToggleBookmark(activeReview) },
    onSaveOfflineCache = { onSaveOfflineCache(activeReview) },
    onSavePersonalNotes = { notes -> onSavePersonalNotes(activeReview, notes) },
    onDelete = { onDeleteReview(activeReview.id) },
    onAskFollowUp = { q -> onAskFollowUp(activeReview, q) },
    onSendChatMessage = { msg -> onSendChatMessage(activeReview, msg) },
    onClearChatHistory = { onClearChatHistory(activeReview) },
    onUpdateChatPersona = onUpdateChatPersona,
    onUpdateChatModelTier = onUpdateChatModelTier,
    onUpdateChatSearchGrounding = onUpdateChatSearchGrounding,
    onRunGoogleSearchVerification = { onRunGoogleSearchVerification(activeReview) }
  )
}

@Composable
fun DossierDetailView(
  review: PaperReview,
  recentReviews: List<PaperReview> = emptyList(),
  isCachedOffline: Boolean = true,
  followUpState: FollowUpQaState,
  refereeChatState: RefereeChatUiState = RefereeChatUiState(),
  searchGroundingState: SearchGroundingUiState = SearchGroundingUiState.Idle,
  reportTextScale: Float = 1.0f,
  onSelectReview: (PaperReview) -> Unit = {},
  onBackToAnalyze: () -> Unit,
  onToggleBookmark: () -> Unit,
  onSaveOfflineCache: () -> Unit = {},
  onSavePersonalNotes: (String) -> Unit,
  onDelete: () -> Unit,
  onAskFollowUp: (String) -> Unit,
  onSendChatMessage: (String) -> Unit = onAskFollowUp,
  onClearChatHistory: () -> Unit = {},
  onUpdateChatPersona: (ChatbotRolePersona) -> Unit = {},
  onUpdateChatModelTier: (GeminiModelTier) -> Unit = {},
  onUpdateChatSearchGrounding: (Boolean) -> Unit = {},
  onRunGoogleSearchVerification: () -> Unit = {}
) {
  BackHandler { onBackToAnalyze() }

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var isExportingPdf by remember { mutableStateOf(false) }
  var selectedSectionTab by rememberSaveable { mutableIntStateOf(0) }
  var overviewSubSegment by rememberSaveable { mutableIntStateOf(0) }
  var redBlueSubSegment by rememberSaveable { mutableIntStateOf(0) }
  var mathCodeSubSegment by rememberSaveable { mutableIntStateOf(0) }
  var editorCopySubSegment by rememberSaveable { mutableIntStateOf(0) }

  var followUpInput by rememberSaveable { mutableStateOf("") }
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var showDossierSearch by rememberSaveable { mutableStateOf(false) }
  var dossierSearchQuery by rememberSaveable { mutableStateOf("") }
  var authorNotesDraft by remember(review.id, review.personalNotes) {
    mutableStateOf(review.personalNotes)
  }

  val dossierSearchMatches = remember(review, dossierSearchQuery) {
    DossierFormattingEngine.searchDossier(review, dossierSearchQuery)
  }

  if (showDeleteConfirm) {
    val cleanTitle = DossierFormattingEngine.normalizeTitle(review.title)
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("Delete Referee Dossier?", fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Permanently delete \"$cleanTitle\" from your offline Room cache and cloud portfolio? This cannot be undone."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteConfirm = false
            onDelete()
          },
          modifier = Modifier.testTag("confirm_delete_dossier_button")
        ) {
          Text("Delete", color = CupertinoRed, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showDeleteConfirm = false },
          modifier = Modifier.testTag("cancel_delete_dossier_button")
        ) {
          Text("Cancel")
        }
      }
    )
  }

  val sectionTabs = listOf(
    IosSubTabEntry("1. Rubric & Verdict", Icons.Default.Gavel, "${review.credibilityScore}%"),
    IosSubTabEntry("2. Red/Blue & Search", Icons.Default.Security, "01a–03c"),
    IosSubTabEntry("3. Quant & Code", Icons.Default.Functions, "01e/02a"),
    IosSubTabEntry("4. Editor & Copy", Icons.Default.EditNote, "08b/09c"),
    IosSubTabEntry(
      "5. Gemini Chat",
      Icons.Default.Forum,
      if (refereeChatState.messages.isNotEmpty()) "${refereeChatState.messages.size}" else "AI"
    ),
    IosSubTabEntry("6. Export / PDF", Icons.Default.PictureAsPdf, "PDF")
  )

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("dossier_detail_list"),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Quick switcher if user has multiple analysed papers
    if (recentReviews.size > 1) {
      item {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
          Text(
            text = "ANALYSED PAPERS (SQL + CLOUD PORTFOLIO)",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            recentReviews.forEach { itemReview ->
              val isSelected = itemReview.id == review.id && review.id.isNotBlank()
              val squircle = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
              Box(
                modifier = Modifier
                  .clip(squircle)
                  .background(
                    if (isSelected) CupertinoBlue
                    else MaterialTheme.colorScheme.surface
                  )
                  .border(
                    width = 0.75.dp,
                    color = if (isSelected) CupertinoBlue
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = squircle
                  )
                  .clickable { onSelectReview(itemReview) }
                  .padding(horizontal = 11.dp, vertical = 7.dp)
              ) {
                val chipTitle = DossierFormattingEngine.normalizeTitle(itemReview.title)
                Text(
                  text = chipTitle.take(28) + if (chipTitle.length > 28) "…" else "",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                )
              }
            }
          }
        }
      }
    }

    // Top Dossier Verdict Card (Sharp iOS Squircle Card)
    item {
      IosGroupedCard {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
          ) {
            val backShape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
            Box(
              modifier = Modifier
                .size(34.dp)
                .clip(backShape)
                .background(CupertinoBlue.copy(alpha = 0.12f))
                .border(0.6.dp, CupertinoBlue.copy(alpha = 0.30f), backShape)
                .clickable { onBackToAnalyze() }
                .testTag("dossier_back_button"),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "New Analysis",
                tint = CupertinoBlue,
                modifier = Modifier.size(17.dp)
              )
            }
            VerdictSquircleBadge(decision = review.verdict)
            TrackSquircleBadge(isEmpirical = review.isEmpirical)
          }

          Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            IconButton(
              onClick = { showDossierSearch = !showDossierSearch },
              modifier = Modifier.testTag("dossier_search_toggle_button")
            ) {
              Icon(
                imageVector = if (showDossierSearch) Icons.Default.Close else Icons.Default.Search,
                contentDescription = "Search inside dossier",
                tint = if (showDossierSearch) CupertinoBlue else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            IconButton(
              onClick = {
                if (!isExportingPdf) {
                  isExportingPdf = true
                  scope.launch {
                    val res = PdfReportExporter.exportAndSharePdf(context, review)
                    isExportingPdf = false
                    res.onFailure { err ->
                      Toast.makeText(
                        context,
                        "PDF export failed: ${err.localizedMessage}",
                        Toast.LENGTH_SHORT
                      ).show()
                    }
                  }
                }
              },
              enabled = !isExportingPdf,
              modifier = Modifier.testTag("share_pdf_dossier_button")
            ) {
              Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = "Export & Share PDF",
                tint = CupertinoRed
              )
            }
            IconButton(
              onClick = onToggleBookmark,
              modifier = Modifier.testTag("pin_dossier_button")
            ) {
              Icon(
                imageVector = if (review.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = "Bookmark Review",
                tint = if (review.bookmarked) CupertinoBlue else MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            IconButton(
              onClick = {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_SUBJECT, "Referee Report: ${DossierFormattingEngine.normalizeTitle(review.title)}")
                  putExtra(Intent.EXTRA_TEXT, review.formattedDossier)
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share Referee Report"))
              },
              modifier = Modifier.testTag("share_dossier_button")
            ) {
              Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share Report",
                tint = CupertinoBlue
              )
            }
            if (review.id.isNotBlank()) {
              IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.testTag("delete_dossier_button")
              ) {
                Icon(
                  imageVector = Icons.Default.DeleteOutline,
                  contentDescription = "Delete Report",
                  tint = CupertinoRed
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Room Offline SQLite Status Chip
        val cachePillShape = SquircleShape(cornerRadius = 6.dp, smoothing = 0.88f)
        val cacheColor = if (isCachedOffline) CupertinoGreen else CupertinoAmber
        Row(
          modifier = Modifier
            .clip(cachePillShape)
            .background(cacheColor.copy(alpha = 0.11f))
            .border(0.6.dp, cacheColor.copy(alpha = 0.35f), cachePillShape)
            .clickable { onSaveOfflineCache() }
            .padding(horizontal = 8.dp, vertical = 3.5.dp)
            .testTag("dossier_offline_cache_badge"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          Icon(
            imageVector = Icons.Default.OfflinePin,
            contentDescription = "Offline Room Cache Status",
            tint = cacheColor,
            modifier = Modifier.size(13.dp)
          )
          Text(
            text = if (isCachedOffline) "ROOM SQLITE CACHED (OFFLINE READY)" else "TAP TO CACHE FOR OFFLINE ACCESS",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = cacheColor
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        val normalizedDisplayTitle = remember(review.title) {
          DossierFormattingEngine.normalizeTitle(review.title)
        }
        val cleanCitationLine = remember(review.authors, review.publicationYear, review.venueOrCitation) {
          val parts = buildList {
            val auth = review.authors.trim()
            val yr = review.publicationYear.trim()
            if (auth.isNotBlank() && yr.isNotBlank()) add("$auth ($yr)")
            else if (auth.isNotBlank()) add(auth)
            else if (yr.isNotBlank()) add("($yr)")
            val venue = review.venueOrCitation.trim()
            if (venue.isNotBlank()) add(venue)
          }
          parts.joinToString(" • ").ifBlank { "Uploaded Academic Manuscript" }
        }

        Text(
          text = normalizedDisplayTitle,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
          text = cleanCitationLine,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        val rubric = remember(review.id, review.editorNote, review.credibilityScore) {
          review.toRubric()
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          IosCredibilityArcGauge(
            score = rubric.overallCredibilityScore,
            label = "CREDIBILITY",
            size = 76.dp
          )
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              IosScorePill(
                label = "METHODOLOGY",
                score = rubric.methodology,
                max = 10,
                modifier = Modifier.weight(1f)
              )
              IosScorePill(
                label = "SIGNIFICANCE",
                score = rubric.significance,
                max = 10,
                modifier = Modifier.weight(1f)
              )
              IosScorePill(
                label = "CLARITY",
                score = rubric.clarity,
                max = 10,
                modifier = Modifier.weight(1f)
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              IosScorePill(
                label = "ORIGINALITY",
                score = rubric.originality,
                max = 10,
                modifier = Modifier.weight(1f)
              )
              IosScorePill(
                label = "MATH RIGOR",
                score = rubric.mathematicalRigor,
                max = 10,
                modifier = Modifier.weight(1f)
              )
              IosScorePill(
                label = "REPRODUCIBLE",
                score = rubric.reproducibility,
                max = 10,
                modifier = Modifier.weight(1f)
              )
            }
          }
        }
      }
    }

    // Collapsible Cross-Dossier Instant Keyword Search & Jump Panel
    if (showDossierSearch) {
      item {
        IosGroupedCard(
          header = "Find Across All 30 Referee Stages"
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = dossierSearchQuery,
              onValueChange = { dossierSearchQuery = it },
              placeholder = { Text("Search e.g. IQR, PRISMA, FAIL, leakage, bias...") },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Default.Search,
                  contentDescription = "Find in dossier",
                  tint = CupertinoBlue
                )
              },
              trailingIcon = {
                if (dossierSearchQuery.isNotEmpty()) {
                  IconButton(onClick = { dossierSearchQuery = "" }) {
                    Icon(
                      imageVector = Icons.Default.Close,
                      contentDescription = "Clear search",
                      tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }
              },
              singleLine = true,
              shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
              colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CupertinoBlue),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("dossier_search_input")
            )

            if (dossierSearchQuery.trim().length >= 2) {
              if (dossierSearchMatches.isEmpty()) {
                Text(
                  text = "No matches found for \"${dossierSearchQuery.trim()}\" in this dossier.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              } else {
                Text(
                  text = "${dossierSearchMatches.sumOf { it.matchCount }} match(es) across ${dossierSearchMatches.size} section(s) — tap to jump:",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFamily,
                  color = CupertinoBlue,
                  fontWeight = FontWeight.Bold
                )
                dossierSearchMatches.forEach { hit ->
                  val hitShape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(hitShape)
                      .background(CupertinoBlue.copy(alpha = 0.08f))
                      .border(0.6.dp, CupertinoBlue.copy(alpha = 0.28f), hitShape)
                      .clickable {
                        selectedSectionTab = hit.sectionTabIndex
                        when (hit.sectionTabIndex) {
                          0 -> overviewSubSegment = hit.subSegmentIndex
                          1 -> redBlueSubSegment = hit.subSegmentIndex
                          2 -> mathCodeSubSegment = hit.subSegmentIndex
                          3 -> editorCopySubSegment = hit.subSegmentIndex
                        }
                      }
                      .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = hit.sectionTitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoBlue
                      )
                      Text(
                        text = "${hit.matchCount} hit${if (hit.matchCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        color = CupertinoIndigo
                      )
                    }
                    Text(
                      text = hit.snippet,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurface,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // Sharp-Edged iOS Squircle Sub-Tab Bar for Output Sections
    item {
      IosSubTabBar(
        tabs = sectionTabs,
        selectedIndex = selectedSectionTab,
        onSelect = { selectedSectionTab = it },
        tagPrefix = "dossier_tab"
      )
    }

    // Active Output Sub-Tab Content with Inner Segmented Sub-Tabs for long sections
    when (selectedSectionTab) {
      0 -> {
        item {
          IosSegmentedControl(
            items = listOf("Rubric Scorecard", "Summary & RQ", "Editor Memo", "My Notes"),
            selectedIndex = overviewSubSegment,
            onSelect = { overviewSubSegment = it }
          )
        }
        when (overviewSubSegment) {
          0 -> {
            item {
              RefereeDimensionBreakdownCard(review = review)
            }
          }
          1 -> {
            item {
              DossierSectionCard(
                stageNumber = "STAGE 00a–00c",
                title = "Executive Summary, Research Question & Contributions",
                badgeColor = CupertinoBlue,
                body = buildString {
                  if (review.abstractSummary.isNotBlank()) {
                    appendLine("Executive Assessment: ${review.abstractSummary.trim()}")
                  }
                  if (review.researchQuestion.isNotBlank()) {
                    appendLine()
                    appendLine("[STAGE 00b — RESEARCH QUESTION]")
                    appendLine(review.researchQuestion.trim())
                  }
                  if (review.keyMethodology.isNotBlank()) {
                    appendLine()
                    appendLine("[STAGE 00b — KEY METHODOLOGY]")
                    appendLine(review.keyMethodology.trim())
                  }
                  if (review.coreContributions.isNotBlank()) {
                    appendLine()
                    appendLine("[STAGE 00c — CLAIMED CONTRIBUTIONS]")
                    appendLine(review.coreContributions.trim())
                  }
                }.trim(),
                textScale = reportTextScale
              )
            }
          }
          2 -> {
            item {
              DossierSectionCard(
                stageNumber = "STAGE 04a–08b",
                title = "Senior Editor Verdict & Decision Memo",
                badgeColor = CupertinoIndigo,
                body = review.editorNote,
                textScale = reportTextScale
              )
            }
          }
          3 -> {
            item {
              AuthorRevisionNotesCard(
                notes = authorNotesDraft,
                onNotesChange = { authorNotesDraft = it },
                onSaveNotes = {
                  onSavePersonalNotes(authorNotesDraft)
                  Toast.makeText(context, "Notes saved to SQL & Cloud Dossier", Toast.LENGTH_SHORT).show()
                }
              )
            }
          }
        }
      }

      1 -> {
        item {
          IosSegmentedControl(
            items = listOf(
              "Red Team (01a–01g)",
              "Blue Team (02c–03c)",
              "Google Search (03b)"
            ),
            selectedIndex = redBlueSubSegment,
            onSelect = { redBlueSubSegment = it }
          )
        }
        when (redBlueSubSegment) {
          0 -> {
            item {
              DossierSectionCard(
                stageNumber = "STAGE 01a–01g • RED TEAM",
                title = "Adversarial Breaker / Butcher / Shredder / Void Critique",
                badgeColor = CupertinoRed,
                body = review.redTeamCritique,
                textScale = reportTextScale
              )
            }
          }
          1 -> {
            item {
              DossierSectionCard(
                stageNumber = "STAGE 02c–03c • BLUE TEAM & CHECKER",
                title = "Verification Cascade, Rebuttal Defense & Verified List v2",
                badgeColor = CupertinoGreen,
                body = review.blueTeamVerification,
                textScale = reportTextScale
              )
            }
          }
          2 -> {
            item {
              Stage03bGoogleSearchGroundingCard(
                review = review,
                searchGroundingState = searchGroundingState,
                textScale = reportTextScale,
                onRunSearchVerification = onRunGoogleSearchVerification
              )
            }
          }
        }
      }

      2 -> {
        item {
          IosSegmentedControl(
            items = listOf(
              "Quant & Stats (01e–02a)",
              "Repro & Leakage (01i–01n)"
            ),
            selectedIndex = mathCodeSubSegment,
            onSelect = { mathCodeSubSegment = it }
          )
        }
        when (mathCodeSubSegment) {
          0 -> {
            item {
              DossierSectionCard(
                stageNumber = "STAGE 01e–01fd & 02a • QUANTITATIVE AUDIT",
                title = "Quantitative, Statistical & Mathematical Audit",
                badgeColor = CupertinoIndigo,
                body = review.mathAuditReport,
                textScale = reportTextScale
              )
            }
          }
          1 -> {
            item {
              DossierSectionCard(
                stageNumber = "STAGE 01i–01n • CODE & LEAKAGE",
                title = "Reproducibility, Data Leakage & GRIM Statistics",
                badgeColor = CupertinoAmber,
                body = review.codeAuditReport,
                textScale = reportTextScale
              )
            }
          }
        }
      }

      3 -> {
        item {
          IosSegmentedControl(
            items = listOf(
              "Copyedit & De-Fluff (09a–09c)",
              "Mandatory Author Questions"
            ),
            selectedIndex = editorCopySubSegment,
            onSelect = { editorCopySubSegment = it }
          )
        }
        when (editorCopySubSegment) {
          0 -> {
            item {
              DossierSectionCard(
                stageNumber = "STAGE 08a–09c • PHD-SKILLS",
                title = "Academic Copyedit, De-Fluffing & Revision Actions",
                badgeColor = CupertinoBlue,
                body = review.copyeditSuggestions,
                textScale = reportTextScale
              )
            }
          }
          1 -> {
            item {
              DossierSectionCard(
                stageNumber = "REFEREE INTERROGATION",
                title = "Mandatory Questions for the Authors",
                badgeColor = CupertinoIndigo,
                body = review.reviewerQuestions.ifBlank { "No additional author interrogation questions recorded." },
                textScale = reportTextScale
              )
            }
          }
        }
      }

      4 -> {
        item {
          MultiTurnGeminiChatbotCard(
            chatState = refereeChatState,
            inputDraft = followUpInput,
            onInputDraftChange = { followUpInput = it },
            onSendMessage = {
              if (followUpInput.isNotBlank()) {
                onSendChatMessage(followUpInput)
                followUpInput = ""
              }
            },
            onClearHistory = onClearChatHistory,
            onSelectPersona = onUpdateChatPersona,
            onSelectModelTier = onUpdateChatModelTier,
            onToggleSearchGrounding = onUpdateChatSearchGrounding
          )
        }
      }

      5 -> {
        item {
          PdfAndOfflineExportCard(
            review = review,
            isCachedOffline = isCachedOffline,
            isExportingPdf = isExportingPdf,
            onSharePdf = {
              if (!isExportingPdf) {
                isExportingPdf = true
                scope.launch {
                  val res = PdfReportExporter.exportAndSharePdf(context, review)
                  isExportingPdf = false
                  res.onFailure { err ->
                    Toast.makeText(
                      context,
                      "PDF export failed: ${err.localizedMessage}",
                      Toast.LENGTH_SHORT
                    ).show()
                  }
                }
              }
            },
            onSaveOfflineCache = onSaveOfflineCache
          )
        }
        item {
          PlainTextExportCard(
            plainText = review.formattedDossier,
            textScale = reportTextScale,
            onCopy = {
              val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              clipboard.setPrimaryClip(
                ClipData.newPlainText("Referee Report", review.formattedDossier)
              )
              Toast.makeText(
                context,
                "Plain-text report copied to clipboard",
                Toast.LENGTH_SHORT
              ).show()
            }
          )
        }
      }
    }
  }
}

@Composable
private fun Stage03bGoogleSearchGroundingCard(
  review: PaperReview,
  searchGroundingState: SearchGroundingUiState,
  textScale: Float = 1.0f,
  onRunSearchVerification: () -> Unit
) {
  val context = LocalContext.current
  val isRunning = searchGroundingState is SearchGroundingUiState.Loading

  IosGroupedCard(
    header = "Stage 03b • External Literature & Citation Grounding (Google Search)"
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      IosSquircleIconBadge(
        icon = Icons.Default.TravelExplore,
        tint = CupertinoBlue,
        size = 38.dp,
        cornerRadius = 8.dp
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Live Google Search Grounding (Tool.googleSearch)",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Verify prior art, SOTA benchmark claims, and external citations for '${review.title.take(40)}'",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    IosSquircleButton(
      text = if (isRunning) "Searching Google Scholar & Web..." else "Run Stage 03b Google Search Verification",
      onClick = onRunSearchVerification,
      enabled = !isRunning,
      icon = Icons.Default.Search,
      containerColor = CupertinoBlue,
      cornerRadius = 9.dp,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("run_stage03b_google_search_button")
    )

    when (searchGroundingState) {
      is SearchGroundingUiState.Idle -> {}
      is SearchGroundingUiState.Loading -> {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = CupertinoBlue
          )
          Text(
            text = "Grounding manuscript claims against live Google Search data...",
            style = MaterialTheme.typography.bodySmall,
            color = CupertinoBlue
          )
        }
      }
      is SearchGroundingUiState.Error -> {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = searchGroundingState.message,
          style = MaterialTheme.typography.bodySmall,
          color = CupertinoRed
        )
      }
      is SearchGroundingUiState.Success -> {
        val result = searchGroundingState.result
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Spacer(modifier = Modifier.height(10.dp))

        if (result.searchQueries.isNotEmpty()) {
          Text(
            text = "EXECUTED GOOGLE SEARCH QUERIES",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            color = CupertinoBlue
          )
          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            result.searchQueries.forEach { query ->
              val chipShape = SquircleShape(cornerRadius = 7.dp, smoothing = 0.88f)
              Box(
                modifier = Modifier
                  .clip(chipShape)
                  .background(CupertinoBlue.copy(alpha = 0.10f))
                  .border(0.6.dp, CupertinoBlue.copy(alpha = 0.30f), chipShape)
                  .padding(horizontal = 9.dp, vertical = 5.dp)
              ) {
                Text(
                  text = "🔍 $query",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFamily,
                  color = CupertinoBlue
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(10.dp))
        }

        Text(
          text = result.summaryText,
          style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = (14.5f * textScale).sp,
            lineHeight = (22f * textScale).sp
          ),
          color = MaterialTheme.colorScheme.onSurface
        )

        if (result.sources.isNotEmpty()) {
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = "GROUNDED WEB SOURCES (${result.sources.size})",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            color = CupertinoGreen
          )
          Spacer(modifier = Modifier.height(6.dp))
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            result.sources.forEach { src ->
              GroundedSourceItemRow(
                source = src,
                onClick = {
                  runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src.url)))
                  }
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
private fun GroundedSourceItemRow(
  source: GroundedWebSource,
  onClick: () -> Unit
) {
  val shape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(CupertinoGreen.copy(alpha = 0.08f))
      .border(0.6.dp, CupertinoGreen.copy(alpha = 0.30f), shape)
      .clickable { onClick() }
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f)
    ) {
      Icon(
        imageVector = Icons.Default.Language,
        contentDescription = null,
        tint = CupertinoGreen,
        modifier = Modifier.size(16.dp)
      )
      Column {
        Text(
          text = source.title,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = source.url,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          fontSize = 10.sp,
          color = CupertinoBlue,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    Icon(
      imageVector = Icons.Default.OpenInNew,
      contentDescription = "Open web source",
      tint = CupertinoBlue,
      modifier = Modifier.size(15.dp)
    )
  }
}

@Composable
private fun MultiTurnGeminiChatbotCard(
  chatState: RefereeChatUiState,
  inputDraft: String,
  onInputDraftChange: (String) -> Unit,
  onSendMessage: () -> Unit,
  onClearHistory: () -> Unit,
  onSelectPersona: (ChatbotRolePersona) -> Unit,
  onSelectModelTier: (GeminiModelTier) -> Unit,
  onToggleSearchGrounding: (Boolean) -> Unit
) {
  val context = LocalContext.current
  val personas = ChatbotRolePersona.entries
  val modelTiers = GeminiModelTier.entries

  IosGroupedCard(
    header = "Multi-Turn Gemini Referee Chatbot (SQL-Persisted + Search Grounding)"
  ) {
    // 1. Persona Selector
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IosSquircleIconBadge(
          icon = Icons.Default.Psychology,
          tint = CupertinoIndigo,
          size = 32.dp,
          cornerRadius = 7.dp
        )
        Column {
          Text(
            text = "Referee Persona (${chatState.selectedPersona.stageTag})",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = chatState.selectedPersona.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      if (chatState.messages.isNotEmpty()) {
        IconButton(
          onClick = onClearHistory,
          modifier = Modifier.testTag("clear_chat_history_button")
        ) {
          Icon(
            imageVector = Icons.Default.DeleteOutline,
            contentDescription = "Clear Chat History",
            tint = CupertinoRed
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    IosSegmentedControl(
      items = personas.map { it.shortLabel },
      selectedIndex = personas.indexOf(chatState.selectedPersona).coerceAtLeast(0),
      onSelect = { idx -> onSelectPersona(personas[idx]) }
    )

    Spacer(modifier = Modifier.height(10.dp))

    // 2. Gemini Model Reasoning Tier Selector
    Text(
      text = "GEMINI MODEL TIER: ${chatState.selectedModelTier.modelId.uppercase()} — ${chatState.selectedModelTier.description}",
      style = MaterialTheme.typography.labelSmall,
      fontFamily = JetBrainsMonoFamily,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
      color = CupertinoBlue
    )
    Spacer(modifier = Modifier.height(6.dp))

    IosSegmentedControl(
      items = modelTiers.map { it.shortLabel },
      selectedIndex = modelTiers.indexOf(chatState.selectedModelTier).coerceAtLeast(0),
      onSelect = { idx -> onSelectModelTier(modelTiers[idx]) }
    )

    Spacer(modifier = Modifier.height(8.dp))
    IosInsetDivider()

    // 3. Google Search Grounding Toggle (iOS Cupertino Switch)
    IosSettingToggleRow(
      icon = Icons.Default.TravelExplore,
      iconTint = CupertinoGreen,
      title = "Google Search Grounding (Tool.googleSearch)",
      subtitle = "Ground chat answers in live web citations, SOTA benchmarks, and prior art",
      checked = chatState.enableSearchGrounding,
      onCheckedChange = onToggleSearchGrounding,
      modifier = Modifier.testTag("toggle_chat_search_grounding")
    )

    IosInsetDivider()
    Spacer(modifier = Modifier.height(10.dp))

    // 4. Quick Prompts
    val quickPrompts = listOf(
      "Draft a point-by-point rebuttal to the #1 [CRITICAL] flaw",
      "Verify with Google Search if newer SOTA baselines exist for this task",
      "Cross-examine my identification strategy as hostile Reviewer 2"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      quickPrompts.forEach { prompt ->
        IosSecondarySquircleButton(
          text = prompt,
          onClick = { onInputDraftChange(prompt) },
          tintColor = CupertinoBlue,
          cornerRadius = 8.dp,
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 5. Multi-Turn Conversation Thread (Persisted in Room SQLite)
    if (chatState.messages.isEmpty() && !chatState.isStreaming) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
          .padding(12.dp)
      ) {
        Text(
          text = "Multi-turn conversation ready. Messages are stored in your on-device SQL database (sql_chat_messages) and maintain full multi-turn context across follow-up questions.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chatState.messages.forEach { msg ->
          ChatTurnBubble(
            message = msg,
            onOpenUrl = { url ->
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
              }
            }
          )
        }

        if (chatState.isStreaming) {
          val streamingMsg = RefereeChatMessageUi(
            id = -1L,
            role = "model",
            persona = chatState.selectedPersona,
            modelTier = chatState.selectedModelTier,
            text = chatState.streamingPartialText.ifBlank { "Thinking & grounding response..." },
            searchQueries = chatState.streamingQueries,
            sources = chatState.streamingSources
          )
          ChatTurnBubble(
            message = streamingMsg,
            isStreaming = true,
            onOpenUrl = { url ->
              runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
              }
            }
          )
        }
      }
    }

    if (chatState.errorMessage != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = chatState.errorMessage,
        style = MaterialTheme.typography.bodySmall,
        color = CupertinoRed
      )
    }

    Spacer(modifier = Modifier.height(10.dp))

    OutlinedTextField(
      value = inputDraft,
      onValueChange = onInputDraftChange,
      label = {
        Text("Message ${chatState.selectedPersona.shortLabel} (${chatState.selectedModelTier.shortLabel})")
      },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("referee_qa_input"),
      minLines = 2,
      maxLines = 5,
      shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CupertinoBlue
      )
    )

    Spacer(modifier = Modifier.height(10.dp))

    IosSquircleButton(
      text = if (chatState.isStreaming) {
        "Streaming Multi-Turn Response..."
      } else {
        "Send to ${chatState.selectedPersona.shortLabel}"
      },
      onClick = onSendMessage,
      enabled = !chatState.isStreaming && inputDraft.isNotBlank(),
      icon = Icons.AutoMirrored.Filled.Send,
      containerColor = CupertinoBlue,
      cornerRadius = 9.dp,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("referee_qa_send_button")
    )
  }
}

@Composable
private fun ChatTurnBubble(
  message: RefereeChatMessageUi,
  isStreaming: Boolean = false,
  onOpenUrl: (String) -> Unit
) {
  val isUser = message.role == "user"
  val bubbleShape = SquircleShape(cornerRadius = 10.dp, smoothing = 0.86f)
  val bgColor = if (isUser) {
    CupertinoBlue.copy(alpha = 0.14f)
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
  }
  val borderColor = if (isUser) {
    CupertinoBlue.copy(alpha = 0.35f)
  } else {
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(bubbleShape)
      .background(bgColor)
      .border(0.75.dp, borderColor, bubbleShape)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = if (isUser) {
          "RESEARCHER / AUTHOR"
        } else {
          "${message.persona.shortLabel.uppercase()} • ${message.modelTier.modelId}"
        },
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        color = if (isUser) CupertinoBlue else CupertinoIndigo
      )
      if (isStreaming) {
        CircularProgressIndicator(
          modifier = Modifier.size(14.dp),
          strokeWidth = 2.dp,
          color = CupertinoBlue
        )
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))

    Text(
      text = message.text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface
    )

    if (message.searchQueries.isNotEmpty()) {
      Text(
        text = "Google Search: ${message.searchQueries.joinToString(" • ")}",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFamily,
        fontSize = 10.sp,
        color = CupertinoGreen
      )
    }

    if (message.sources.isNotEmpty()) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        message.sources.take(4).forEach { src ->
          GroundedSourceItemRow(
            source = src,
            onClick = { onOpenUrl(src.url) }
          )
        }
      }
    }
  }
}

@Composable
fun VerdictSquircleBadge(decision: String) {
  val palette = LocalNeumorphicPalette.current
  val normalized = decision.uppercase()
  val (textColor, icon) = when {
    normalized.contains("REJECT") -> CupertinoRed to Icons.Default.ReportProblem
    normalized.contains("MAJOR") -> CupertinoAmber to Icons.Default.Shield
    else -> CupertinoGreen to Icons.Default.Verified
  }

  val pillRadius = 11.dp
  Row(
    modifier = Modifier
      .neuRaised(
        cornerRadius = pillRadius,
        elevation = 3.2.dp,
        surfaceColor = palette.surfaceElevated,
        accentGlowColor = textColor.copy(alpha = 0.22f)
      )
      .padding(horizontal = 10.dp, vertical = 5.5.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = textColor,
      modifier = Modifier.size(13.dp)
    )
    Text(
      text = decision.replace('_', ' ').uppercase(),
      style = MaterialTheme.typography.labelSmall,
      fontSize = 10.sp,
      fontWeight = FontWeight.ExtraBold,
      color = textColor
    )
  }
}

@Composable
private fun TrackSquircleBadge(isEmpirical: Boolean) {
  val palette = LocalNeumorphicPalette.current
  Box(
    modifier = Modifier
      .neuRecessed(
        cornerRadius = 10.dp,
        depth = 2.dp,
        cavityColor = palette.surfaceRecessed
      )
      .padding(horizontal = 9.dp, vertical = 5.dp)
  ) {
    Text(
      text = if (isEmpirical) "EMPIRICAL" else "THEORY",
      style = MaterialTheme.typography.labelSmall,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Bold,
      color = CupertinoIndigo
    )
  }
}

@Composable
private fun IosScorePill(
  label: String,
  score: Int,
  max: Int,
  modifier: Modifier = Modifier,
  isPrimary: Boolean = false
) {
  val palette = LocalNeumorphicPalette.current
  Column(
    modifier = modifier
      .neuRecessed(
        cornerRadius = 12.dp,
        depth = 2.4.dp,
        cavityColor = palette.surfaceRecessed
      )
      .padding(vertical = 8.dp, horizontal = 5.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontSize = 8.5.sp,
      letterSpacing = 0.35.sp,
      fontWeight = FontWeight.Bold,
      color = if (isPrimary) CupertinoBlue else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = "$score/$max",
      style = MaterialTheme.typography.titleSmall,
      fontFamily = JetBrainsMonoFamily,
      fontWeight = FontWeight.ExtraBold,
      color = if (isPrimary) CupertinoBlue else MaterialTheme.colorScheme.onSurface
    )
  }
}

@Composable
private fun RefereeDimensionBreakdownCard(review: PaperReview) {
  val rubric = remember(review.id, review.editorNote, review.credibilityScore) {
    review.toRubric()
  }
  val criteria = remember(rubric) { rubric.criteriaList() }

  IosGroupedCard(
    header = "Structured Paper Review Rubric (6-Axis Evaluation)"
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "CALIBRATED REFEREE SCORECARD",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Bold,
        color = CupertinoBlue
      )
      Box(
        modifier = Modifier
          .clip(SquircleShape(cornerRadius = 6.dp, smoothing = 0.88f))
          .background(CupertinoIndigo.copy(alpha = 0.12f))
          .padding(horizontal = 8.dp, vertical = 3.dp)
      ) {
        Text(
          text = "CONFIDENCE ${rubric.refereeConfidence}/5",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          color = CupertinoIndigo
        )
      }
    }
    Spacer(modifier = Modifier.height(10.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      criteria.forEach { criterion ->
        val barColor = when {
          criterion.score >= 8 -> CupertinoGreen
          criterion.score >= 6 -> CupertinoBlue
          criterion.score >= 4 -> CupertinoAmber
          else -> CupertinoRed
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "${criterion.criterion} (${criterion.weightPercent}%)",
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f)
            )
            Text(
              text = "${criterion.score} / ${criterion.maxScore}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              color = barColor
            )
          }
          LinearProgressIndicator(
            progress = { (criterion.score.toFloat() / criterion.maxScore.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier
              .fillMaxWidth()
              .height(6.dp)
              .clip(SquircleShape(cornerRadius = 3.dp, smoothing = 0.88f)),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
          )
          if (criterion.justification.isNotBlank()) {
            Text(
              text = DossierFormattingEngine.cleanAcademicProse(criterion.justification),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DossierSectionCard(
  stageNumber: String,
  title: String,
  badgeColor: Color,
  body: String,
  textScale: Float = 1.0f,
  useMonospace: Boolean = false
) {
  val context = LocalContext.current
  val parsedBlocks = remember(body) {
    DossierFormattingEngine.parseStructuredBlocks(body, stripInternalTags = true)
  }

  IosGroupedCard {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      val badgeShape = SquircleShape(cornerRadius = 6.dp, smoothing = 0.88f)
      Box(
        modifier = Modifier
          .clip(badgeShape)
          .background(badgeColor.copy(alpha = 0.14f))
          .border(0.6.dp, badgeColor.copy(alpha = 0.30f), badgeShape)
          .padding(horizontal = 9.dp, vertical = 4.dp)
      ) {
        Text(
          text = stageNumber,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          color = badgeColor
        )
      }

      IconButton(
        onClick = {
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          val cleanedCopy = DossierFormattingEngine.cleanAcademicProse(body)
          clipboard.setPrimaryClip(ClipData.newPlainText(title, cleanedCopy))
          Toast.makeText(context, "Copied $title", Toast.LENGTH_SHORT).show()
        }
      ) {
        Icon(
          imageVector = Icons.Default.ContentCopy,
          contentDescription = "Copy section",
          tint = CupertinoBlue,
          modifier = Modifier.size(18.dp)
        )
      }
    }

    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    Spacer(modifier = Modifier.height(10.dp))

    if (useMonospace) {
      Text(
        text = DossierFormattingEngine.cleanAcademicProse(body),
        style = MaterialTheme.typography.bodyMedium.copy(
          fontFamily = JetBrainsMonoFamily,
          fontSize = (13f * textScale).sp,
          lineHeight = (20f * textScale).sp
        ),
        color = MaterialTheme.colorScheme.onSurface
      )
    } else if (parsedBlocks.isEmpty()) {
      Text(
        text = "No findings recorded for this stage.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      StructuredReportBlocksColumn(
        blocks = parsedBlocks,
        textScale = textScale
      )
    }
  }
}

@Composable
private fun StructuredReportBlocksColumn(
  blocks: List<ReportBlock>,
  textScale: Float
) {
  val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize = (14.2f * textScale).sp,
    lineHeight = (21.5f * textScale).sp
  )

  Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
    blocks.forEach { block ->
      when (block) {
        is ReportBlock.SubHeader -> {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            if (block.tag.isNotBlank()) {
              val tagShape = SquircleShape(cornerRadius = 5.dp, smoothing = 0.88f)
              Box(
                modifier = Modifier
                  .clip(tagShape)
                  .background(CupertinoBlue.copy(alpha = 0.11f))
                  .padding(horizontal = 7.dp, vertical = 2.5.dp)
              ) {
                Text(
                  text = block.tag.uppercase(Locale.US),
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = (10f * textScale).sp,
                  fontWeight = FontWeight.Bold,
                  color = CupertinoBlue
                )
              }
            }
            Text(
              text = block.title,
              style = MaterialTheme.typography.titleSmall.copy(
                fontSize = (14.5f * textScale).sp,
                lineHeight = (20f * textScale).sp
              ),
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
          }
        }

        is ReportBlock.SeverityCard -> {
          val accent = when (block.level) {
            SeverityLevel.CRITICAL, SeverityLevel.MUST_FIX, SeverityLevel.FAIL -> CupertinoRed
            SeverityLevel.MAJOR, SeverityLevel.SHOULD_FIX, SeverityLevel.WARN -> CupertinoAmber
            SeverityLevel.MINOR -> CupertinoIndigo
            SeverityLevel.DISMISSED, SeverityLevel.PASS -> CupertinoGreen
            SeverityLevel.RECOMMENDED -> CupertinoBlue
          }
          val cardShape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.88f)
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(cardShape)
              .background(accent.copy(alpha = 0.08f))
              .border(0.75.dp, accent.copy(alpha = 0.32f), cardShape)
              .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
          ) {
            val pillShape = SquircleShape(cornerRadius = 4.dp, smoothing = 0.88f)
            Box(
              modifier = Modifier
                .clip(pillShape)
                .background(accent)
                .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
              Text(
                text = block.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                fontSize = (9.5f * textScale).sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
            }
            Text(
              text = block.body,
              style = bodyStyle,
              color = MaterialTheme.colorScheme.onSurface
            )
          }
        }

        is ReportBlock.Callout -> {
          val accent = when (block.tone) {
            CalloutTone.BOTTOM_LINE -> CupertinoBlue
            CalloutTone.FUTURE_RESEARCH -> CupertinoIndigo
            CalloutTone.ALERT -> CupertinoAmber
            CalloutTone.INFO -> MaterialTheme.colorScheme.primary
          }
          val cardShape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.88f)
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(cardShape)
              .background(accent.copy(alpha = 0.09f))
              .border(0.75.dp, accent.copy(alpha = 0.30f), cardShape)
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text(
              text = block.title.uppercase(Locale.US),
              style = MaterialTheme.typography.labelSmall.copy(
                fontSize = (10.5f * textScale).sp
              ),
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              color = accent
            )
            Text(
              text = block.body,
              style = bodyStyle,
              color = MaterialTheme.colorScheme.onSurface
            )
          }
        }

        is ReportBlock.Bullet -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = block.marker,
              style = MaterialTheme.typography.labelMedium.copy(
                fontSize = (12.5f * textScale).sp,
                lineHeight = (21.5f * textScale).sp
              ),
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.Bold,
              color = CupertinoBlue,
              modifier = Modifier.widthIn(min = if (block.marker.length > 3) 34.dp else 18.dp)
            )
            Text(
              text = block.body,
              style = bodyStyle,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f)
            )
          }
        }

        is ReportBlock.Equation -> {
          val eqShape = SquircleShape(cornerRadius = 7.dp, smoothing = 0.88f)
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(eqShape)
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
              .border(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), eqShape)
              .padding(horizontal = 11.dp, vertical = 8.dp)
          ) {
            Text(
              text = block.expression,
              style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMonoFamily,
                fontSize = (12.5f * textScale).sp,
                lineHeight = (19f * textScale).sp
              ),
              color = MaterialTheme.colorScheme.onSurface
            )
          }
        }

        is ReportBlock.AuditTable -> {
          val tableShape = SquircleShape(cornerRadius = 10.dp, smoothing = 0.88f)
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(tableShape)
              .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), tableShape)
          ) {
            // Compact table header strip
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(CupertinoIndigo.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = block.headers.firstOrNull().orEmpty().uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                fontSize = (9.5f * textScale).sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoIndigo
              )
              Text(
                text = "${block.rows.size} DIAGNOSTIC CHECKS",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                fontSize = (9f * textScale).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            block.rows.forEachIndexed { rowIdx, cells ->
              if (rowIdx > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
              }
              val dimText = cells.getOrElse(0) { "" }
              val evidenceText = if (cells.size >= 4) cells[1] else ""
              val statusRaw = when {
                cells.size >= 4 -> cells[2]
                cells.size == 3 -> cells[1]
                else -> ""
              }.trim().uppercase(Locale.US)
              val detailText = when {
                cells.size >= 4 -> cells[3]
                cells.size == 3 -> cells[2]
                cells.size == 2 -> cells[1]
                else -> ""
              }
              val badgeColor = when (statusRaw) {
                "PASS" -> CupertinoGreen
                "WARN", "MAJOR" -> CupertinoAmber
                "FAIL", "CRITICAL" -> CupertinoRed
                else -> CupertinoIndigo
              }
              val rowBg = if (rowIdx % 2 == 0) {
                MaterialTheme.colorScheme.surface
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
              }

              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(rowBg)
                  .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.5.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = dimText,
                    style = MaterialTheme.typography.labelMedium.copy(
                      fontSize = (12.5f * textScale).sp
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                  )
                  if (statusRaw.isNotBlank()) {
                    val statusPill = SquircleShape(cornerRadius = 4.dp, smoothing = 0.88f)
                    Box(
                      modifier = Modifier
                        .clip(statusPill)
                        .background(badgeColor)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                      Text(
                        text = statusRaw,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = (9f * textScale).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                      )
                    }
                  }
                }
                if (evidenceText.isNotBlank()) {
                  Text(
                    text = evidenceText,
                    style = MaterialTheme.typography.labelSmall.copy(
                      fontFamily = JetBrainsMonoFamily,
                      fontSize = (10.5f * textScale).sp,
                      lineHeight = (15f * textScale).sp
                    ),
                    color = CupertinoBlue
                  )
                }
                if (detailText.isNotBlank()) {
                  Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall.copy(
                      fontSize = (12.8f * textScale).sp,
                      lineHeight = (18.5f * textScale).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                  )
                }
              }
            }
          }
        }

        is ReportBlock.Paragraph -> {
          Text(
            text = block.text,
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurface
          )
        }
      }
    }
  }
}

@Composable
private fun AuthorRevisionNotesCard(
  notes: String,
  onNotesChange: (String) -> Unit,
  onSaveNotes: () -> Unit
) {
  IosGroupedCard(
    header = "Author Revision & Rebuttal Scratchpad"
  ) {
    Text(
      text = "Track your experiments, responses to Reviewer 2, and camera-ready checklist items.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(10.dp))
    OutlinedTextField(
      value = notes,
      onValueChange = onNotesChange,
      placeholder = { Text("Add rebuttal notes or TODO experiments...") },
      modifier = Modifier
        .fillMaxWidth()
        .testTag("author_notes_input"),
      minLines = 3,
      maxLines = 6,
      shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CupertinoBlue
      )
    )
    Spacer(modifier = Modifier.height(10.dp))
    IosSquircleButton(
      text = "Save Notes to SQL & Cloud Dossier",
      onClick = onSaveNotes,
      icon = Icons.Default.CheckCircle,
      containerColor = CupertinoBlue,
      cornerRadius = 9.dp,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("save_author_notes_button")
    )
  }
}

@Composable
private fun PdfAndOfflineExportCard(
  review: PaperReview,
  isCachedOffline: Boolean,
  isExportingPdf: Boolean,
  onSharePdf: () -> Unit,
  onSaveOfflineCache: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var isSavingPdf by remember { mutableStateOf(false) }

  val suggestedFileName = remember(review.title) {
    val slug = review.title
      .lowercase(Locale.US)
      .replace(Regex("""[^a-z0-9]+"""), "_")
      .trim('_')
      .take(40)
      .ifBlank { "paper_critic_dossier" }
    "${slug}_referee_report.pdf"
  }

  val savePdfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf")
  ) { uri ->
    if (uri != null) {
      isSavingPdf = true
      scope.launch {
        val res = PdfReportExporter.writePdfToUri(context, review, uri)
        isSavingPdf = false
        res.onSuccess {
          Toast.makeText(context, "Saved PDF referee report to device", Toast.LENGTH_SHORT).show()
        }.onFailure { err ->
          Toast.makeText(context, "PDF save failed: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  IosGroupedCard(
    header = "Export & Share Professional PDF Dossier",
    footer = "Generates a formatted multi-page A4 PDF with the 6-axis scorecard and all 30 referee stages."
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        IosSquircleIconBadge(
          icon = Icons.Default.PictureAsPdf,
          tint = CupertinoRed,
          size = 38.dp,
          cornerRadius = 8.dp
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Publication-Grade PDF Export",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Includes Executive Verdict, Credibility Scorecard, Red/Blue Cascade, Math & Copyedit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IosSquircleButton(
          text = if (isExportingPdf) "Rendering PDF..." else "Share PDF",
          onClick = onSharePdf,
          enabled = !isExportingPdf,
          icon = Icons.Default.Share,
          containerColor = CupertinoRed,
          cornerRadius = 9.dp,
          modifier = Modifier
            .weight(1f)
            .testTag("share_pdf_report_button")
        )
        IosSecondarySquircleButton(
          text = if (isSavingPdf) "Saving..." else "Save .pdf File",
          onClick = { savePdfLauncher.launch(suggestedFileName) },
          enabled = !isSavingPdf,
          icon = Icons.Default.Download,
          tintColor = CupertinoBlue,
          cornerRadius = 9.dp,
          modifier = Modifier
            .weight(1f)
            .testTag("save_pdf_file_button")
        )
      }

      IosInsetDivider()

      IosSecondarySquircleButton(
        text = if (isCachedOffline) "Verified in Room Offline Cache (Re-Save)" else "Save to Room Offline Database",
        onClick = onSaveOfflineCache,
        icon = Icons.Default.OfflinePin,
        tintColor = if (isCachedOffline) CupertinoGreen else CupertinoAmber,
        cornerRadius = 9.dp,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("cache_offline_room_button")
      )
    }
  }
}

@Composable
private fun PlainTextExportCard(
  plainText: String,
  textScale: Float = 1.0f,
  onCopy: () -> Unit
) {
  val context = LocalContext.current
  val saveTxtLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
    contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
  ) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
          out.write(plainText.toByteArray(Charsets.UTF_8))
        }
      }.onSuccess {
        Toast.makeText(context, "Saved referee report (.txt) to device", Toast.LENGTH_SHORT).show()
      }.onFailure { err ->
        Toast.makeText(context, "Save failed: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  IosGroupedCard(
    header = "Stage 10_render • Plain-Text Referee Report"
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = "Zero-markdown ASCII dossier ready for OpenReview, HotCRP, ScholarOne, or local .txt archive.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IosSecondarySquircleButton(
          text = "Copy All",
          onClick = onCopy,
          icon = Icons.Default.ContentCopy,
          tintColor = CupertinoBlue,
          cornerRadius = 9.dp,
          modifier = Modifier
            .weight(1f)
            .testTag("copy_plain_text_button")
        )
        IosSquircleButton(
          text = "Save .txt File",
          onClick = { saveTxtLauncher.launch("is_it_credible_referee_report.txt") },
          icon = Icons.Default.Description,
          containerColor = CupertinoBlue,
          cornerRadius = 9.dp,
          modifier = Modifier
            .weight(1f)
            .testTag("save_plain_text_file_button")
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .padding(12.dp)
    ) {
      Text(
        text = plainText,
        fontFamily = JetBrainsMonoFamily,
        fontSize = (12f * textScale).sp,
        lineHeight = (18f * textScale).sp,
        color = MaterialTheme.colorScheme.onSurface
      )
    }
  }
}
