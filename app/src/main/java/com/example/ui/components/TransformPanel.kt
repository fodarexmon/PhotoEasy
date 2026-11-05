package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceVariant
import kotlin.math.abs

@Composable
fun TransformPanel(
    rotation: Float,
    scaleX: Float = 1f,
    scaleY: Float = 1f,
    positionX: Float = 0f,
    positionY: Float = 0f,
    onRotationChange: (Float) -> Unit,
    onRotationCommit: () -> Unit,
    onScaleChange: (scaleX: Float, scaleY: Float) -> Unit = { _, _ -> },
    onPositionChange: (posX: Float, posY: Float) -> Unit = { _, _ -> },
    onTransformCommit: () -> Unit = {},
    onRotate90Left: () -> Unit,
    onRotate90Right: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onCenterCanvas: () -> Unit = {},
    onFitCanvas: () -> Unit = {},
    onFillCanvas: () -> Unit = {},
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val normalized = ((rotation % 360f) + 360f) % 360f
    val signed = if (normalized > 180f) normalized - 360f else normalized
    var lockProportions by remember { mutableStateOf(true) }

    val absScaleX = abs(scaleX)
    val absScaleY = abs(scaleY)
    val signX = if (scaleX < 0) -1f else 1f
    val signY = if (scaleY < 0) -1f else 1f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(StudioSurface)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.transform_title),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(Modifier.height(10.dp))

        // Quick Canvas Alignments
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCenterCanvas,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.transform_center_canvas), fontSize = 11.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = onFitCanvas,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.FitScreen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.transform_fit_canvas), fontSize = 11.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = onFillCanvas,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.transform_fill_canvas), fontSize = 11.sp, maxLines = 1)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Proportional Lock Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.transform_scale),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            FilterChip(
                selected = lockProportions,
                onClick = { lockProportions = !lockProportions },
                label = {
                    Text(
                        stringResource(R.string.transform_proportional),
                        fontSize = 11.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        if (lockProportions) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }

        if (lockProportions) {
            val avgScale = (absScaleX + absScaleY) / 2f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.transform_scale), color = Color.LightGray, fontSize = 12.sp)
                Text(String.format("%.2fx", avgScale), color = IndigoPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Slider(
                value = avgScale,
                onValueChange = { newScale ->
                    onScaleChange(newScale * signX, newScale * signY)
                },
                onValueChangeFinished = onTransformCommit,
                valueRange = 0.1f..4.0f
            )
        } else {
            // Independent Scale X & Scale Y
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.transform_scale_x), color = Color.LightGray, fontSize = 12.sp)
                Text(String.format("%.2fx", absScaleX), color = IndigoPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Slider(
                value = absScaleX,
                onValueChange = { newX ->
                    onScaleChange(newX * signX, scaleY)
                },
                onValueChangeFinished = onTransformCommit,
                valueRange = 0.1f..4.0f
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.transform_scale_y), color = Color.LightGray, fontSize = 12.sp)
                Text(String.format("%.2fx", absScaleY), color = IndigoPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Slider(
                value = absScaleY,
                onValueChange = { newY ->
                    onScaleChange(scaleX, newY * signY)
                },
                onValueChangeFinished = onTransformCommit,
                valueRange = 0.1f..4.0f
            )
        }

        Spacer(Modifier.height(8.dp))

        // Rotation
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.transform_rotation), color = Color.White, fontSize = 12.sp)
            Text("${signed.toInt()}°", color = IndigoPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Slider(
            value = signed,
            onValueChange = onRotationChange,
            onValueChangeFinished = onRotationCommit,
            valueRange = -180f..180f
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransformAction(stringResource(R.string.rotate_left), Icons.Default.RotateLeft, Modifier.weight(1f), onRotate90Left)
            TransformAction(stringResource(R.string.rotate_right), Icons.Default.RotateRight, Modifier.weight(1f), onRotate90Right)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransformAction(stringResource(R.string.flip_h), Icons.Default.Flip, Modifier.weight(1f), onFlipHorizontal)
            TransformAction(stringResource(R.string.flip_v), Icons.Default.Flip, Modifier.weight(1f), onFlipVertical)
        }

        Spacer(Modifier.height(12.dp))

        // Position X & Y controls
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.transform_pos_x), color = Color.LightGray, fontSize = 12.sp)
            Text("${positionX.toInt()} px", color = IndigoPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Slider(
            value = positionX,
            onValueChange = { newX -> onPositionChange(newX, positionY) },
            onValueChangeFinished = onTransformCommit,
            valueRange = -800f..800f
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.transform_pos_y), color = Color.LightGray, fontSize = 12.sp)
            Text("${positionY.toInt()} px", color = IndigoPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Slider(
            value = positionY,
            onValueChange = { newY -> onPositionChange(positionX, newY) },
            onValueChangeFinished = onTransformCommit,
            valueRange = -800f..800f
        )

        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.transform_reset))
        }
    }
}

@Composable
private fun TransformAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}
