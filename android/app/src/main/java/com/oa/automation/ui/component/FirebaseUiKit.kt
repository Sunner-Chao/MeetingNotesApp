package com.oa.automation.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.oa.automation.R
import com.oa.automation.ui.theme.BrandCyan
import com.oa.automation.ui.theme.LocalAppIsDarkTheme

/** Layout values mirrored from FirebaseUI Auth's wrapper and touch-target resources. */
object FirebaseUiTokens {
    val ScreenPadding = 20.dp
    val SectionSpacing = 20.dp
    val FieldSpacing = 14.dp
    val CompactSpacing = 10.dp
    val MinTouchTarget = 48.dp
    val AuthContentMaxWidth = 420.dp
}

/** Renders the brand artwork with the same rounded silhouette as a launcher icon. */
@Composable
fun AppLauncherIcon(
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                setScaleType(ImageView.ScaleType.CENTER_CROP)
                setImageResource(R.drawable.brand_icon)
                clipToOutline = true
                outlineProvider = RoundedIconOutlineProvider()
                this.contentDescription = contentDescription
            }
        },
        update = { imageView ->
            imageView.setImageResource(R.drawable.brand_icon)
            imageView.clipToOutline = true
            imageView.outlineProvider = RoundedIconOutlineProvider()
            imageView.invalidateOutline()
            imageView.contentDescription = contentDescription
        },
        modifier = modifier
    )
}

private class RoundedIconOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val radius = minOf(view.width, view.height) * 0.235f
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    showAgent: Boolean = true
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppLauncherIcon(modifier = Modifier.size(42.dp), contentDescription = "智悟本")
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = "智悟本",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
            )
            if (showAgent) {
                Text(
                    text = "智能体 · 小Woo",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dark) BrandCyan else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ZhiWuScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = if (LocalAppIsDarkTheme.current) {
        listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
            MaterialTheme.colorScheme.background
        )
    } else {
        listOf(Color(0xFFF8FBFF), Color(0xFFF5F7FD), Color(0xFFFFFFFF))
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors))
    ) {
        content()
    }
}

@Composable
fun BrandPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}
