package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.ai.ForensicPreFlightReport
import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.model.BenchmarkManuscript
import com.example.data.model.BenchmarkManuscriptCatalog
import com.example.data.model.PaperReview
import com.example.data.model.PipelineOptions
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
import com.example.ui.theme.AmberGold400
import com.example.ui.theme.CupertinoAmber
import com.example.ui.theme.CupertinoBlue
import com.example.ui.theme.CupertinoGreen
import com.example.ui.theme.CupertinoIndigo
import com.example.ui.theme.CupertinoRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SlateNavy900
import com.example.ui.viewmodel.PipelineExecutionState
import com.example.ui.viewmodel.WorkbenchFormState
import com.example.util.DossierFormattingEngine

@Composable
fun WorkbenchScreen(
  formState: WorkbenchFormState,
  preFlight: ForensicPreFlightReport? = null,
  pipelineState: PipelineExecutionState,
  onInputModeChange: (Int) -> Unit,
  onTitleChange: (String) -> Unit,
  onAuthorsChange: (String) -> Unit,
  onYearChange: (String) -> Unit,
  onVenueChange: (String) -> Unit,
  onManuscriptTextChange: (String) -> Unit,
  onReplicationCodeChange: (String) -> Unit = {},
  onOptionsChange: ((PipelineOptions) -> PipelineOptions) -> Unit,
  onSelectBenchmark: (BenchmarkManuscript) -> Unit,
  onClearForm: () -> Unit,
  onImportDocumentUri: (Uri) -> Unit,
  onImportReplicationCodeUri: (Uri) -> Unit = {},
  onRunPipeline: () -> Unit,
  onCancelPipeline: () -> Unit = {},
  onOpenCompletedDossier: (PaperReview) -> Unit
) {
  // Zero-permission Storage Access Framework launchers for PDF, Word (.docx/.doc), Text, and Replication Code
  val pdfPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) onImportDocumentUri(uri)
  }

  val wordPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) onImportDocumentUri(uri)
  }

  val textPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) onImportDocumentUri(uri)
  }

  val universalPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) onImportDocumentUri(uri)
  }

  val codePackagePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri != null) onImportReplicationCodeUri(uri)
  }

  // Active sub-tab on the Upload Workbench to eliminate long vertical scrolling:
  // 0 = Import Source, 1 = Manuscript Editor, 2 = Forensic Pre-Flight, 3 = Audit Toggles & Run
  var activeWorkbenchSubTab by rememberSaveable { mutableIntStateOf(0) }
  var activeForensicSubSegment by rememberSaveable { mutableIntStateOf(0) }

  val resolvedPreFlight = preFlight ?: remember(
    formState.manuscriptText,
    formState.options.isEmpirical,
    formState.replicationCodeText
  ) {
    ManuscriptForensicEngine.analyzeManuscript(
      text = formState.manuscriptText,
      isEmpiricalTrack = formState.options.isEmpirical,
      replicationCode = formState.replicationCodeText
    )
  }

  val quantIssueCount = resolvedPreFlight.quantForensics.criticalDiscrepancyCount +
    resolvedPreFlight.quantForensics.warningCount
  val forensicAlertCount = resolvedPreFlight.proceduralLeakageAlerts.size +
    resolvedPreFlight.grimAndNumericAlerts.size +
    resolvedPreFlight.symbolAndNotationAlerts.size +
    quantIssueCount

  val activeModuleCount = listOf(
    formState.options.isEmpirical,
    formState.options.enableMathAudit,
    formState.options.enableCodeAudit,
    formState.options.enableEditorNote,
    formState.options.enableCopyeditWriterMode
  ).count { it }

  val subTabs = listOf(
    IosSubTabEntry(
      title = "1. Import File",
      icon = Icons.Default.UploadFile,
      badge = formState.importedFormat.displayBadge
    ),
    IosSubTabEntry(
      title = "2. Paper Editor",
      icon = Icons.Default.EditNote,
      badge = "${formState.importedWordCount}w"
    ),
    IosSubTabEntry(
      title = "3. Forensic Scan",
      icon = Icons.Default.Radar,
      badge = if (forensicAlertCount > 0) "$forensicAlertCount!" else "OK"
    ),
    IosSubTabEntry(
      title = "4. Audit & Run",
      icon = Icons.Default.Tune,
      badge = "$activeModuleCount/5"
    )
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Museum-Grade Neo-Tactile Sculpted Executive Hero Card
    // Inspired directly by the Neumorphism styleguide: soft monochromatic depth,
    // high-contrast editorial ink, sculpted corner counter medallion ("30 STAGES"),
    // and a carved recessed 4-column telemetry bar.
    val palette = LocalNeumorphicPalette.current
    val heroRadius = 22.dp
    val heroShape = SquircleShape(cornerRadius = heroRadius, smoothing = 0.86f)
    val isRunningNow = pipelineState is PipelineExecutionState.Running

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 2.dp, vertical = 2.dp)
        .neuRaised(
          cornerRadius = heroRadius,
          elevation = 8.dp,
          surfaceColor = palette.surfaceElevated
        )
        .clip(heroShape)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top
        ) {
          Column(
            modifier = Modifier
              .weight(1f)
              .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "SOFT LIGHT × ADVERSARIAL RIGOR",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 9.5.sp,
              letterSpacing = 1.4.sp,
              fontWeight = FontWeight.Bold,
              color = CupertinoBlue
            )
            Text(
              text = if (formState.title.isBlank()) {
                "Paper Review Workbench"
              } else {
                DossierFormattingEngine.normalizeTitle(formState.title)
              },
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.ExtraBold,
              letterSpacing = (-0.4).sp,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "${DossierFormattingEngine.formatCleanCitation(formState.authors, formState.year, "", fallback = resolvedPreFlight.detectedDiscipline)} • ${resolvedPreFlight.studyDesign.displayName}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          // Sculpted Corner Medallion modeled after the "DAY 08" Neumorphic tile
          val tileRadius = 15.dp
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
              .size(width = 66.dp, height = 66.dp)
              .neuRaised(
                cornerRadius = tileRadius,
                elevation = 5.dp,
                surfaceColor = palette.surfaceRaised
              )
              .padding(6.dp)
          ) {
            Text(
              text = "STAGES",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 8.sp,
              letterSpacing = 0.8.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
              text = "30",
              style = MaterialTheme.typography.headlineSmall,
              fontFamily = JetBrainsMonoFamily,
              fontWeight = FontWeight.ExtraBold,
              color = CupertinoBlue
            )
          }
        }

        // Action & Format Pill Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .neuRecessed(
                cornerRadius = 10.dp,
                depth = 2.dp,
                cavityColor = palette.surfaceRecessed
              )
              .padding(horizontal = 10.dp, vertical = 5.5.dp)
          ) {
            Text(
              text = "PDF • WORD • LATEX • CODE",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 9.sp,
              letterSpacing = 0.5.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          val runPillRadius = 11.dp
          val runPillShape = SquircleShape(cornerRadius = runPillRadius, smoothing = 0.86f)
          Box(
            modifier = Modifier
              .then(
                if (isRunningNow) {
                  Modifier.neuRecessed(
                    cornerRadius = runPillRadius,
                    depth = 2.5.dp,
                    cavityColor = CupertinoBlue.copy(alpha = 0.2f)
                  )
                } else {
                  Modifier.neuRaised(
                    cornerRadius = runPillRadius,
                    elevation = 4.dp,
                    surfaceColor = CupertinoBlue,
                    accentGlowColor = CupertinoBlue.copy(alpha = 0.35f)
                  )
                }
              )
              .clip(runPillShape)
              .clickable(enabled = !isRunningNow) { onRunPipeline() }
              .padding(horizontal = 13.dp, vertical = 6.5.dp)
              .testTag("hero_quick_run_audit_button")
          ) {
            Text(
              text = if (isRunningNow) "RUNNING..." else "▶ RUN AUDIT",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = if (isRunningNow) CupertinoBlue else Color.White
            )
          }
        }

        // Carved Recessed 4-Column Live Telemetry Bar
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .neuRecessed(
              cornerRadius = 13.dp,
              depth = 2.8.dp,
              cavityColor = palette.surfaceRecessed
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          HeroTelemetryMetric("FORMAT", formState.importedFormat.displayBadge, CupertinoBlue)
          HeroTelemetryMetric("WORDS", "${resolvedPreFlight.wordCount}", MaterialTheme.colorScheme.onSurface)
          HeroTelemetryMetric("QUANT", "${resolvedPreFlight.quantForensics.diagnosticTable.size}", CupertinoIndigo)
          HeroTelemetryMetric(
            label = "SCAN",
            value = if (forensicAlertCount == 0) "CLEAN" else "$forensicAlertCount FLAGS",
            tint = if (forensicAlertCount == 0) CupertinoGreen else CupertinoAmber
          )
        }
      }
    }

    // Sharp-Edged iOS Squircle Sub-Tab Bar for the Upload Workbench
    IosSubTabBar(
      tabs = subTabs,
      selectedIndex = activeWorkbenchSubTab,
      onSelect = { activeWorkbenchSubTab = it },
      tagPrefix = "workbench_sub_tab"
    )

    // Sub-Tab Content (eliminates long single-page scrolling)
    when (activeWorkbenchSubTab) {
      0 -> {
        IosGroupedCard(
          header = "1. Paper Input Source (PDF / Word / Text)"
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IosSegmentedControl(
              items = listOf("PDF / Word File", "Paste / Type Text", "Sample Papers"),
              selectedIndex = formState.inputModeIndex,
              onSelect = { modeIdx ->
                onInputModeChange(modeIdx)
                if (modeIdx == 1) {
                  activeWorkbenchSubTab = 1
                }
              },
              modifier = Modifier.testTag("input_source_segmented_control")
            )

            when (formState.inputModeIndex) {
              0 -> {
                Text(
                  text = "Choose your manuscript file format to parse and inspect:",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  FormatUploadSquircleTile(
                    title = "PDF Paper",
                    subtitle = ".pdf + Vision",
                    icon = Icons.Default.PictureAsPdf,
                    accentColor = CupertinoRed,
                    onClick = {
                      pdfPickerLauncher.launch(arrayOf("application/pdf"))
                    },
                    modifier = Modifier
                      .weight(1f)
                      .testTag("import_pdf_squircle_button")
                  )
                  FormatUploadSquircleTile(
                    title = "Word Doc",
                    subtitle = ".docx / .doc",
                    icon = Icons.Default.Description,
                    accentColor = CupertinoBlue,
                    onClick = {
                      wordPickerLauncher.launch(
                        arrayOf(
                          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                          "application/msword",
                          "application/rtf",
                          "*/*"
                        )
                      )
                    },
                    modifier = Modifier
                      .weight(1f)
                      .testTag("import_word_squircle_button")
                  )
                  FormatUploadSquircleTile(
                    title = "Plain Text",
                    subtitle = ".txt / .tex",
                    icon = Icons.AutoMirrored.Filled.Article,
                    accentColor = CupertinoIndigo,
                    onClick = {
                      textPickerLauncher.launch(
                        arrayOf("text/plain", "text/markdown", "application/x-tex", "*/*")
                      )
                    },
                    modifier = Modifier
                      .weight(1f)
                      .testTag("import_text_squircle_button")
                  )
                }

                Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  IosSecondarySquircleButton(
                    text = "Browse All Files",
                    icon = Icons.Default.UploadFile,
                    onClick = {
                      universalPickerLauncher.launch(
                        arrayOf(
                          "application/pdf",
                          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                          "application/msword",
                          "text/plain",
                          "text/markdown",
                          "*/*"
                        )
                      )
                    },
                    modifier = Modifier
                      .weight(1f)
                      .testTag("import_manuscript_button")
                  )
                  IosSecondarySquircleButton(
                    text = "Clear",
                    icon = Icons.Default.Clear,
                    tintColor = CupertinoRed,
                    onClick = onClearForm,
                    modifier = Modifier.testTag("clear_manuscript_button")
                  )
                }
              }

              1 -> {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Text(
                    text = "Switch to the '2. Paper Editor' sub-tab to paste or edit your manuscript text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  IosSecondarySquircleButton(
                    text = "Open Editor",
                    icon = Icons.Default.EditNote,
                    tintColor = CupertinoBlue,
                    onClick = { activeWorkbenchSubTab = 1 }
                  )
                }
              }

              2 -> {
                Text(
                  text = "Tap any benchmark paper to load its full abstract, equations, and empirical setup:",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  BenchmarkManuscriptCatalog.samples.forEachIndexed { index, sample ->
                    val isSelected = formState.title == sample.title
                    val cardShape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f)
                    Surface(
                      color = if (isSelected) {
                        CupertinoBlue.copy(alpha = 0.12f)
                      } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                      },
                      shape = cardShape,
                      modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .border(
                          width = if (isSelected) 1.dp else 0.5.dp,
                          color = if (isSelected) CupertinoBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                          shape = cardShape
                        )
                        .clickable { onSelectBenchmark(sample) }
                        .testTag("benchmark_sample_card_$index")
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.padding(11.dp)
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          Text(
                            text = sample.badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = CupertinoBlue,
                            fontWeight = FontWeight.Bold
                          )
                          Text(
                            text = sample.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                          )
                          Text(
                            text = "${sample.authors} (${sample.year}) • ${sample.venue}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        IosSquircleIconBadge(
                          icon = Icons.Default.Description,
                          tint = CupertinoBlue,
                          size = 32.dp,
                          cornerRadius = 7.dp
                        )
                      }
                    }
                  }
                }
              }
            }

            // Loaded Document Status Banner + PDF Page Thumbnails
            if (formState.importedFileBanner != null) {
              val bannerShape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f)
              Surface(
                color = CupertinoGreen.copy(alpha = 0.10f),
                shape = bannerShape,
                modifier = Modifier
                  .fillMaxWidth()
                  .border(0.6.dp, CupertinoGreen.copy(alpha = 0.35f), bannerShape)
              ) {
                Column(
                  modifier = Modifier.padding(11.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.CheckCircle,
                      contentDescription = null,
                      tint = CupertinoGreen,
                      modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = formState.importedFileBanner,
                        style = MaterialTheme.typography.labelMedium,
                        color = CupertinoGreen,
                        fontWeight = FontWeight.Bold
                      )
                      Text(
                        text = "Format: ${formState.importedFormat.displayBadge} • ${formState.importedWordCount} words • ~${formState.importedPageCount} page(s)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                    IosSecondarySquircleButton(
                      text = "Edit Text",
                      onClick = { activeWorkbenchSubTab = 1 },
                      tintColor = CupertinoBlue
                    )
                  }

                  if (formState.pdfPageBitmaps.isNotEmpty()) {
                    Text(
                      text = "Rendered PDF Pages Attached for Gemini Vision:",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                      modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                    ) {
                      formState.pdfPageBitmaps.forEachIndexed { pageIndex, bmp ->
                        val pageShape = SquircleShape(cornerRadius = 7.dp, smoothing = 0.88f)
                        Surface(
                          shape = pageShape,
                          shadowElevation = 2.dp,
                          modifier = Modifier.size(width = 72.dp, height = 98.dp)
                        ) {
                          Box {
                            Image(
                              bitmap = bmp.asImageBitmap(),
                              contentDescription = "PDF Page ${pageIndex + 1}",
                              contentScale = ContentScale.Crop,
                              modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                              color = SlateNavy900.copy(alpha = 0.82f),
                              shape = SquircleShape(cornerRadius = 4.dp, smoothing = 0.88f),
                              modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                            ) {
                              Text(
                                text = "P${pageIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFamily,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                              )
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }

            IosInsetDivider()

            // Stage 00_prep_code / --code-dir Replication Code Attachment
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
              ) {
                IosSquircleIconBadge(
                  icon = Icons.Default.Code,
                  tint = CupertinoGreen,
                  size = 32.dp,
                  cornerRadius = 7.dp
                )
                Column {
                  Text(
                    text = "Replication Code (--code-dir)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                  )
                  Text(
                    text = formState.replicationCodeFileName
                      ?: "Attach .py, .R, .do, .ipynb, or .zip scripts for Stage 01i",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = if (formState.replicationCodeFileName != null) CupertinoGreen else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              Spacer(modifier = Modifier.width(8.dp))
              IosSecondarySquircleButton(
                text = if (formState.replicationCodeText.isNotBlank()) "Replace" else "Attach Code",
                icon = Icons.Default.Code,
                tintColor = CupertinoGreen,
                onClick = {
                  codePackagePickerLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier.testTag("attach_replication_code_button")
              )
            }
          }
        }
      }

      1 -> {
        IosGroupedCard(
          header = "2. Manuscript Metadata & Text Stream",
          footer = "Edit extracted paper metadata, equations, or replication code before running the 30-stage review."
        ) {
          val fieldShape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f)
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
              value = formState.title,
              onValueChange = onTitleChange,
              label = { Text(stringResource(R.string.label_manuscript_title)) },
              shape = fieldShape,
              singleLine = true,
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CupertinoBlue,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
              ),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manuscript_title_input")
            )

            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              OutlinedTextField(
                value = formState.authors,
                onValueChange = onAuthorsChange,
                label = { Text(stringResource(R.string.label_manuscript_authors)) },
                shape = fieldShape,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = CupertinoBlue,
                  focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                  unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
                ),
                modifier = Modifier
                  .weight(1f)
                  .testTag("manuscript_authors_input")
              )
              OutlinedTextField(
                value = formState.year,
                onValueChange = onYearChange,
                label = { Text(stringResource(R.string.label_manuscript_year)) },
                shape = fieldShape,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = CupertinoBlue,
                  focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                  unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
                ),
                modifier = Modifier
                  .width(98.dp)
                  .testTag("manuscript_year_input")
              )
            }

            OutlinedTextField(
              value = formState.venueOrCitation,
              onValueChange = onVenueChange,
              label = { Text(stringResource(R.string.label_manuscript_venue)) },
              shape = fieldShape,
              singleLine = true,
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CupertinoBlue,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
              ),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manuscript_venue_input")
            )

            OutlinedTextField(
              value = formState.manuscriptText,
              onValueChange = onManuscriptTextChange,
              label = { Text(stringResource(R.string.label_manuscript_body)) },
              shape = fieldShape,
              minLines = 8,
              maxLines = 16,
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CupertinoBlue,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f)
              ),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manuscript_body_input")
            )

            if (formState.replicationCodeText.isNotBlank()) {
              OutlinedTextField(
                value = formState.replicationCodeText,
                onValueChange = onReplicationCodeChange,
                label = { Text("Replication Scripts (Stage 00_prep_code -> 01i–01n Code Gonzo)") },
                shape = fieldShape,
                minLines = 4,
                maxLines = 9,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = CupertinoGreen
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("replication_code_input")
              )
            }
          }
        }
      }

      2 -> {
        val quantDiag = resolvedPreFlight.quantForensics
        IosGroupedCard(
          header = "3. Deterministic Pre-Flight Forensic & Quantitative Scan (00a / 01e / 02a)",
          footer = "Computed off-main-thread. Exact statcheck, CI geometry, IQR skew & leakage findings are automatically injected into Pass 1 & Pass 2A."
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Domain: ${resolvedPreFlight.detectedDiscipline}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CupertinoBlue,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = "${resolvedPreFlight.wordCount} words",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            // Detected Study Design Archetype pill
            val archetypeShape = SquircleShape(cornerRadius = 7.dp, smoothing = 0.88f)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(archetypeShape)
                .background(CupertinoIndigo.copy(alpha = 0.10f))
                .border(0.6.dp, CupertinoIndigo.copy(alpha = 0.30f), archetypeShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(
                text = "STUDY DESIGN",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = CupertinoIndigo
              )
              Text(
                text = resolvedPreFlight.studyDesign.displayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              ForensicStatTile(
                value = "${quantDiag.diagnosticTable.size}",
                label = "Quant Checks",
                tint = CupertinoIndigo,
                modifier = Modifier.weight(1f)
              )
              ForensicStatTile(
                value = "${resolvedPreFlight.extractedSampleSizes.size + resolvedPreFlight.statisticalClaims.size}",
                label = "Stats & N (02a)",
                tint = CupertinoBlue,
                modifier = Modifier.weight(1f)
              )
              ForensicStatTile(
                value = "$forensicAlertCount",
                label = "Forensic Flags",
                tint = if (forensicAlertCount > 0) CupertinoRed else CupertinoGreen,
                modifier = Modifier.weight(1f)
              )
            }

            IosSegmentedControl(
              items = listOf(
                "Quant (${quantDiag.diagnosticTable.size})",
                "Flags ($forensicAlertCount)",
                "Math (${resolvedPreFlight.extractedEquations.size})",
                "Refs"
              ),
              selectedIndex = activeForensicSubSegment.coerceIn(0, 3),
              onSelect = { activeForensicSubSegment = it }
            )

            when (activeForensicSubSegment.coerceIn(0, 3)) {
              0 -> {
                if (quantDiag.diagnosticTable.isEmpty()) {
                  Text(
                    text = "No inline test statistics, 95% CIs, or Median/IQR pairs matched yet. Full 8-pillar statistical audit will still execute during analysis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                } else {
                  Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    quantDiag.diagnosticTable.take(12).forEach { finding ->
                      val accent = when (finding.status) {
                        QuantFindingStatus.PASS -> CupertinoGreen
                        QuantFindingStatus.WARN -> CupertinoAmber
                        QuantFindingStatus.FAIL -> CupertinoRed
                        QuantFindingStatus.INFO -> CupertinoBlue
                      }
                      val cardShape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.88f)
                      Column(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(cardShape)
                          .background(accent.copy(alpha = 0.08f))
                          .border(0.6.dp, accent.copy(alpha = 0.30f), cardShape)
                          .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                      ) {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Text(
                            text = "${finding.category} • ${finding.metricOrCheck}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                          )
                          val pillShape = SquircleShape(cornerRadius = 4.dp, smoothing = 0.88f)
                          Box(
                            modifier = Modifier
                              .clip(pillShape)
                              .background(accent)
                              .padding(horizontal = 6.dp, vertical = 2.dp)
                          ) {
                            Text(
                              text = finding.status.name,
                              style = MaterialTheme.typography.labelSmall,
                              fontFamily = JetBrainsMonoFamily,
                              fontSize = 9.sp,
                              fontWeight = FontWeight.Bold,
                              color = Color.White
                            )
                          }
                        }
                        if (finding.reportedEvidence.isNotBlank()) {
                          Text(
                            text = finding.reportedEvidence,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = CupertinoBlue
                          )
                        }
                        Text(
                          text = finding.diagnosticDetail,
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurface
                        )
                      }
                    }
                  }
                }
              }

              1 -> {
                val allFlags = resolvedPreFlight.proceduralLeakageAlerts +
                  resolvedPreFlight.grimAndNumericAlerts +
                  resolvedPreFlight.symbolAndNotationAlerts +
                  quantDiag.powerAndMultiplicityAlerts +
                  quantDiag.algebraicAndMetricAlerts
                if (allFlags.isEmpty()) {
                  Text(
                    text = "No deterministic pre-flight leakage or GRIM anomalies flagged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CupertinoGreen
                  )
                } else {
                  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allFlags.forEach { flag ->
                      Surface(
                        color = CupertinoRed.copy(alpha = 0.08f),
                        shape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.86f),
                        modifier = Modifier.fillMaxWidth()
                      ) {
                        Text(
                          text = "• $flag",
                          style = MaterialTheme.typography.bodySmall,
                          color = CupertinoRed,
                          fontWeight = FontWeight.Medium,
                          modifier = Modifier.padding(10.dp)
                        )
                      }
                    }
                  }
                }
              }

              2 -> {
                if (resolvedPreFlight.extractedEquations.isEmpty()) {
                  Text(
                    text = "No inline LaTeX or symbolic equations detected in current text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                } else {
                  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    resolvedPreFlight.extractedEquations.forEachIndexed { idx, eq ->
                      Surface(
                        color = CupertinoIndigo.copy(alpha = 0.08f),
                        shape = SquircleShape(cornerRadius = 8.dp, smoothing = 0.86f),
                        modifier = Modifier.fillMaxWidth()
                      ) {
                        Text(
                          text = "[EQ${idx + 1}] $eq",
                          style = MaterialTheme.typography.bodySmall,
                          fontFamily = JetBrainsMonoFamily,
                          color = MaterialTheme.colorScheme.onSurface,
                          modifier = Modifier.padding(10.dp)
                        )
                      }
                    }
                  }
                }
              }

              3 -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  Text(
                    text = "Sample Sizes (N): ${resolvedPreFlight.extractedSampleSizes.joinToString(", ").ifBlank { "None explicitly declared" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFamily
                  )
                  Text(
                    text = "Statistical Claims: ${resolvedPreFlight.statisticalClaims.joinToString(" | ").ifBlank { "None extracted" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = JetBrainsMonoFamily
                  )
                  Text(
                    text = "Citations Detected: ~${resolvedPreFlight.citationCountEstimate} (${resolvedPreFlight.citationAlerts.joinToString("; ").ifBlank { "No citation anomalies" }})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }
        }
      }

      3 -> {
        IosGroupedCard(
          header = "4. Pipeline Stage Toggles (iOS Cupertino Switches)",
          footer = "Toggle individual Reviewer2 & PhD-Skills stages for this paper analysis."
        ) {
          Column {
            IosSettingToggleRow(
              icon = Icons.Default.Science,
              iconTint = CupertinoBlue,
              title = "Empirical Paper Track",
              subtitle = "Enable Breaker (01a), Butcher (01b) & Shredder (01c) empirical audits",
              checked = formState.options.isEmpirical,
              onCheckedChange = { checked -> onOptionsChange { it.copy(isEmpirical = checked) } },
              modifier = Modifier.testTag("chip_empirical_track")
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.Functions,
              iconTint = CupertinoIndigo,
              title = "Math & Equation Proofreader",
              subtitle = "Stage 01e–01fd Re-Deriver & Sober boundary condition check",
              checked = formState.options.enableMathAudit,
              onCheckedChange = { checked -> onOptionsChange { it.copy(enableMathAudit = checked) } },
              modifier = Modifier.testTag("chip_math_audit")
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.Code,
              iconTint = CupertinoGreen,
              title = "Code & Split Leakage X-Ray",
              subtitle = "Stage 01i–01n train/val/test leakage & replication audit",
              checked = formState.options.enableCodeAudit,
              onCheckedChange = { checked -> onOptionsChange { it.copy(enableCodeAudit = checked) } },
              modifier = Modifier.testTag("chip_code_audit")
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.Gavel,
              iconTint = CupertinoRed,
              title = "Senior Editor Decision Memo",
              subtitle = "Stage 08b 6-Axis Calibrated Scorecard & editorial verdict",
              checked = formState.options.enableEditorNote,
              onCheckedChange = { checked -> onOptionsChange { it.copy(enableEditorNote = checked) } },
              modifier = Modifier.testTag("chip_editor_note")
            )
            IosInsetDivider()
            IosSettingToggleRow(
              icon = Icons.Default.EditNote,
              iconTint = CupertinoAmber,
              title = "Academic Copyedit & De-Fluffer",
              subtitle = "Stage 09a–09c line-by-line academic prose tightening",
              checked = formState.options.enableCopyeditWriterMode,
              onCheckedChange = { checked ->
                onOptionsChange { it.copy(enableCopyeditWriterMode = checked) }
              },
              modifier = Modifier.testTag("chip_writer_mode")
            )
          }
        }
      }
    }

    // Persistent Docked Execution & Pipeline Status Card (Visible across all 4 Upload Sub-Tabs)
    IosGroupedCard(
      header = "Execute 30-Stage Adversarial Review"
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (pipelineState) {
          is PipelineExecutionState.Running -> {
            var elapsedSeconds by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
              while (true) {
                kotlinx.coroutines.delay(1000L)
                elapsedSeconds++
              }
            }
            val cancelShape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f)
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .neuRecessed(
                  cornerRadius = 15.dp,
                  depth = 3.dp,
                  cavityColor = palette.surfaceRecessed,
                  focusGlowColor = CupertinoBlue
                )
                .padding(14.dp)
                .testTag("pipeline_progress_card"),
              verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(
                  text = "STAGE [${pipelineState.stageCode}] • ${elapsedSeconds}s",
                  style = MaterialTheme.typography.labelMedium,
                  fontFamily = JetBrainsMonoFamily,
                  fontWeight = FontWeight.Bold,
                  color = CupertinoBlue
                )
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Text(
                    text = "${(pipelineState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Box(
                    modifier = Modifier
                      .neuRaised(
                        cornerRadius = 9.dp,
                        elevation = 2.5.dp,
                        surfaceColor = palette.surfaceElevated
                      )
                      .clip(cancelShape)
                      .clickable { onCancelPipeline() }
                      .padding(horizontal = 9.dp, vertical = 4.dp)
                      .testTag("cancel_pipeline_button")
                  ) {
                    Text(
                      text = "Cancel",
                      style = MaterialTheme.typography.labelSmall,
                      fontFamily = JetBrainsMonoFamily,
                      fontWeight = FontWeight.Bold,
                      color = CupertinoRed
                    )
                  }
                }
              }
              Text(
                text = pipelineState.stageLabel,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
              )
              LinearProgressIndicator(
                progress = { pipelineState.progress },
                color = CupertinoBlue,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(7.dp)
                  .clip(SquircleShape(cornerRadius = 4.dp, smoothing = 0.88f))
              )
            }
          }

          is PipelineExecutionState.Error -> {
            Surface(
              color = CupertinoRed.copy(alpha = 0.12f),
              shape = SquircleShape(cornerRadius = 9.dp, smoothing = 0.86f),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("pipeline_error_banner")
            ) {
              Text(
                text = pipelineState.message,
                style = MaterialTheme.typography.bodySmall,
                color = CupertinoRed,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(12.dp)
              )
            }
          }

          is PipelineExecutionState.Completed -> {
            Surface(
              color = CupertinoGreen.copy(alpha = 0.12f),
              shape = SquircleShape(cornerRadius = 10.dp, smoothing = 0.86f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(12.dp)
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "OUTPUT READY • SCORE ${pipelineState.review.credibilityScore}/100",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFamily,
                    color = CupertinoGreen,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = pipelineState.review.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IosSecondarySquircleButton(
                  text = "View Output",
                  onClick = { onOpenCompletedDossier(pipelineState.review) },
                  tintColor = CupertinoGreen,
                  modifier = Modifier.testTag("view_latest_dossier_button")
                )
              }
            }
          }

          PipelineExecutionState.Idle -> Unit
        }

        IosSquircleButton(
          text = "Analyze Paper & Generate Review",
          icon = Icons.Default.Gavel,
          enabled = pipelineState !is PipelineExecutionState.Running,
          onClick = onRunPipeline,
          cornerRadius = 10.dp,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("run_reviewer2_pipeline_button")
        )
      }
    }
  }
}

