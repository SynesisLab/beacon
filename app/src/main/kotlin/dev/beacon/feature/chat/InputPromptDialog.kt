package dev.beacon.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.beacon.core.chat.ChatEngine
import kotlinx.coroutines.delay

/**
 * The model's ask_user dialog (M4-d3): questions arrive as
 * `request:user_input` socket calls (tools/builtin.py:543-660); the FIRST
 * option = the server's recommended one. Answers ack as {answers: {id: label}},
 * cancellation as {status:'cancelled'} — the model continues with the result.
 * Up to 3 questions render sequentially; each can accept a free-form answer
 * when the question/server allows (`allow_other`).
 */
@Composable
fun InputPromptDialog(
    prompt: ChatEngine.InputRequest,
    onAnswer: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val answers = remember(prompt) { mutableStateMapOf<String, String>() }
    var openIndex by remember(prompt) { mutableStateOf(0) }
    // The server holds its own timeout; drop the dialog when it passes.
    LaunchedEffect(prompt) {
        delay(prompt.timeoutMs.takeIf { it > 0 } ?: 120_000L)
        onCancel()
    }
    val question = prompt.questions.getOrNull(openIndex)
    if (question == null) {
        // All questions answered → resolve.
        LaunchedEffect(openIndex) {
            if (prompt.questions.isNotEmpty() && answers.isNotEmpty()) {
                onAnswer(prompt.questions.associate { it.id to (answers[it.id] ?: "") })
            } else {
                onCancel()
            }
        }
        AlertDialog(onDismissRequest = onCancel, confirmButton = {}, title = { Text("…") })
        return
    }

    var selection by remember(question) { mutableStateOf<String?>(null) }
    var custom by remember(question) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text(
                    question.header,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    question.question,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                QuestionOptions(
                    question,
                    selection,
                    onSelect = { selection = it },
                )
                if (prompt.allowOther || question.allowOther) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = custom,
                        onValueChange = {
                            custom = it
                            if (it.isNotBlank()) selection = null
                        },
                        label = { Text("Or type an answer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = selection ?: custom.takeIf { it.isNotBlank() } ?: return@Button
                    answers[question.id] = chosen
                    openIndex++
                },
                enabled = selection != null || custom.isNotBlank(),
            ) {
                Text(
                    if (openIndex < prompt.questions.lastIndex) "Next" else "Answer",
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun QuestionOptions(
    question: ChatEngine.InputQuestion,
    selection: String?,
    onSelect: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height((question.options.size * 76 + 8).dp),
    ) {
        items(question.options, key = { it.label }) { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option.label) }
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(
                    selected = selection == option.label,
                    onClick = { onSelect(option.label) },
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                    if (option.description.isNotBlank()) {
                        Text(
                            option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}