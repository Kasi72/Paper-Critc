package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.ui.theme.AmberGold400
import com.example.ui.theme.SlateNavy800
import com.example.ui.theme.SlateNavy900
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
  val listener = FirebaseAuth.AuthStateListener { auth ->
    trySend(auth.currentUser)
  }
  addAuthStateListener(listener)
  awaitClose { removeAuthStateListener(listener) }
}

fun attemptAutoSignIn(
  context: Context,
  credentialManager: CredentialManager,
  onAuthSuccess: () -> Unit,
  onUnauthenticated: () -> Unit,
  scope: CoroutineScope
) {
  if (Firebase.auth.currentUser != null) {
    onAuthSuccess()
    return
  }
  val clientId = try {
    context.getString(R.string.default_web_client_id)
  } catch (e: Exception) {
    onUnauthenticated()
    return
  }

  val googleIdOption = GetGoogleIdOption.Builder()
    .setFilterByAuthorizedAccounts(true)
    .setServerClientId(clientId)
    .setAutoSelectEnabled(true)
    .build()

  val request = GetCredentialRequest.Builder()
    .addCredentialOption(googleIdOption)
    .build()

  val activityContext: Context = context.findActivity() ?: context

  scope.launch {
    try {
      val result = credentialManager.getCredential(activityContext, request)
      val credential = result.credential
      if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        Firebase.auth.signInWithCredential(authCredential).await()
        onAuthSuccess()
      } else {
        onUnauthenticated()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      onUnauthenticated()
    }
  }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

fun onGoogleSignInClicked(
  context: Context,
  credentialManager: CredentialManager,
  onAuthSuccess: () -> Unit,
  onAuthError: (String) -> Unit,
  scope: CoroutineScope,
  onAuthCancelled: () -> Unit = {}
) {
  val clientId = try {
    context.getString(R.string.default_web_client_id)
  } catch (e: Exception) {
    onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
    return
  }

  val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
  val request = GetCredentialRequest.Builder()
    .addCredentialOption(signInOption)
    .build()

  val activityContext: Context = context.findActivity() ?: context

  scope.launch {
    try {
      val result = credentialManager.getCredential(activityContext, request)
      val credential = result.credential
      if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
        Firebase.auth.signInWithCredential(authCredential).await()
        onAuthSuccess()
      } else {
        onAuthError("Unexpected credential type returned by Google Sign-In.")
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: GetCredentialCancellationException) {
      Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
      onAuthCancelled()
    } catch (e: Exception) {
      Log.e("Auth", "Google Sign-In failed", e)
      onAuthError(e.localizedMessage ?: "Sign in failed. Please try again.")
    }
  }
}

fun signOut(
  credentialManager: CredentialManager,
  scope: CoroutineScope,
  onSignOutComplete: () -> Unit = {}
) {
  scope.launch {
    withContext(NonCancellable) {
      try {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
      } catch (e: Exception) {
        Log.w("Auth", "Failed to clear credential state: ${e.message}")
      } finally {
        Firebase.auth.signOut()
        onSignOutComplete()
      }
    }
  }
}

@Composable
fun GoogleSignInButton(
  onAuthSuccess: () -> Unit,
  onAuthError: (String) -> Unit,
  modifier: Modifier = Modifier,
  buttonText: String? = null
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  var isLoading by remember { mutableStateOf(false) }

  Button(
    onClick = {
      isLoading = true
      onGoogleSignInClicked(
        context = context,
        credentialManager = credentialManager,
        onAuthSuccess = {
          isLoading = false
          onAuthSuccess()
        },
        onAuthError = { errorMsg ->
          isLoading = false
          onAuthError(errorMsg)
        },
        scope = coroutineScope,
        onAuthCancelled = { isLoading = false }
      )
    },
    enabled = !isLoading,
    shape = com.example.ui.components.SquircleShape(16.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = com.example.ui.theme.CupertinoBlue,
      contentColor = Color.White
    ),
    modifier = modifier
      .fillMaxWidth()
      .height(52.dp)
      .testTag("google_sign_in_button")
  ) {
    if (isLoading) {
      CircularProgressIndicator(
        modifier = Modifier.size(22.dp),
        color = Color.White,
        strokeWidth = 2.5.dp
      )
    } else {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.CloudDone,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Text(
          text = buttonText ?: stringResource(R.string.sign_in_with_google),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold
        )
      }
    }
  }
}

