package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.InputRequestView
import dev.ahpclient.state.QuestionView

/** What the agent is asking, in words, with the choices of any pick-one or pick-any question. This client never answers. */
@Composable
internal fun InputRequestCard(request: InputRequestView, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("input_request")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(request.kind, style = MaterialTheme.typography.labelLarge)
            Text(request.text, style = MaterialTheme.typography.bodyLarge)
            request.questions.forEach { question -> QuestionBlock(question) }
        }
    }
}

@Composable
private fun QuestionBlock(question: QuestionView) {
    Column(modifier = Modifier.padding(top = 8.dp).testTag("question"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        question.prompt?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        Text(question.answerKind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        question.options.forEach { option ->
            Text("• $option", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp).testTag("question_option"))
        }
    }
}
