package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.epistemic.ReviewerCalibrationBenchmark
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import com.example.data.model.DefaultAiProviderCatalog
import com.example.data.model.PaperReview
import com.example.ui.auth.GoogleSignInButton
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
import com.example.ui.viewmodel.AppSettingsState
import com.example.ui.viewmodel.ArchiveUiState
import com.example.ui.viewmodel.ThemeModePreference
import com.example.util.DossierFormattingEngine
import com.example.util.PdfReportExporter
import kotlin.math.abs
import kotlinx.coroutines.launch

@Composable
fun ArchiveScreen(
  archiveState: ArchiveUiState,
  offlineCachedIds: Set<String> = emptySet(),
  isSignedIn: Boolean = true,
  userEmail: String = "",
  unsyncedLocalCount: Int = 0,
  sqlCachedCount: Int = 0,
  sqlChatMessagesCount: Int = 0,
  isSyncingPortfolio: Boolean = false,
  onSyncPortfolioNow: () -> Unit = {},
  onOpenReview: (PaperReview) -> Unit,
  onToggleBookmark: (PaperReview) -> Unit,
  onDeleteReview: (String) -> Unit,
  onGoToWorkbench: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var activeHistorySubTab by rememberSaveable { mutableIntStateOf(0) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var selectedFilterIdx by rememberSaveable { mutableIntStateOf(0) }
  var selectedSortIdx by rememberSaveable { mutableIntStateOf(0) } // 0=Newest, 1=Highest Score, 2=Lowest Score
  var signInError by remember { mutableStateOf<String?>(null) }
  var pendingDeleteReview by remember { mutableStateOf<PaperReview?>(null) }
  var exportingReviewId by remember { mutableStateOf<String?>(null) }

  pendingDeleteReview?.let { target ->
    val cleanTitle = DossierFormattingEngine.normalizeTitle(target.title)
    AlertDialog(
      onDismissRequest = { pendingDeleteReview = null },
      title = { Text("Delete Referee Dossier?", fontWeight = FontWeight.Bold) },
      text = {
        Text(
          "Permanently remove \"$cleanTitle\" from your offline SQLite cache and cloud library? This action cannot be undone."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteReview(target.id)
            pendingDeleteReview = null
          },
          modifier = Modifier.testTag("confirm_delete_archive_button")
        ) {
          Text("Delete", color = CupertinoRed, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(
          onClick = { pendingDeleteReview = null },
          modifier = Modifier.testTag("cancel_delete_archive_button")
        ) {
          Text("Cancel")
        }
      }
    )
  }

  val allReviews = when (archiveState) {
    is ArchiveUiState.Success -> archiveState.reviews
    else -> emptyList()
  }

  val filteredReviews = remember(allReviews, searchQuery, selectedFilterIdx, selectedSortIdx) {
    val matching = allReviews.filter { item ->
      val matchesQuery = searchQuery.isBlank() ||
        item.title.contains(searchQuery, ignoreCase = true) ||
        item.authors.contains(searchQuery, ignoreCase = true) ||
        item.venueOrCitation.contains(searchQuery, ignoreCase = true) ||
        item.abstractSummary.contains(searchQuery, ignoreCase = true)

      val matchesFilter = when (selectedFilterIdx) {
        1 -> item.bookmarked
        2 -> item.verdict.contains("REJECT", ignoreCase = true)
        3 -> item.verdict.contains("MAJOR", ignoreCase = true)
        4 -> item.verdict.contains("MINOR", ignoreCase = true) || item.verdict.contains("ACCEPT", ignoreCase = true)
        else -> true
      }
      matchesQuery && matchesFilter
    }
    when (selectedSortIdx) {
      1 -> matching.sortedByDescending { it.credibilityScore }
      2 -> matching.sortedBy { it.credibilityScore }
      else -> matching
    }
  }

  val assessedReviews = remember(allReviews) { allReviews.filter { !it.isUnassessed() } }

  val historySubTabs = listOf(
    IosSubTabEntry(
      title = "Dossiers",
      icon = Icons.Default.History,
      badge = "${allReviews.size}"
    ),
    IosSubTabEntry(
      title = "Stats",
      icon = Icons.Default.Analytics,
      badge = if (assessedReviews.isNotEmpty()) "${assessedReviews.map { it.credibilityScore }.average().toInt()}%" else if (allReviews.isNotEmpty()) "N/A" else null
    ),
    IosSubTabEntry(
      title = "Sync",
      icon = if (isSignedIn) Icons.Default.CloudDone else Icons.Default.CloudSync,
      badge = if (isSignedIn) "CLOUD" else "SQL"
    )
  )

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("archive_screen_list"),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(15.dp)
  ) {
    item {
      IosSubTabBar(
        tabs = historySubTabs,
        selectedIndex = activeHistorySubTab,
        onSelect = { activeHistorySubTab = it },
        tagPrefix = "history_sub_tab"
      )
    }

    when (activeHistorySubTab) {
      0 -> {
        item {
          IosGroupedCard(
            header = "Referee Dossier Library (${filteredReviews.size} of ${allReviews.size})"
          ) {
            val palette = LocalNeumorphicPalette.current
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .neuRecessed(
                  cornerRadius = 24.dp,
                  depth = 4.5.dp,
                  cavityColor = palette.surfaceRecessed
                )
            ) {
              OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search title, venue, or summary…") },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search library",
                    tint = MaterialTheme.colorScheme.primary
                  )
                },
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("archive_search_input"),
                singleLine = true,
                shape = SquircleShape(cornerRadius = 24.dp, smoothing = 0.86f),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                  unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                  focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                  unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
                )
              )
            }

            Spacer(modifier = Modifier.height(10.dp))

            IosSegmentedControl(
              items = listOf("All", "Starred", "Reject", "Major", "Accept"),
              selectedIndex = selectedFilterIdx,
              onSelect = { idx -> selectedFilterIdx = idx }
            )

            Spacer(modifier = Modifier.height(8.dp))

            IosSegmentedControl(
              items = listOf("Newest", "Highest Score", "Lowest Score"),
              selectedIndex = selectedSortIdx,
              onSelect = { idx -> selectedSortIdx = idx }
            )
          }
        }

        when (archiveState) {
          is ArchiveUiState.Loading -> {
            item {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(32.dp),
                contentAlignment = Alignment.Center
              ) {
                CircularProgressIndicator(color = CupertinoBlue)
              }
            }
          }

          is ArchiveUiState.Error -> {
            item {
              IosGroupedCard {
                Text(
                  text = archiveState.message,
                  style = MaterialTheme.typography.bodyMedium,
                  color = CupertinoRed
                )
              }
            }
          }

          is ArchiveUiState.Success -> {
            if (filteredReviews.isEmpty()) {
              item {
                IosGroupedCard {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                  ) {
                    IosSquircleIconBadge(
                      icon = Icons.Default.FolderOpen,
                      tint = CupertinoBlue,
                      size = 46.dp,
                      cornerRadius = 9.dp
                    )
                    Text(
                      text = "No Matching Referee Dossiers",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = "Upload a PDF, Word (.docx/.doc), or Text manuscript in the Upload tab to populate your review history.",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IosSquircleButton(
                      text = "Upload & Analyze Paper",
                      onClick = onGoToWorkbench,
                      icon = Icons.Default.AutoAwesome,
                      containerColor = CupertinoBlue,
                      cornerRadius = 9.dp,
                      modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_archive_cta_button")
                    )
                  }
                }
              }
            } else {
              items(filteredReviews, key = { it.id.ifBlank { it.title } }) { itemReview ->
                IosGroupedCard(
                  modifier = Modifier
                    .clickable { onOpenReview(itemReview) }
                    .testTag("archive_card_${itemReview.id}")
                ) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                      val cardRefereeDecision = com.example.data.ai.referee.RefereeDecision.fromNameOrNull(itemReview.refereeDecision)
                      val effectiveCardVerdict = if (cardRefereeDecision != null) {
                        com.example.data.ai.Reviewer2PipelineService.mapRefereeDecisionToModeVerdict(
                          decision = cardRefereeDecision,
                          reviewMode = itemReview.resolvedReviewMode(),
                          domainAppraisal = null,
                          preOrchestratorVerdict = itemReview.verdict,
                          compositeScore = itemReview.toRubric().overallCredibilityScore
                        )
                      } else {
                        itemReview.verdict
                      }
                      VerdictSquircleBadge(decision = effectiveCardVerdict)
                      if (itemReview.id in offlineCachedIds) {
                        val sqlShape = SquircleShape(cornerRadius = 6.dp, smoothing = 0.88f)
                        Box(
                          modifier = Modifier
                            .clip(sqlShape)
                            .background(CupertinoGreen.copy(alpha = 0.12f))
                            .border(0.5.dp, CupertinoGreen.copy(alpha = 0.35f), sqlShape)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                          Text(
                            text = "OFFLINE SQL",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoGreen
                          )
                        }
                      }
                      if (itemReview.ledgerLoadState != com.example.data.model.LedgerLoadState.COMPLETE) {
                        val degradedShape = SquircleShape(cornerRadius = 6.dp, smoothing = 0.88f)
                        Box(
                          modifier = Modifier
                            .clip(degradedShape)
                            .background(CupertinoAmber.copy(alpha = 0.14f))
                            .border(0.5.dp, CupertinoAmber.copy(alpha = 0.40f), degradedShape)
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                          Text(
                            text = "INCOMPLETE",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = CupertinoAmber
                          )
                        }
                      }
                    }

                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                      val isItemUnassessed = itemReview.isUnassessed()
                      Box(
                        modifier = Modifier
                          .neuRecessed(
                            cornerRadius = 10.dp,
                            depth = 2.dp,
                            cavityColor = LocalNeumorphicPalette.current.surfaceRecessed
                          )
                          .padding(horizontal = 9.dp, vertical = 4.5.dp)
                      ) {
                        Text(
                          text = if (isItemUnassessed) "N/A" else "${itemReview.credibilityScore}/100",
                          fontFamily = JetBrainsMonoFamily,
                          style = MaterialTheme.typography.labelSmall,
                          fontWeight = FontWeight.ExtraBold,
                          color = if (isItemUnassessed) CupertinoAmber else CupertinoBlue
                        )
                      }
                      IconButton(
                        onClick = {
                          if (exportingReviewId == null) {
                            exportingReviewId = itemReview.id
                            scope.launch {
                              val res = PdfReportExporter.exportAndSharePdf(context, itemReview)
                              exportingReviewId = null
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
                        enabled = exportingReviewId != itemReview.id,
                        modifier = Modifier.testTag("archive_share_pdf_${itemReview.id}")
                      ) {
                        Icon(
                          imageVector = Icons.Default.PictureAsPdf,
                          contentDescription = "Share PDF Report",
                          tint = CupertinoRed
                        )
                      }
                      IconButton(onClick = { onToggleBookmark(itemReview) }) {
                        Icon(
                          imageVector = if (itemReview.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                          contentDescription = "Bookmark",
                          tint = if (itemReview.bookmarked) CupertinoBlue else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                      }
                      IconButton(
                        onClick = { pendingDeleteReview = itemReview },
                        modifier = Modifier.testTag("archive_delete_${itemReview.id}")
                      ) {
                        Icon(
                          imageVector = Icons.Default.DeleteOutline,
                          contentDescription = "Delete",
                          tint = CupertinoRed
                        )
                      }
                    }
                  }

                  Spacer(modifier = Modifier.height(6.dp))

                  Text(
                    text = DossierFormattingEngine.normalizeTitle(itemReview.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )

                  Spacer(modifier = Modifier.height(2.dp))

                  Text(
                    text = DossierFormattingEngine.formatCleanCitation(
                      authors = itemReview.authors,
                      year = itemReview.publicationYear,
                      venue = itemReview.venueOrCitation
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )

                  Spacer(modifier = Modifier.height(6.dp))

                  Text(
                    text = DossierFormattingEngine.cleanAcademicProse(itemReview.abstractSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
            }
          }
        }
      }

      1 -> {
        item {
          val unassessedCount = allReviews.size - assessedReviews.size
          IosGroupedCard(
            header = "Portfolio Rubric Averages (${assessedReviews.size} Assessed Papers)"
          ) {
            if (assessedReviews.isEmpty()) {
              Text(
                text = if (unassessedCount > 0) {
                  "$unassessedCount readiness-blocked draft(s) are excluded from portfolio rubric averages. Upload a readable full manuscript to compute scored portfolio metrics."
                } else {
                  "Analyze at least one paper to compute portfolio-wide rubric statistics across Methodology, Significance, Clarity, Originality, Mathematical Rigor, and Reproducibility."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            } else {
              val rubrics = remember(assessedReviews) { assessedReviews.map { it.toRubric() } }
              val avgCredibility = rubrics.map { it.overallCredibilityScore }.average().toInt()
              val avgMethodology = rubrics.map { it.methodology }.average()
              val avgSignificance = rubrics.map { it.significance }.average()
              val avgClarity = rubrics.map { it.clarity }.average()
              val avgOriginality = rubrics.map { it.originality }.average()
              val avgMath = rubrics.map { it.mathematicalRigor }.average()
              val avgRepro = rubrics.map { it.reproducibility }.average()

              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                  text = "Mean Portfolio Credibility Score: $avgCredibility / 100",
                  style = MaterialTheme.typography.titleSmall,
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                  color = CupertinoBlue
                )
                if (unassessedCount > 0) {
                  Text(
                    text = "Excluded $unassessedCount unassessed readiness-blocked draft(s) from mean scores.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }

                val metrics = listOf(
                  Triple("Methodology", avgMethodology, CupertinoIndigo),
                  Triple("Significance", avgSignificance, CupertinoBlue),
                  Triple("Clarity", avgClarity, CupertinoGreen),
                  Triple("Originality", avgOriginality, CupertinoBlue),
                  Triple("Mathematical Rigor", avgMath, CupertinoIndigo),
                  Triple("Reproducibility", avgRepro, CupertinoAmber)
                )

                metrics.forEach { (name, avg, tint) ->
                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                      )
                      Text(
                        text = String.format("%.1f / 10", avg),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        color = tint
                      )
                    }
                    LinearProgressIndicator(
                      progress = { (avg.toFloat() / 10f).coerceIn(0f, 1f) },
                      modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(SquircleShape(cornerRadius = 3.dp, smoothing = 0.88f)),
                      color = tint,
                      trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                  }
                }
              }
            }
          }
        }
      }

      2 -> {
        item {
          if (!isSignedIn) {
            IosGroupedCard(header = "CLOUD BACKUP & CROSS-DEVICE PORTFOLIO SYNC") {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                IosSquircleIconBadge(
                  icon = Icons.Default.CloudSync,
                  tint = CupertinoBlue,
                  size = 40.dp,
                  cornerRadius = 8.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Sign in with Google for Cloud Sync",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = if (unsyncedLocalCount > 0) {
                      "$unsyncedLocalCount session dossier(s) ready to sync across your devices."
                    } else {
                      "Sign in with Google to sync your paper review portfolio across devices via Firebase Firestore."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              if (signInError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text = signInError.orEmpty(),
                  style = MaterialTheme.typography.bodySmall,
                  color = CupertinoRed
                )
              }
              Spacer(modifier = Modifier.height(10.dp))
              GoogleSignInButton(
                onAuthSuccess = { signInError = null },
                onAuthError = { signInError = it }
              )
            }
          } else {
            IosGroupedCard(header = "GOOGLE CLOUD PORTFOLIO SYNC") {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  IosSquircleIconBadge(
                    icon = Icons.Default.CloudDone,
                    tint = CupertinoGreen,
                    size = 38.dp,
                    cornerRadius = 8.dp
                  )
                  Column {
                    Text(
                      text = "Synced Across Devices",
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = if (userEmail.isNotBlank()) {
                        "$userEmail • ${allReviews.size} dossier(s)"
                      } else {
                        "${allReviews.size} dossier(s) in Firestore portfolio"
                      },
                      style = MaterialTheme.typography.labelSmall,
                      color = CupertinoGreen
                    )
                  }
                }
                IosSecondarySquircleButton(
                  text = if (isSyncingPortfolio) "Syncing..." else "Sync Now",
                  onClick = onSyncPortfolioNow,
                  icon = Icons.Default.CloudSync,
                  tintColor = CupertinoBlue,
                  cornerRadius = 8.dp,
                  modifier = Modifier.testTag("archive_sync_now_button")
                )
              }
            }
          }
        }

        item {
          IosGroupedCard(
            header = "ON-DEVICE ROOM SQL DATABASE (paper_critic_sql_database)"
          ) {
            Text(
              text = "Dual-engine persistence: Every referee dossier and multi-turn Gemini Chatbot thread is indexed in an on-device SQLite relational database alongside Firebase Firestore.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val pillShape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
              Column(
                modifier = Modifier
                  .weight(1f)
                  .clip(pillShape)
                  .background(CupertinoIndigo.copy(alpha = 0.10f))
                  .border(0.6.dp, CupertinoIndigo.copy(alpha = 0.30f), pillShape)
                  .padding(10.dp)
              ) {
                Text(
                  text = "TABLE: sql_review_cache",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = CupertinoIndigo
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                  text = "${maxOf(sqlCachedCount, allReviews.size)} SQL Rows",
                  style = MaterialTheme.typography.titleSmall,
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold
                )
              }
              Column(
                modifier = Modifier
                  .weight(1f)
                  .clip(pillShape)
                  .background(CupertinoBlue.copy(alpha = 0.10f))
                  .border(0.6.dp, CupertinoBlue.copy(alpha = 0.30f), pillShape)
                  .padding(10.dp)
              ) {
                Text(
                  text = "TABLE: sql_chat_messages",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = CupertinoBlue
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                  text = "$sqlChatMessagesCount Chat Turns",
                  style = MaterialTheme.typography.titleSmall,
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun IosSettingsScreen(
  settingsState: AppSettingsState,
  apiProviders: List<AiApiProviderEntry> = DefaultAiProviderCatalog.presets,
  isTestingApiKey: Boolean = false,
  apiTestResultMessage: String? = null,
  isSignedIn: Boolean = false,
  userDisplayName: String = "",
  userEmail: String,
  syncedReviewCount: Int = 0,
  unsyncedLocalCount: Int = 0,
  adoptableAnonymousCount: Int = 0,
  anonymousCustomProviderCount: Int = 0,
  isSyncingPortfolio: Boolean = false,
  onSyncPortfolioNow: () -> Unit = {},
  onAdoptAnonymousReviews: () -> Unit = {},
  onAdoptAnonymousApiProviders: () -> Unit = {},
  onUpdateSettings: ((AppSettingsState) -> AppSettingsState) -> Unit,
  onSelectActiveProvider: (String) -> Unit = {},
  onSaveProvider: (AiApiProviderEntry) -> Unit = {},
  onAddCustomProvider: (
    serviceName: String,
    protocolType: ApiProtocolType,
    baseUrl: String,
    modelId: String,
    apiKey: String,
    notes: String
  ) -> Unit = { _, _, _, _, _, _ -> },
  onDeleteCustomProvider: (String) -> Unit = {},
  onTestProviderConnection: (AiApiProviderEntry) -> Unit = {},
  onClearDraft: () -> Unit,
  onSignOut: () -> Unit
) {
  var activeSettingsSubTab by rememberSaveable { mutableIntStateOf(0) }
  var activeSpecsCluster by rememberSaveable { mutableIntStateOf(0) }
  var authError by remember { mutableStateOf<String?>(null) }

  val activePipelineToggleCount = listOf(
    settingsState.defaultOptions.isEmpirical,
    settingsState.defaultOptions.enableMathAudit,
    settingsState.defaultOptions.enableCodeAudit,
    settingsState.defaultOptions.enableEditorNote,
    settingsState.defaultOptions.enableCopyeditWriterMode,
    settingsState.enablePdfVisionPages,
    settingsState.enableGrimStatsCheck,
    settingsState.enableLegalScreen
  ).count { it }

  val configuredKeysCount = apiProviders.count {
    it.id != DefaultAiProviderCatalog.BUILTIN_KEYLESS_ID && it.apiKey.isNotBlank()
  }

  val settingsSubTabs = listOf(
    IosSubTabEntry("Display", Icons.Default.DarkMode, settingsState.themeMode.label),
    IosSubTabEntry("Toggles", Icons.Default.Tune, "$activePipelineToggleCount/8"),
    IosSubTabEntry(
      "API Keys",
      Icons.Default.Key,
      if (settingsState.useCustomApiProvider) "CUSTOM" else if (configuredKeysCount > 0) "$configuredKeysCount" else "FREE"
    ),
    IosSubTabEntry("Cloud", Icons.Default.CloudDone, if (isSignedIn) "ON" else "OFF"),
    IosSubTabEntry("Engine", Icons.Default.Info, "30-Stg")
  )

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("ios_settings_screen"),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(15.dp)
  ) {
    // Sharp-Edged iOS Squircle Sub-Tab Bar for Settings
    item {
      IosSubTabBar(
        tabs = settingsSubTabs,
        selectedIndex = activeSettingsSubTab,
        onSelect = { activeSettingsSubTab = it },
        tagPrefix = "settings_sub_tab"
      )
    }

    when (activeSettingsSubTab) {
      0 -> {
        item {
          IosGroupedCard(
            header = "iOS Appearance & Display Toggles",
            footer = "Customize Cupertino theme mode, OLED dark surfaces, and equation typography."
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              IosSquircleIconBadge(
                icon = Icons.Default.DarkMode,
                tint = CupertinoIndigo,
                size = 32.dp,
                cornerRadius = 7.dp
              )
              Column {
                Text(
                  text = "iOS Theme Mode",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = "Choose System, Cupertino Light, or OLED Dark",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val themeModes = ThemeModePreference.entries
            IosSegmentedControl(
              items = themeModes.map { it.label },
              selectedIndex = themeModes.indexOf(settingsState.themeMode),
              onSelect = { idx ->
                onUpdateSettings { it.copy(themeMode = themeModes[idx]) }
              }
            )

            Spacer(modifier = Modifier.height(10.dp))
            IosInsetDivider()

            IosSettingToggleRow(
              icon = Icons.Default.DarkMode,
              iconTint = CupertinoIndigo,
              title = "OLED Cupertino Dark Mode",
              subtitle = "Force deep OLED dark appearance across all workbench tabs",
              checked = settingsState.themeMode == ThemeModePreference.IOS_DARK,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(
                    themeMode = if (enabled) ThemeModePreference.IOS_DARK else ThemeModePreference.IOS_LIGHT
                  )
                }
              },
              modifier = Modifier.testTag("toggle_oled_dark_mode")
            )
            IosInsetDivider()

            IosSettingToggleRow(
              icon = Icons.Default.ViewCompact,
              iconTint = CupertinoBlue,
              title = "Compact High-Density Layout",
              subtitle = "Tighten card padding for rapid academic table & rubric scanning",
              checked = settingsState.compactDossierMode,
              onCheckedChange = { enabled ->
                onUpdateSettings { it.copy(compactDossierMode = enabled) }
              },
              modifier = Modifier.testTag("toggle_compact_density")
            )
            IosInsetDivider()

            IosSettingToggleRow(
              icon = Icons.Default.Functions,
              iconTint = CupertinoGreen,
              title = "Monospace LaTeX Equations",
              subtitle = "Render extracted equations and Stage 01e math proofs in JetBrains Mono",
              checked = settingsState.monospaceEquations,
              onCheckedChange = { enabled ->
                onUpdateSettings { it.copy(monospaceEquations = enabled) }
              },
              modifier = Modifier.testTag("toggle_monospace_equations")
            )
            IosInsetDivider()
            Spacer(modifier = Modifier.height(10.dp))

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              IosSquircleIconBadge(
                icon = Icons.Default.FormatSize,
                tint = CupertinoBlue,
                size = 32.dp,
                cornerRadius = 7.dp
              )
              Column {
                Text(
                  text = "Referee Report Text Size",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = "Adjust reading typography in the Output tab",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val scaleValues = listOf(0.92f, 1.0f, 1.14f)
            val scaleLabels = listOf("Compact", "Standard", "Large")
            val currentScaleIdx = scaleValues
              .indexOfFirst { abs(it - settingsState.reportTextScale) < 0.05f }
              .let { if (it == -1) 1 else it }

            IosSegmentedControl(
              items = scaleLabels,
              selectedIndex = currentScaleIdx,
              onSelect = { idx ->
                onUpdateSettings { it.copy(reportTextScale = scaleValues[idx]) }
              }
            )
          }
        }
      }

      1 -> {
        item {
          IosGroupedCard(
            header = "Default Appraisal Mode",
            footer = settingsState.defaultOptions.reviewMode.description
          ) {
            val modes = com.example.data.model.ReviewMode.entries
            IosSegmentedControl(
              items = modes.map { it.shortLabel },
              selectedIndex = modes.indexOf(settingsState.defaultOptions.reviewMode).coerceAtLeast(0),
              onSelect = { idx ->
                onUpdateSettings {
                  it.copy(defaultOptions = it.defaultOptions.copy(reviewMode = modes[idx]))
                }
              },
              modifier = Modifier.testTag("settings_review_mode_selector")
            )
          }
        }

        item {
          IosGroupedCard(
            header = "AI Referee Rigor & Calibration"
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              IosSquircleIconBadge(
                icon = Icons.Default.Gavel,
                tint = CupertinoRed,
                size = 32.dp,
                cornerRadius = 7.dp
              )
              Column {
                Text(
                  text = "Referee Calibration",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold
                )
                Text(
                  text = settingsState.reviewStrictness,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFamily,
                  color = CupertinoBlue,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val strictnessCodes = listOf(
              "AGGRESSIVE_REVIEWER2",
              "BALANCED_AREA_CHAIR",
              "CONSTRUCTIVE_MENTOR"
            )
            val strictnessShortLabels = listOf("Reviewer 2", "Area Chair", "Mentor")
            val selectedStrictIdx = strictnessCodes.indexOf(settingsState.reviewStrictness).coerceAtLeast(0)

            IosSegmentedControl(
              items = strictnessShortLabels,
              selectedIndex = selectedStrictIdx,
              onSelect = { idx ->
                onUpdateSettings { it.copy(reviewStrictness = strictnessCodes[idx]) }
              }
            )
          }
        }

        item {
          IosGroupedCard(
            header = "Default 30-Stage Pipeline Toggles (iOS Switches)",
            footer = "All switches use native-style Cupertino toggles and apply automatically to new uploads."
          ) {
            IosSettingToggleRow(
              icon = Icons.Default.Science,
              iconTint = CupertinoBlue,
              title = "Empirical Paper Track (Stage 01a–01c)",
              subtitle = "Enable Breaker, Butcher & Shredder identification and table audits",
              checked = settingsState.defaultOptions.isEmpirical,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(defaultOptions = it.defaultOptions.copy(isEmpirical = enabled))
                }
              }
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.Functions,
              iconTint = CupertinoIndigo,
              title = "Math Proofreader & Re-Deriver (Stage 01e–01fd)",
              subtitle = "Verify theorem assumptions, summation bounds, and notation",
              checked = settingsState.defaultOptions.enableMathAudit,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(defaultOptions = it.defaultOptions.copy(enableMathAudit = enabled))
                }
              }
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.Code,
              iconTint = CupertinoAmber,
              title = "Code & Split Leakage X-Ray (Stage 01i–01n)",
              subtitle = "Scan for train/test contamination and replication divergences",
              checked = settingsState.defaultOptions.enableCodeAudit,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(defaultOptions = it.defaultOptions.copy(enableCodeAudit = enabled))
                }
              }
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.Gavel,
              iconTint = CupertinoRed,
              title = "Senior Editor Decision Note (Stage 08b)",
              subtitle = "Include 6-Axis Calibrated Rubric Scorecard & editorial verdict memo",
              checked = settingsState.defaultOptions.enableEditorNote,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(defaultOptions = it.defaultOptions.copy(enableEditorNote = enabled))
                }
              }
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.EditNote,
              iconTint = CupertinoBlue,
              title = "Academic Copyedit & De-Fluffer (Stage 09a–09c)",
              subtitle = "Include line-by-line academic copyediting and prose tightening",
              checked = settingsState.defaultOptions.enableCopyeditWriterMode,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(defaultOptions = it.defaultOptions.copy(enableCopyeditWriterMode = enabled))
                }
              }
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.PictureAsPdf,
              iconTint = CupertinoRed,
              title = "Multimodal PDF Page Vision (Stage 00a)",
              subtitle = "Send rendered PDF page bitmaps to Gemini for equation & table inspection",
              checked = settingsState.enablePdfVisionPages,
              onCheckedChange = { enabled ->
                onUpdateSettings { it.copy(enablePdfVisionPages = enabled) }
              }
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.QueryStats,
              iconTint = CupertinoGreen,
              title = "GRIM & Statistical P-Value Forensics (Stage 02a)",
              subtitle = "Verify integer-mean granularity and sample-size attrition across tables",
              checked = settingsState.enableGrimStatsCheck,
              onCheckedChange = { enabled ->
                onUpdateSettings { it.copy(enableGrimStatsCheck = enabled) }
              }
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.Policy,
              iconTint = CupertinoIndigo,
              title = "Stage 06 Legal & Defamation Screen",
              subtitle = "Enforce the 4 legal red lines (no intent, fraud, competence, or absolute claims)",
              checked = settingsState.enableLegalScreen,
              onCheckedChange = { enabled ->
                onUpdateSettings { it.copy(enableLegalScreen = enabled) }
              }
            )
          }
        }
      }

      2 -> {
        item {
          ApiKeysAndProvidersSettingsContent(
            settingsState = settingsState,
            apiProviders = apiProviders,
            isTestingApiKey = isTestingApiKey,
            apiTestResultMessage = apiTestResultMessage,
            anonymousCustomProviderCount = anonymousCustomProviderCount,
            onAdoptAnonymousApiProviders = onAdoptAnonymousApiProviders,
            onUpdateSettings = onUpdateSettings,
            onSelectActiveProvider = onSelectActiveProvider,
            onSaveProvider = onSaveProvider,
            onAddCustomProvider = onAddCustomProvider,
            onDeleteCustomProvider = onDeleteCustomProvider,
            onTestProviderConnection = onTestProviderConnection
          )
        }
      }

      3 -> {
        item {
          IosGroupedCard(
            header = "Google Account & Cross-Device Portfolio Sync"
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              IosSquircleIconBadge(
                icon = Icons.Default.AccountCircle,
                tint = if (isSignedIn) CupertinoGreen else CupertinoBlue,
                size = 44.dp,
                cornerRadius = 9.dp
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = if (isSignedIn) {
                    userDisplayName.ifBlank { userEmail.ifBlank { "Authenticated Researcher" } }
                  } else {
                    "Google Sign-In • Portfolio Sync"
                  },
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Bold
                )
                if (isSignedIn && userEmail.isNotBlank() && userDisplayName.isNotBlank()) {
                  Text(
                    text = userEmail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Icon(
                    imageVector = if (isSignedIn) Icons.Default.CloudDone else Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = if (isSignedIn) CupertinoGreen else CupertinoBlue,
                    modifier = Modifier.size(14.dp)
                  )
                  Text(
                    text = if (isSignedIn) {
                      "Synced across devices ($syncedReviewCount cloud dossier${if (syncedReviewCount == 1) "" else "s"})"
                    } else if (unsyncedLocalCount > 0) {
                      "$unsyncedLocalCount local dossier(s) ready to sync to Google Cloud"
                    } else {
                      "Sign in with Google to sync dossiers across devices"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSignedIn) CupertinoGreen else CupertinoBlue,
                    fontWeight = FontWeight.SemiBold
                  )
                }
              }
            }

            if (authError != null) {
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = authError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = CupertinoRed
              )
            }

            Spacer(modifier = Modifier.height(10.dp))
            IosInsetDivider()

            IosSettingToggleRow(
              icon = Icons.Default.CloudDone,
              iconTint = CupertinoGreen,
              title = "Auto-Save Reports to Cloud Library",
              subtitle = "Automatically persist every analysed paper to Firebase Firestore",
              checked = settingsState.autoSaveToCloud && !settingsState.confidentialLocalOnly,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(
                    autoSaveToCloud = enabled,
                    confidentialLocalOnly = if (enabled) false else it.confidentialLocalOnly
                  )
                }
              }
            )

            IosInsetDivider()

            IosSettingToggleRow(
              icon = Icons.Default.Policy,
              iconTint = CupertinoAmber,
              title = "Local-Only Report Storage",
              subtitle = "Store saved reports strictly on this device (no Firestore upload or cloud backup). AI analysis still sends manuscript text to your selected AI provider.",
              checked = settingsState.confidentialLocalOnly,
              onCheckedChange = { enabled ->
                onUpdateSettings {
                  it.copy(
                    confidentialLocalOnly = enabled,
                    autoSaveToCloud = if (enabled) false else it.autoSaveToCloud
                  )
                }
              },
              modifier = Modifier.testTag("toggle_confidential_local_only")
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isSignedIn) {
              IosSquircleButton(
                text = if (isSyncingPortfolio) "Syncing Portfolio..." else "Sync Portfolio Across Devices",
                onClick = onSyncPortfolioNow,
                enabled = !isSyncingPortfolio,
                icon = Icons.Default.CloudSync,
                containerColor = CupertinoBlue,
                cornerRadius = 9.dp,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("settings_sync_portfolio_button")
              )
              if (adoptableAnonymousCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                IosSecondarySquircleButton(
                  text = "Adopt $adoptableAnonymousCount Local Signed-Out Draft(s)",
                  onClick = onAdoptAnonymousReviews,
                  icon = Icons.Default.FolderOpen,
                  tintColor = CupertinoBlue,
                  cornerRadius = 9.dp,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_adopt_anonymous_button")
                )
              }
              Spacer(modifier = Modifier.height(8.dp))
              IosSecondarySquircleButton(
                text = "Sign Out of Google Account",
                onClick = onSignOut,
                icon = Icons.AutoMirrored.Filled.Logout,
                tintColor = CupertinoRed,
                cornerRadius = 9.dp,
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("settings_sign_out_button")
              )
            } else {
              GoogleSignInButton(
                onAuthSuccess = { authError = null },
                onAuthError = { authError = it }
              )
            }
          }
        }

        item {
          IosGroupedCard(header = "Workbench Session Maintenance") {
            IosSecondarySquircleButton(
              text = "Reset Current Manuscript Input Form",
              onClick = onClearDraft,
              icon = Icons.Default.Refresh,
              tintColor = CupertinoBlue,
              cornerRadius = 9.dp,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_reset_draft_button")
            )
          }
        }
      }

      4 -> {
        item {
          ReviewerCalibrationInstrumentCard()
        }

        item {
          IosGroupedCard(
            header = "30-Stage Reviewer2 + PhD-Skills Architecture"
          ) {
            IosSegmentedControl(
              items = listOf("Red Team (00–01n)", "Blue & Check (02–03c)", "Editor & Copy (04–10)"),
              selectedIndex = activeSpecsCluster,
              onSelect = { activeSpecsCluster = it }
            )
            Spacer(modifier = Modifier.height(10.dp))
            PipelineSpecsSummaryList(clusterIndex = activeSpecsCluster)
          }
        }
      }
    }
  }
}