@Composable
fun GoogleCloudSyncDialog(
  isSignedIn: Boolean,
  userDisplayName: String,
  userEmail: String,
  syncedCount: Int,
  unsyncedLocalCount: Int,
  adoptableAnonymousCount: Int = 0,
  isSyncing: Boolean,
  onSyncNow: () -> Unit,
  onAdoptAnonymousReviews: () -> Unit = {},
  onSignOut: () -> Unit,
  onDismiss: () -> Unit
) {
  var authError by remember { mutableStateOf<String?>(null) }

  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = com.example.ui.components.SquircleShape(24.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 8.dp,
      shadowElevation = 12.dp,
      modifier = Modifier
        .fillMaxWidth()
        .testTag("cloud_sync_dialog")
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          com.example.ui.components.IosSquircleIconBadge(
            icon = Icons.Default.CloudDone,
            tint = if (isSignedIn) com.example.ui.theme.CupertinoGreen else com.example.ui.theme.CupertinoBlue,
            size = 44.dp
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = if (isSignedIn) "Cloud Portfolio Synced" else "Sync Review Portfolio",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (isSignedIn) {
                userDisplayName.ifBlank { userEmail.ifBlank { "Google Account Connected" } }
              } else {
                "Firebase Authentication • Google Sign-In"
              },
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        if (isSignedIn) {
          if (userEmail.isNotBlank()) {
            Text(
              text = "Signed in as $userEmail",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Surface(
            shape = com.example.ui.components.SquircleShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Text(
                text = "Cloud Dossiers: $syncedCount synced across devices",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = com.example.ui.theme.CupertinoGreen
              )
              if (unsyncedLocalCount > 0) {
                Text(
                  text = "$unsyncedLocalCount local session review(s) ready to upload",
                  style = MaterialTheme.typography.bodySmall,
                  color = com.example.ui.theme.CupertinoBlue
                )
              } else {
                Text(
                  text = "All paper reviews, bookmarks, and revision notes are backed up in real time.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }

          com.example.ui.components.IosSquircleButton(
            text = if (isSyncing) "Syncing Portfolio..." else "Sync Portfolio Across Devices",
            onClick = onSyncNow,
            enabled = !isSyncing,
            icon = Icons.Default.CloudDone,
            containerColor = com.example.ui.theme.CupertinoBlue
          )

          if (adoptableAnonymousCount > 0) {
            com.example.ui.components.IosSecondarySquircleButton(
              text = "Adopt $adoptableAnonymousCount Local Signed-Out Draft(s)",
              onClick = onAdoptAnonymousReviews,
              tintColor = com.example.ui.theme.CupertinoBlue
            )
          }

          com.example.ui.components.IosSecondarySquircleButton(
            text = "Sign Out of Google Account",
            onClick = {
              onSignOut()
              onDismiss()
            },
            tintColor = com.example.ui.theme.CupertinoRed
          )
        } else {
          Text(
            text = "Sign in with your Google account to back up this account's referee dossiers, bookmarks, and revision notes to Firebase Firestore across devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          if (unsyncedLocalCount > 0) {
            Surface(
              shape = com.example.ui.components.SquircleShape(12.dp),
              color = com.example.ui.theme.CupertinoBlue.copy(alpha = 0.1f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = "You have $unsyncedLocalCount local signed-out dossier(s). They remain strictly local after sign-in unless you explicitly choose to adopt them into your account.",
                style = MaterialTheme.typography.labelSmall,
                color = com.example.ui.theme.CupertinoBlue,
                modifier = Modifier.padding(10.dp)
              )
            }
          }

          if (authError != null) {
            Surface(
              color = Color(0xFF7F1D1D),
              shape = com.example.ui.components.SquircleShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = authError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFECACA),
                modifier = Modifier.padding(10.dp)
              )
            }
          }

          GoogleSignInButton(
            onAuthSuccess = {
              authError = null
              onDismiss()
            },
            onAuthError = { authError = it }
          )
        }

        com.example.ui.components.IosSecondarySquircleButton(
          text = "Close",
          onClick = onDismiss,
          tintColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
fun AuthScreen(
  onAuthSuccess: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  var authError by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      onAuthSuccess = onAuthSuccess,
      onUnauthenticated = {},
      scope = scope
    )
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(SlateNavy900, SlateNavy800, Color(0xFF0B1120))
        )
      )
      .windowInsetsPadding(WindowInsets.safeDrawing),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
      Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(200.dp)
          ) {
            Image(
              painter = painterResource(id = R.drawable.img_review_hero_banner),
              contentDescription = "Scholarly peer review workbench illustration",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize()
            )
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    colors = listOf(
                      Color.Transparent,
                      Color(0xCC0F172A)
                    )
                  )
                )
            )
            Surface(
              color = AmberGold400,
              shape = RoundedCornerShape(50),
              modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
            ) {
              Text(
                text = "IS IT CREDIBLE? • REVIEWER2 + PHD-SKILLS",
                style = MaterialTheme.typography.labelSmall,
                color = SlateNavy900,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
              )
            }
          }

          Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.displaySmall,
              color = Color.White
            )
            Text(
              text = stringResource(R.string.app_tagline),
              style = MaterialTheme.typography.titleMedium,
              color = AmberGold400
            )
            Text(
              text = "\"To err is human. All human knowledge is fallible and therefore uncertain. It follows that we must distinguish sharply between truth and certainty.\" — Karl Popper",
              style = MaterialTheme.typography.bodySmall,
              fontStyle = FontStyle.Italic,
              color = Color(0xFFCBD5E1)
            )

            Spacer(modifier = Modifier.height(4.dp))

            FeaturePillRow(
              icon = Icons.Default.Gavel,
              title = "Red Team Breaker / Butcher / Shredder",
              subtitle = "Aggressive methodological, identification, and empirical table interrogation"
            )
            FeaturePillRow(
              icon = Icons.AutoMirrored.Filled.FactCheck,
              title = "Blue Team Verification Cascade",
              subtitle = "Filters LLM hallucinations and verifies equation & numerical consistency"
            )
            FeaturePillRow(
              icon = Icons.Default.Science,
              title = "Math Proofread & Code X-Ray",
              subtitle = "Audits LaTeX notation, train/val/test split leakage, and replication readiness"
            )
            FeaturePillRow(
              icon = Icons.Default.CloudDone,
              title = "Cloud-Synced Dossier Portfolio",
              subtitle = "Export plain-text 'IS IT CREDIBLE?' reports and sync critiques across devices"
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = stringResource(R.string.auth_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF94A3B8),
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth()
            )

            if (authError != null) {
              Surface(
                color = Color(0xFF7F1D1D),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("auth_error_banner")
              ) {
                Text(
                  text = authError.orEmpty(),
                  style = MaterialTheme.typography.bodySmall,
                  color = Color(0xFFFECACA),
                  modifier = Modifier.padding(12.dp)
                )
              }
            }

            GoogleSignInButton(
              onAuthSuccess = onAuthSuccess,
              onAuthError = { authError = it }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FeaturePillRow(
  icon: ImageVector,
  title: String,
  subtitle: String
) {
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(Color(0xFF0F172A).copy(alpha = 0.6f))
      .padding(12.dp)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = AmberGold400,
      modifier = Modifier.size(22.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF94A3B8)
      )
    }
  }
}
