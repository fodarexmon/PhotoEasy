package com.example.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * The system bars (status bar, navigation buttons / gesture bar, cut-out) as padding, read from
 * the screen that opens a full-size dialog. Call it OUTSIDE the Dialog: inside a dialog window
 * many devices report no navigation-bar inset even though the dialog is drawn under it.
 */
@Composable
fun hostSystemBarsPadding(): PaddingValues = WindowInsets.safeDrawing.union(WindowInsets.systemBars).asPaddingValues()
