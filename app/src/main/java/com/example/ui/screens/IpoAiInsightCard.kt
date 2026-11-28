package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.IpoEntity
import com.example.ui.components.glassBorderStroke
import com.example.ui.theme.LocalGlassStyle
import com.example.ui.theme.LocalMarketColors
import com.example.ui.viewmodel.IpoAiUiState

private data class QuickAiPrompt(
    val label: String,
    val prompt: String,
)

private val DEFAULT_IPO_AI_PROMPTS = listOf(
    QuickAiPrompt(
        label = "Quick Brief",
        prompt = "Give a balanced 5-point IPO brief covering business quality, valuation, live demand, and key risks.",
    ),
    QuickAiPrompt(
        label = "Valuation Check",
        prompt = "Analyze the IPO valuation using P/E, EPS, ROE, debt, and Fresh vs OFS split.",
    ),
    QuickAiPrompt(
        label = "GMP & Demand",
        prompt = "Interpret the current GMP and QIB/NII/Retail subscription numbers. What do they signal?",
    ),
    QuickAiPrompt(
        label = "Key Risks",
        prompt = "What are the biggest red flags and risks an investor should check before applying?",
    ),
)

@Composable
internal fun IpoAiInsightCard(
    ipo: IpoEntity,
    aiState: IpoAiUiState,
    onAskQuestion: (String?) -> Unit,
    onClearResponse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val marketColors = LocalMarketColors.current
    val glass = LocalGlassStyle.current
    var customQuestion by rememberSaveable(ipo.id) { mutableStateOf("") }
    val isBusy = aiState is IpoAiUiState.Loading || aiState is IpoAiUiState.Streaming

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ipo_ai_insight_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = glass.cardFill,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = glassBorderStroke(glass = glass, width = 1.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (glass.isDark || glass.isGlass) 0.dp else 1.5.dp,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = "AI IPO Analyst",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(7.dp)
                                .size(18.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "AI IPO Analyst",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Grounded in live GMP, subscription & financials",
                            style = MaterialTheme.typography.labelSmall,
                            color = marketColors.mutedText,
                        )
                    }
                }
                if (aiState !is IpoAiUiState.Idle) {
                    IconButton(
                        onClick = onClearResponse,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("ipo_ai_clear_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear AI insight",
                            tint = marketColors.mutedText,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DEFAULT_IPO_AI_PROMPTS.forEach { preset ->
                    SuggestionChip(
                        onClick = { onAskQuestion(preset.prompt) },
                        enabled = !isBusy,
                        label = {
                            Text(
                                text = preset.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        ),
                        border = BorderStroke(0.8.dp, marketColors.border),
                        modifier = Modifier.testTag("ipo_ai_chip_${preset.label.lowercase().replace(' ', '_')}"),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = customQuestion,
                    onValueChange = { customQuestion = it },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ipo_ai_question_input"),
                    placeholder = {
                        Text(
                            text = "Ask about ${ipo.shortCode} valuation, risks...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val trimmed = customQuestion.trim()
                            if (trimmed.isNotEmpty() && !isBusy) {
                                onAskQuestion(trimmed)
                                customQuestion = ""
                            }
                        },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = marketColors.border,
                    ),
                )
                FilledIconButton(
                    onClick = {
                        val trimmed = customQuestion.trim()
                        if (trimmed.isNotEmpty()) {
                            onAskQuestion(trimmed)
                            customQuestion = ""
                        } else {
                            onAskQuestion(null)
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("ipo_ai_send_button"),
                ) {
                    if (aiState is IpoAiUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Ask AI IPO Analyst",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            when (aiState) {
                is IpoAiUiState.Idle -> Unit
                is IpoAiUiState.Loading -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(0.7.dp, marketColors.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ipo_ai_loading_state"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "Analyzing ${ipo.companyName}...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                is IpoAiUiState.Streaming -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ipo_ai_streaming_output"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.8.dp,
                                )
                                Text(
                                    text = "Generating insight...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = aiState.text,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                is IpoAiUiState.Success -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(0.8.dp, marketColors.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ipo_ai_success_output"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = aiState.text,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                is IpoAiUiState.Error -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ipo_ai_error_output"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = "AI error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = aiState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            OutlinedButton(
                                onClick = { onAskQuestion(aiState.activePrompt.ifBlank { null }) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("ipo_ai_retry_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
