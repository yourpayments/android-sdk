package ru.ypmn.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.ypmn.sdk.IntentStatus

/** Выбор сниппета по активному языку (глобальный тоггл Kotlin/Java). */
fun pickCode(lang: Lang, kotlin: String, java: String): String =
    if (lang == Lang.Java) java else kotlin

private val CodeBg = Color(0xFF1E1E2E)
private val CodeFg = Color(0xFFE6E6F0)

@Composable
fun CodeSpoiler(title: String, code: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "</>",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "▾" else "▸",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CodeBg, MaterialTheme.shapes.small)
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = code,
                            color = CodeFg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Переключатель языка сниппетов (Kotlin / Java). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LangToggle(lang: Lang, onSelect: (Lang) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        Lang.entries.forEachIndexed { index, l ->
            SegmentedButton(
                selected = lang == l,
                onClick = { onSelect(l) },
                shape = SegmentedButtonDefaults.itemShape(index, Lang.entries.size),
            ) {
                Text(l.name)
            }
        }
    }
}

@Composable
fun StatusPill(status: IntentStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        IntentStatus.Success -> Color(0xFFD8F5DE) to Color(0xFF137333)
        IntentStatus.Expired -> Color(0xFFFCEEDA) to Color(0xFF8A5A00)
        IntentStatus.RequiresPaymentData,
        IntentStatus.RequiresPaymentMethod -> Color(0xFFE5E2F7) to Color(0xFF4A3FA0)
    }
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(status.name, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Кружок-аватар способа оплаты с брендовым акцентом. */
@Composable
fun MethodAvatar(type: String, modifier: Modifier = Modifier) {
    val accent = methodAccent(type)
    Box(
        modifier = modifier
            .size(40.dp)
            .background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(methodGlyph(type), color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

fun methodAccent(type: String): Color = when (type) {
    "Card" -> Color(0xFF5B4DFF)
    "FasterPayments" -> Color(0xFF1D1346)
    "SberPay" -> Color(0xFF21A038)
    "AlfaPay" -> Color(0xFFEF3124)
    "MirPay" -> Color(0xFF00A859)
    "TPay" -> Color(0xFF111111)
    else -> Color(0xFF6B7280)
}

fun methodGlyph(type: String): String = when (type) {
    "Card" -> "💳"
    "FasterPayments" -> "⚡"
    "SberPay" -> "S"
    "AlfaPay" -> "A"
    "MirPay" -> "M"
    "TPay" -> "T"
    else -> type.take(1)
}

fun methodLabel(type: String): String = when (type) {
    "FasterPayments" -> "СБП"
    else -> type
}