@Composable
private fun ReviewerCalibrationInstrumentCard() {
  var report by remember { mutableStateOf<ReviewerCalibrationBenchmark.CalibrationSummaryReport?>(null) }
  var isRunning by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val palette = LocalNeumorphicPalette.current

  IosGroupedCard(
    header = "Embedded Regression Suite (Not External Calibration)",
    footer = "Internal diagnostic verification against embedded unit & adversarial fixtures. Tracks explicit positive/negative labels, Wilson 95% CIs, strict quote anchors, and 10-perturbation noise stability (external blinded corpus pending)."
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      IosSquircleIconBadge(
        icon = Icons.Default.QueryStats,
        tint = CupertinoIndigo,
        size = 36.dp,
        cornerRadius = 8.dp
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Embedded Epistemic & Statistical Regression Suite",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Explicit label matrix, strict quote-anchor checks, 10-perturbation noise suite, and Wilson 95% CIs.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    IosSquircleButton(
      text = if (isRunning) "Running Regression Suite..." else if (report == null) "Run Embedded Regression Suite" else "Re-Run Regression Suite",
      onClick = {
        if (!isRunning) {
          isRunning = true
          scope.launch {
            report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
              ReviewerCalibrationBenchmark.runCalibration()
            }
            isRunning = false
          }
        }
      },
      enabled = !isRunning,
      icon = Icons.Default.Science,
      containerColor = CupertinoIndigo,
      cornerRadius = 9.dp,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("run_calibration_button")
    )

    report?.let { summary ->
      Spacer(modifier = Modifier.height(12.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("calibration_results_panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = summary.honestCalibrationNotice,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = if (summary.allEmbeddedFixturesPassed) CupertinoGreen else CupertinoAmber,
          modifier = Modifier.testTag("calibration_honest_notice")
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          CalibrationMetricTile(
            label = "PRECISION",
            value = "${(summary.precision * 100).toInt()}%",
            tint = if (summary.precision >= 0.99) CupertinoGreen else CupertinoAmber,
            modifier = Modifier.weight(1f)
          )
          CalibrationMetricTile(
            label = "RECALL",
            value = "${(summary.recall * 100).toInt()}%",
            tint = if (summary.recall >= 0.99) CupertinoGreen else CupertinoAmber,
            modifier = Modifier.weight(1f)
          )
          CalibrationMetricTile(
            label = "FPR (${summary.explicitNegativeLabelCount} NEG)",
            value = "${summary.totalFalsePositives}/${summary.explicitNegativeLabelCount}",
            tint = if (summary.totalFalsePositives == 0) CupertinoGreen else CupertinoRed,
            modifier = Modifier.weight(1f)
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          CalibrationMetricTile(
            label = "DESIGN ACC.",
            value = "${(summary.designClassificationAccuracy * 100).toInt()}%",
            tint = if (summary.designClassificationAccuracy >= 0.99) CupertinoGreen else CupertinoAmber,
            modifier = Modifier.weight(1f)
          )
          CalibrationMetricTile(
            label = "STRICT ANCHOR",
            value = "${(summary.evidenceAnchorAccuracy * 100).toInt()}%",
            tint = if (summary.evidenceAnchorAccuracy >= 0.99) CupertinoBlue else CupertinoAmber,
            modifier = Modifier.weight(1f)
          )
          CalibrationMetricTile(
            label = "VERDICT MATCH",
            value = "${(summary.verdictAgreementRate * 100).toInt()}%",
            tint = if (summary.verdictAgreementRate >= 0.99) CupertinoGreen else CupertinoAmber,
            modifier = Modifier.weight(1f)
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          CalibrationMetricTile(
            label = "SCORE STABLE",
            value = "${(summary.scoreStabilityUnderNoiseRate * 100).toInt()}%",
            tint = if (summary.scoreStabilityUnderNoiseRate >= 0.99) CupertinoGreen else CupertinoAmber,
            modifier = Modifier.weight(1f)
          )
          CalibrationMetricTile(
            label = "FINDING STABLE",
            value = "${(summary.findingStabilityUnderNoiseRate * 100).toInt()}%",
            tint = if (summary.findingStabilityUnderNoiseRate >= 0.99) CupertinoGreen else CupertinoAmber,
            modifier = Modifier.weight(1f)
          )
          CalibrationMetricTile(
            label = "OCR 0-FALSE-VER",
            value = "${(summary.ocrZeroFalseVerifiedRate * 100).toInt()}%",
            tint = if (summary.ocrZeroFalseVerifiedRate >= 0.99) CupertinoBlue else CupertinoRed,
            modifier = Modifier.weight(1f)
          )
        }
        Text(
          text = "Wilson 95% CIs — Precision: ${summary.precisionCi.formatPercentWithCi()} • Recall: ${summary.recallCi.formatPercentWithCi()} • FPR: ${summary.fprCi.formatPercentWithCi()}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          fontSize = 9.5.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        summary.caseResults.forEach { caseRes ->
          val passed = caseRes.falsePositives == 0 &&
            caseRes.falseNegatives == 0 &&
            caseRes.severityCalibrated &&
            caseRes.verdictMatched
          val badgeColor = if (passed) CupertinoGreen else CupertinoRed
          val rowShape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.88f)
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .neuRecessed(
                cornerRadius = 9.dp,
                depth = 2.dp,
                cavityColor = palette.surfaceRecessed
              )
              .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "${caseRes.caseId} • ${caseRes.title}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
              )
              Box(
                modifier = Modifier
                  .clip(rowShape)
                  .background(badgeColor.copy(alpha = 0.14f))
                  .border(0.6.dp, badgeColor.copy(alpha = 0.40f), rowShape)
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text(
                  text = if (passed) "PASS" else "DRIFT",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = JetBrainsMonoFamily,
                  fontSize = 9.sp,
                  fontWeight = FontWeight.ExtraBold,
                  color = badgeColor
                )
              }
            }
            Text(
              text = "Verdict: ${caseRes.resolvedVerdict.replace('_', ' ')} (Expert: ${caseRes.expertReferenceVerdict.replace('_', ' ')}) • TP=${caseRes.truePositives} FP=${caseRes.falsePositives} FN=${caseRes.falseNegatives}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 9.5.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CalibrationMetricTile(
  label: String,
  value: String,
  tint: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier
) {
  val palette = LocalNeumorphicPalette.current
  Column(
    modifier = modifier
      .neuRecessed(
        cornerRadius = 11.dp,
        depth = 2.2.dp,
        cavityColor = palette.surfaceRecessed
      )
      .padding(vertical = 8.dp, horizontal = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontSize = 8.5.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.titleSmall,
      fontFamily = JetBrainsMonoFamily,
      fontWeight = FontWeight.ExtraBold,
      color = tint
    )
  }
}

private data class StageSpecItem(
  val code: String,
  val title: String,
  val description: String
)

@Composable
private fun PipelineSpecsSummaryList(clusterIndex: Int) {
  val cluster0 = listOf(
    StageSpecItem(
      "STAGE 00a • 00b • 00c",
      "Ingestion, Clean Metadata & Claimed Contributions",
      "Extracts title, discipline, research question, and numbered contribution targets [C1..Cn] + runs deterministic forensic pre-flight."
    ),
    StageSpecItem(
      "STAGE 01a • 01b • 01c • 01d • 01g",
      "Red Team: The Breaker, The Butcher, The Shredder, The Collector & The Void",
      "Hostile multi-agent interrogation of theoretical premises (01a), empirical design (01b), procedural/split forensics (01c), footnotes/tables (01d), and silent confounds (01g)."
    ),
    StageSpecItem(
      "STAGE 01e • 01fa • 01fb • 01fc • 01fd",
      "Math Error Finder, The Re-Deriver, Proofreader, Auditor & Sober Checker",
      "Re-derives load-bearing equations from scratch, audits summation/integration bounds, checks regularity assumptions, and filters OCR math false positives."
    ),
    StageSpecItem(
      "STAGE 01i–01n • 07b",
      "Divergence Hunter, Code Gonzo & Data Editor X-Ray",
      "Detects train/validation/test split contamination, entity overlap, hyperparameter peeking on test sets, and code-to-paper divergence."
    )
  )

  val cluster1 = listOf(
    StageSpecItem(
      "STAGE 02a • 02b • 02c",
      "Numbers Verifier & Blue Team Defense",
      "Verifies quantitative/GRIM claims (02a), checks sample attrition across tables, and formulates the strongest author-advocate Blue Team defense (02c)."
    ),
    StageSpecItem(
      "STAGE 02d • 02e • 02f • 02g",
      "Adversarial Clash Assessment & List v1",
      "Adjudicates every Red Team attack against the Blue Team defense and drops refuted or speculative criticisms."
    ),
    StageSpecItem(
      "STAGE 03a • 03b • 03c",
      "Fact Checker, External Citation Check & Verified List v2",
      "Cross-checks surviving critiques against exact manuscript quotes, equations, and sections to produce severity-graded List v2 ([CRITICAL], [MAJOR], [MINOR])."
    )
  )

  val cluster2 = listOf(
    StageSpecItem(
      "STAGE 04a • 05a–05c • 06 • 07",
      "The Reviewer ('Is It Credible?'), Review Checker & Stage 06 Legal Pass",
      "Drafts the 'Is It Credible?' essay, 'The Bottom Line', and 'Future Research', then screens against the 4 legal red lines (intent, fraud, competence, absolutes)."
    ),
    StageSpecItem(
      "STAGE 08a • 08b",
      "Alchemist Revision Strategy & Senior Editor Decision Memo",
      "Formulates concrete author revision experiments and calibrates the 6-Axis Paper Review Rubric Scorecard."
    ),
    StageSpecItem(
      "STAGE 09a • 09b • 09c • 10_render",
      "Proofreader, Proofread Clean, Copyedit & Plain-Text Dossier",
      "Delivers line-by-line academic copyediting separated from OCR artifacts and renders the zero-markdown ASCII referee dossier."
    )
  )

  val visibleStages = when (clusterIndex) {
    0 -> cluster0
    1 -> cluster1
    else -> cluster2
  }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    visibleStages.forEach { stage ->
      val shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(shape)
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
          .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape)
          .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
      ) {
        Text(
          text = stage.code,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          fontWeight = FontWeight.Bold,
          color = CupertinoBlue
        )
        Text(
          text = stage.title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = stage.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}
