package dev.tim.assistant.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tim.assistant.ui.WallyColors

/** Colors of the line chart's series, in series order (mood, energy, sleep, stress). */
val SERIES_COLORS: List<Color> = WallyColors.chartSeries

private val LABEL_STYLE = TextStyle(fontSize = 11.sp)

private fun TextMeasurer.measureLabel(text: String, color: Color, maxWidth: Int = Constraints.Infinity): TextLayoutResult =
    measure(
        text,
        LABEL_STYLE.copy(color = color),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth),
    )

/**
 * Lines over time. [series] holds one list per line, each the same length as [labels]; [dashed] says per line whether it is drawn dashed; a null value is a gap
 * (the line breaks) and a point with no neighbours is still drawn as a dot. The Y axis shows every integer of [yRange].
 */
@Composable
fun LineChart(
    series: List<List<Int?>>,
    labels: List<String>,
    yRange: IntRange,
    modifier: Modifier = Modifier,
    colors: List<Color> = SERIES_COLORS,
    dashed: List<Boolean> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val yLabels = yRange.map { measurer.measureLabel(it.toString(), textColor) }
        val axisWidth = (yLabels.maxOfOrNull { it.size.width } ?: 0) + 8.dp.toPx()
        val xSample = labels.firstOrNull()?.let { measurer.measureLabel(it, textColor) }
        val bottomSpace = (xSample?.size?.height ?: 0) + 6.dp.toPx()
        val plotLeft = axisWidth
        val plotTop = 6.dp.toPx()
        val plotWidth = (size.width - plotLeft - 6.dp.toPx()).coerceAtLeast(1f)
        val plotHeight = (size.height - plotTop - bottomSpace).coerceAtLeast(1f)
        val low = yRange.first
        val span = (yRange.last - yRange.first).coerceAtLeast(1)
        fun yOf(v: Int) = plotTop + plotHeight - (v - low).toFloat() / span * plotHeight
        fun xOf(i: Int) = if (labels.size <= 1) plotLeft + plotWidth / 2 else plotLeft + i.toFloat() / (labels.size - 1) * plotWidth

        yRange.forEachIndexed { idx, v ->
            val y = yOf(v)
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), strokeWidth = 1f)
            val l = yLabels[idx]
            drawText(l, topLeft = Offset(axisWidth - 6.dp.toPx() - l.size.width, y - l.size.height / 2f))
        }
        // first, middle and last day labels
        val xTicks = listOf(0, (labels.size - 1) / 2, labels.size - 1).filter { it in labels.indices }.distinct()
        for (i in xTicks) {
            val l = measurer.measureLabel(shortDate(labels[i]), textColor)
            val x = (xOf(i) - l.size.width / 2f).coerceIn(0f, (size.width - l.size.width).coerceAtLeast(0f))
            drawText(l, topLeft = Offset(x, plotTop + plotHeight + 4.dp.toPx()))
        }
        series.forEachIndexed { s, values ->
            val color = colors[s % colors.size]
            val dash = if (dashed.getOrNull(s) == true) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())) else null
            for (i in values.indices) {
                val v = values[i] ?: continue
                val p = Offset(xOf(i), yOf(v))
                val next = values.getOrNull(i + 1)
                if (next != null) drawLine(color, p, Offset(xOf(i + 1), yOf(next)), strokeWidth = 2.dp.toPx(), pathEffect = dash)
                drawCircle(color, radius = 3.dp.toPx(), center = p)
            }
        }
    }
}

/**
 * One line of hours over time (a null value is a gap). The Y axis has a tick every 2 hours up to [maxHours]
 * (at least 8), labelled "0h", "2h" and so on, so 7.5 sits between the 6h and 8h ticks. [guideHours] draws a dashed guide line.
 */
