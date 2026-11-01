package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahpclient.app.AppState
import dev.ahpclient.app.GITHUB_AUTHORIZED_APPS_URL
import dev.ahpclient.app.SafeLinks
import dev.ahpclient.app.SignInStep
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
fun SignInScreen(
    state: AppState.SignedOut,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onOpenGitHub: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Skips the sign-in to use only hosts added by address. Null hides the way. */
    onUseDirect: (() -> Unit)? = null,
) {
    var showingAbout by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(title = "AHP Client", modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenColumn {
                Text(
                    "Follow your VS Code agent sessions from your phone. It only watches until you allow more in Settings.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                state.notice?.let { Notice(it, Modifier.testTag("notice")) }
                when (val step = state.step) {
                    SignInStep.Idle -> {
                        StartSignIn(onSignIn, label = "Sign in with GitHub")
                        if (onUseDirect != null) UseDirect(onUseDirect)
                        if (state.offerRevoke) RevokeAccess(onOpenGitHub)
                    }
                    SignInStep.RequestingCode -> {
                        Busy("Asking GitHub for a code…")
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    is SignInStep.AwaitingApproval -> Approval(step, onCancel, onOpenGitHub, onCopyCode)
                    is SignInStep.Failed -> {
                        ErrorText(step.message, Modifier.testTag("sign_in_error"))
                        StartSignIn(onSignIn, label = "Try again")
                        if (onUseDirect != null) UseDirect(onUseDirect)
                    }
                }
                TextButton(
                    onClick = { showingAbout = true },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("about_button"),
                ) {
                    Text("About")
                }
            }
        }
    }
    if (showingAbout) {
        AboutSheet(onDismiss = { showingAbout = false })
    }
}

/** For a host that is reached by its own address (such as ahpd), which needs no GitHub account. */
@Composable
private fun UseDirect(onUseDirect: () -> Unit) {
    Text(
        "Or connect to a host by its address, with no GitHub account.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onUseDirect, modifier = Modifier.fillMaxWidth().testTag("use_direct_hosts")) { Text("Connect to a host by address") }
}

/** Signing out forgets the token on this phone; the grant stays on the GitHub account until it is removed there. */
@Composable
private fun RevokeAccess(onOpen: (String) -> Unit) {
    Notice(
        "Signed out of this phone. The app keeps its access on your GitHub account until you remove it there.",
        Modifier.testTag("revoke_notice"),
    )
    OutlinedButton(onClick = { onOpen(GITHUB_AUTHORIZED_APPS_URL) }, modifier = Modifier.fillMaxWidth().testTag("revoke_access")) {
        Text("Review access on GitHub")
    }
}

@Composable
private fun StartSignIn(onSignIn: () -> Unit, label: String) {
    Text(
        "Use the GitHub account Remote Tunnel Access is signed in with in VS Code.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().testTag("sign_in_button")) { Text(label) }
}

@Composable
private fun Approval(
    step: SignInStep.AwaitingApproval,
    onCancel: () -> Unit,
    onOpenGitHub: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val canOpen = SafeLinks.isSafeVerificationUri(step.verificationUri)
    Text("Enter this code on GitHub", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Text(
        step.userCode,
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
        // Read one character at a time, so TalkBack says "A B C D dash 1 2 3 4", not a word.
        modifier = Modifier.testTag("user_code").semantics { contentDescription = "Code " + step.userCode.map { if (it == '-') "dash" else it.toString() }.joinToString(" ") },
    )
    Text(
        if (canOpen) {
            "Enter the code at github.com/login/device, on this phone or any device, and approve “Visual Studio Tunnel Service”."
        } else {
            "Open ${step.verificationUri} on a device you trust, enter the code, and approve “Visual Studio Tunnel Service”."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onCopyCode(step.userCode) }, modifier = Modifier.testTag("copy_code")) { Text("Copy code") }
        if (canOpen) {
            Button(onClick = { onOpenGitHub(step.verificationUri) }, modifier = Modifier.testTag("open_github")) { Text("Open GitHub") }
        }
    }
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        "Waiting for approval. The code expires at ${timeFormat.format(step.expiresAt)}.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp).testTag("waiting"),
    )
    TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel_sign_in")) { Text("Cancel") }
}

/**
 * In-place GitHub device-flow sign-in dialog shown when signing in while already connected to a direct host, so the
 * existing connection stays live while the user approves the device code.
 */
@Composable
fun GitHubSignInDialog(
    step: SignInStep,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenGitHub: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    if (step is SignInStep.Idle) return
    val canOpen = (step as? SignInStep.AwaitingApproval)?.let { SafeLinks.isSafeVerificationUri(it.verificationUri) } == true
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("github_sign_in_dialog"),
        title = {
            Text(
                when (step) {
                    is SignInStep.AwaitingApproval -> "Enter this code on GitHub"
                    else -> "Sign in with GitHub"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    SignInStep.Idle -> Unit
                    SignInStep.RequestingCode -> Busy("Asking GitHub for a code…")
                    is SignInStep.AwaitingApproval -> {
                        Text(
                            step.userCode,
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 4.sp),
                            modifier = Modifier.testTag("user_code").semantics {
                                contentDescription = "Code " + step.userCode.map { if (it == '-') "dash" else it.toString() }.joinToString(" ")
                            },
                        )
                        Text(
                            if (canOpen) {
                                "Enter the code at github.com/login/device, on this phone or any device, and approve “Visual Studio Tunnel Service”."
                            } else {
                                "Open ${step.verificationUri} on a device you trust, enter the code, and approve “Visual Studio Tunnel Service”."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { onCopyCode(step.userCode) }, modifier = Modifier.heightIn(min = 48.dp).testTag("copy_code")) { Text("Copy code") }
                            if (canOpen) {
                                Button(onClick = { onOpenGitHub(step.verificationUri) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open_github")) { Text("Open GitHub") }
                            }
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Waiting for approval. The code expires at ${timeFormat.format(step.expiresAt)}.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("waiting"),
                        )
                    }
                    is SignInStep.Failed -> ErrorText(step.message, Modifier.testTag("sign_in_error"))
                }
            }
        },
        confirmButton = {
            if (step is SignInStep.Failed) {
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("sign_in_retry")) { Text("Try again") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_sign_in")) { Text("Cancel") }
        },
    )
}
