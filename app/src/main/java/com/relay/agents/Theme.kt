package com.relay.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** هوية بصرية ثابتة: أخضر هادئ، أسطح رمادية فاتحة، زوايا مستديرة، وخطوط عناوين أثقل. */
object Brand {
    val Green = Color(0xFF10B981)
    val GreenDark = Color(0xFF0B8A60)
    val Amber = Color(0xFFD99A2B)
    val Red = Color(0xFFD64545)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E0E10), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F6EF), onPrimaryContainer = Color(0xFF064E3B),
    secondary = Brand.GreenDark, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F6EF), onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Brand.Green,
    background = Color.White, onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6), onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFD1D5DB), outlineVariant = Color(0xFFE5E7EB),
    error = Brand.Red
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF3F4F6), onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF0F3D2E), onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Brand.Green, onSecondary = Color(0xFF04261B),
    secondaryContainer = Color(0xFF0F3D2E), onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Brand.Green,
    background = Color(0xFF0B0B0C), onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF0B0B0C), onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF1A1B1E), onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF3A3C41), outlineVariant = Color(0xFF26282C),
    error = Color(0xFFFF8A8A)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    val base = Typography()
    val typo = base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    )
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AppShapes, typography = typo, content = content)
}

/** بطاقة مسطّحة بخلفية رمادية فاتحة وحد رفيع، بديلة عن Card الافتراضية. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(0.dp),
        content = content
    )
}

/** نقطة حالة ملوّنة. */
@Composable
fun StatusDot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** أيقونة دائرية بحرف المزوّد (مثل شارات الكتالوج). */
@Composable
fun ProviderBadge(label: String, size: Int = 40) {
    Box(
        Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Brand.GreenDark)
    }
}