@Composable
private fun ForensicStatTile(
  value: String,
  label: String,
  tint: Color,
  modifier: Modifier = Modifier
) {
  val palette = LocalNeumorphicPalette.current
  Column(
    modifier = modifier
      .neuRecessed(
        cornerRadius = 13.dp,
        depth = 2.6.dp,
        cavityColor = palette.surfaceRecessed
      )
      .padding(vertical = 10.dp, horizontal = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Text(
      text = value,
      style = MaterialTheme.typography.titleMedium,
      fontFamily = JetBrainsMonoFamily,
      fontWeight = FontWeight.ExtraBold,
      color = tint
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontSize = 9.5.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

@Composable
private fun FormatUploadSquircleTile(
  title: String,
  subtitle: String,
  icon: ImageVector,
  accentColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val palette = LocalNeumorphicPalette.current
  val tileRadius = 16.dp
  val tileShape = SquircleShape(cornerRadius = tileRadius, smoothing = 0.86f)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
    modifier = modifier
      .padding(vertical = 2.dp)
      .neuRaised(
        cornerRadius = tileRadius,
        elevation = 4.8.dp,
        surfaceColor = palette.surfaceElevated
      )
      .clip(tileShape)
      .clickable(onClick = onClick)
      .padding(vertical = 14.dp, horizontal = 6.dp)
  ) {
    IosSquircleIconBadge(
      icon = icon,
      tint = accentColor,
      size = 42.dp,
      cornerRadius = 13.dp
    )
    Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.Bold,
      letterSpacing = (-0.15).sp,
      color = MaterialTheme.colorScheme.onSurface
    )
    Text(
      text = subtitle,
      style = MaterialTheme.typography.labelSmall,
      fontSize = 10.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun HeroTelemetryMetric(
  label: String,
  value: String,
  tint: Color
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(1.dp)
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontFamily = JetBrainsMonoFamily,
      fontSize = 8.5.sp,
      letterSpacing = 0.6.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelSmall,
      fontFamily = JetBrainsMonoFamily,
      fontSize = 10.5.sp,
      fontWeight = FontWeight.ExtraBold,
      color = tint
    )
  }
}

