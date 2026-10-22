package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenColumn {
            Text("AHP Client", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Watch your VS Code agent sessions from your phone. This app only reads; it cannot send anything to your sessions.",
                style = MaterialTheme.typography.bodyLarge,
            )
            state.notice?.let { Notice(it, Modifier.testTag("notice")) }
            when (val step = state.step) {
                SignInStep.Idle -> StartSignIn(onSignIn, label = "Sign in with GitHub")
                SignInStep.RequestingCode -> {
                    Busy("Requesting a code from GitHub…")
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                is SignInStep.AwaitingApproval -> Approval(step, onCancel, onOpenGitHub, onCopyCode)
                is SignInStep.Failed -> {
                    ErrorText(step.message, Modifier.testTag("sign_in_error"))
                    StartSignIn(onSignIn, label = "Try again")
                }
            }
        }
    }
}

@Composable
private fun StartSignIn(onSignIn: () -> Unit, label: String) {
    Text(
        "Use the same GitHub account that Remote Tunnel Access is signed in with in VS Code. GitHub will give you a short code to enter.",
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
            "Open github.com/login/device on this phone or on any other device, enter the code, and approve “Visual Studio Tunnel Service”."
        } else {
            "Open ${step.verificationUri} on any device you trust, enter the code, and approve “Visual Studio Tunnel Service”."
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