@Composable
fun HealthLineChart(
    values: List<Double?>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = SERIES_COLORS[2],
    guideHours: Double? = null,
) {
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val guideColor = MaterialTheme.colorScheme.primary
    val top = (kotlin.math.ceil((values.filterNotNull().maxOrNull() ?: 0.0) / 2.0).toInt() * 2).coerceAtLeast(8)
    val ticks = (0..top step 2).toList()
    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        val yLabels = ticks.map { measurer.measureLabel("${it}h", textColor) }
        val axisWidth = (yLabels.maxOfOrNull { it.size.width } ?: 0) + 8.dp.toPx()
        val xSample = labels.firstOrNull()?.let { measurer.measureLabel(shortDate(it), textColor) }
        val bottomSpace = (xSample?.size?.height ?: 0) + 6.dp.toPx()
        val plotLeft = axisWidth
        val plotTop = 6.dp.toPx()
        val plotWidth = (size.width - plotLeft - 6.dp.toPx()).coerceAtLeast(1f)
        val plotHeight = (size.height - plotTop - bottomSpace).coerceAtLeast(1f)
        fun yOf(v: Double) = plotTop + plotHeight - (v / top).toFloat() * plotHeight
        fun xOf(i: Int) = if (labels.size <= 1) plotLeft + plotWidth / 2 else plotLeft + i.toFloat() / (labels.size - 1) * plotWidth

        ticks.forEachIndexed { idx, v ->
            val y = yOf(v.toDouble())
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), strokeWidth = 1f)
            val l = yLabels[idx]
            drawText(l, topLeft = Offset(axisWidth - 6.dp.toPx() - l.size.width, y - l.size.height / 2f))
        }
        val xTicks = listOf(0, (labels.size - 1) / 2, labels.size - 1).filter { it in labels.indices }.distinct()
        for (i in xTicks) {
            val l = measurer.measureLabel(shortDate(labels[i]), textColor)
            val x = (xOf(i) - l.size.width / 2f).coerceIn(0f, (size.width - l.size.width).coerceAtLeast(0f))
            drawText(l, topLeft = Offset(x, plotTop + plotHeight + 4.dp.toPx()))
        }
        if (guideHours != null) {
            val y = yOf(guideHours)
            drawLine(
                guideColor, Offset(plotLeft, y), Offset(plotLeft + plotWidth, y), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
        }
        for (i in values.indices) {
            val v = values[i] ?: continue
            val p = Offset(xOf(i), yOf(v))
            val next = values.getOrNull(i + 1)
            if (next != null) drawLine(color, p, Offset(xOf(i + 1), yOf(next)), strokeWidth = 2.dp.toPx())
            drawCircle(color, radius = 3.dp.toPx(), center = p)
        }
    }
}

/**
 * Bars where a null value draws nothing and a measured 0 draws a thin stub. Only the first, middle and last
 * labels are drawn (30 bars are too narrow for every label).
 */
@Composable
fun NullableBarChart(
    values: List<Long?>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val sample = labels.firstOrNull()?.let { measurer.measureLabel(shortDate(it), textColor) }
        val bottomSpace = (sample?.size?.height ?: 0) + 6.dp.toPx()
        val plotTop = 6.dp.toPx()
        val plotHeight = (size.height - plotTop - bottomSpace).coerceAtLeast(1f)
        val max = (values.filterNotNull().maxOrNull() ?: 0L).coerceAtLeast(1L)
        drawLine(gridColor, Offset(0f, plotTop + plotHeight), Offset(size.width, plotTop + plotHeight), strokeWidth = 1f)
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val h = if (v <= 0L) 2.dp.toPx() else v.toFloat() / max * plotHeight
            val barWidth = slot * 0.6f
            drawBar(color, i * slot + (slot - barWidth) / 2, plotTop + plotHeight - h, barWidth, h)
        }
        for (i in listOf(0, (labels.size - 1) / 2, labels.size - 1).filter { it in labels.indices }.distinct()) {
            val l = measurer.measureLabel(shortDate(labels[i]), textColor)
            val x = (i * slot + slot / 2 - l.size.width / 2f).coerceIn(0f, (size.width - l.size.width).coerceAtLeast(0f))
            drawText(l, topLeft = Offset(x, plotTop + plotHeight + 4.dp.toPx()))
        }
    }
}

/** "2026-11-08" -> "11-08"; other labels are returned unchanged. */
private fun shortDate(label: String): String =
    if (label.length == 10 && label[4] == '-' && label[7] == '-') label.substring(5) else label

/**
 * Vertical bars, one per value; [labels] go under the bars (cut with "..." when the slot is narrow).
 * The tallest bar fills the plot; an all-zero chart draws empty slots.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val measurer = rememberTextMeasurer()
    val textColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val labelLayouts = labels.map { measurer.measureLabel(it, textColor, slot.toInt().coerceAtLeast(1)) }
        val bottomSpace = (labelLayouts.maxOfOrNull { it.size.height } ?: 0) + 6.dp.toPx()
        val plotTop = 6.dp.toPx()
        val plotHeight = (size.height - plotTop - bottomSpace).coerceAtLeast(1f)
        val max = values.max().coerceAtLeast(1L)
        drawLine(gridColor, Offset(0f, plotTop + plotHeight), Offset(size.width, plotTop + plotHeight), strokeWidth = 1f)
        values.forEachIndexed { i, v ->
            val h = v.coerceAtLeast(0L).toFloat() / max * plotHeight
            val barWidth = slot * 0.6f
            val left = i * slot + (slot - barWidth) / 2
            if (h > 0f) drawBar(color, left, plotTop + plotHeight - h, barWidth, h)
            labelLayouts.getOrNull(i)?.let { l ->
                drawText(l, topLeft = Offset(i * slot + (slot - l.size.width) / 2, plotTop + plotHeight + 4.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.drawBar(color: Color, left: Float, top: Float, width: Float, height: Float) {
    drawRect(color, topLeft = Offset(left, top), size = Size(width, height))
}