package com.ucmtelnyx.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

/** Clips to a circle and adds a ripple - used for the round keypad/call buttons. */
fun Modifier.clickableRipple(onClick: () -> Unit): Modifier =
    this.clip(CircleShape).clickable(onClick = onClick)
