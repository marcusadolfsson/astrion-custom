package com.custom.astrion.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Transient voice readout, styled to match the volume OSD.
 *
 * Feedback is the whole point: press-and-talk with no visible state is
 * indistinguishable from a broken microphone. "Listening" has to appear the
 * instant the key is pressed, because the person is about to start speaking.
 *
 * Tapping it cancels.
 */
@Composable
fun BoxScope.VoiceOverlay(
    state: VoiceState,
    onDismiss: () -> Unit,
    prompts: List<String> = emptyList(),
    promptTitle: String = "Try saying",
) {
    AnimatedVisibility(
        visible = state !is VoiceState.Idle,
        enter = fadeIn(tween(100)) + scaleIn(tween(120), initialScale = 0.9f),
        exit = fadeOut(tween(200)),
        modifier = Modifier.align(Alignment.Center),
    ) {
        val (title, detail, tint) = when (state) {
            is VoiceState.Listening -> Triple("Listening…", null, Color(0xFF7FD8F0))
            is VoiceState.Thinking -> Triple("Thinking…", null, Color(0xFF7FD8F0))
            is VoiceState.Done -> Triple(
                when (state.route) {
                    "siri" -> "Sent to Apple TV"
                    "assist" -> state.transcript ?: "Sent to Assist"
                    else -> "Sent"
                },
                state.response,
                Color(0xFF8FE0A8),
            )
            is VoiceState.Error -> Triple("Voice failed", state.message, Color(0xFFE08A8A))
            VoiceState.Idle -> Triple("", null, Color.White)
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE60E2229))
                .clickable { onDismiss() }
                .padding(horizontal = 26.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A simple level-independent pulse would need a timer; the label
            // alone is enough on a 3.1" screen and costs no recomposition.
            Text(
                text = title,
                color = tint,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = Color(0xFFBFD3DA),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
            }

            // Prompts, shown only while actually listening. Deliberately not on
            // Thinking/Done/Error: by then the utterance is spoken and advice
            // about what to say is just clutter over the answer.
            if (state is VoiceState.Listening && prompts.isNotEmpty()) {
                Text(
                    text = promptTitle,
                    color = Color(0xFF7E9AA3),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
                )
                prompts.forEach { line ->
                    Text(
                        text = "“$line”",
                        color = Color(0xFFD6E6EA),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                    )
                }
            }
        }
    }
}
