package com.samford.patient.data.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import java.io.ByteArrayOutputStream
import kotlin.random.Random

data class SampleHeader(
    val documentTitle: String,
    val department: String,
    val patientLine: String,
    val visitLine: String,
    val doctorLine: String,
    val pageLabel: String,
)

sealed interface InkLine {
    data class Text(val text: String, val struck: Boolean = false, val indent: Int = 0, val correction: Boolean = false) : InkLine
    data object Rx : InkLine
    data object Gap : InkLine
}

data class SamplePageSpec(
    val header: SampleHeader,
    val lines: List<InkLine>,
    val signature: String,
    val seed: Long,
)

/**
 * Draws synthetic "handwritten" prescription-pad pages for offline sample mode. Real pages come from
 * the doctor's tablet; these stand-ins use the phone's cursive font with slight per-word jitter so the
 * viewer can be tried without a server. Every page carries a SAMPLE watermark.
 */
object SamplePageRenderer {
    const val WIDTH = 1240
    const val HEIGHT = 1754

    private val ink = Color.rgb(22, 38, 120)
    private val correctionInk = Color.rgb(150, 20, 20)

    fun render(spec: SamplePageSpec): ByteArray {
        val bitmap = createBitmap(WIDTH, HEIGHT)
        try {
            val canvas = Canvas(bitmap)
            draw(canvas, spec)
            return ByteArrayOutputStream(256 * 1024).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun draw(canvas: Canvas, spec: SamplePageSpec) {
        val random = Random(spec.seed)
        canvas.drawColor(Color.rgb(255, 254, 250))

        // Printed letterhead of the digital pad.
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 93, 115) }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), 150f, brand)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("Samford Hospital, Ranchi", 60f, 78f, white)
        white.textSize = 28f
        white.typeface = Typeface.SANS_SERIF
        canvas.drawText("OPD · ${spec.header.department}", 60f, 124f, white)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(spec.header.documentTitle.uppercase(), WIDTH - 60f, 92f, title)

        val printed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 60, 60)
            textSize = 26f
            typeface = Typeface.SANS_SERIF
        }
        canvas.drawText(spec.header.patientLine, 60f, 205f, printed)
        canvas.drawText(spec.header.visitLine, 60f, 245f, printed)
        canvas.drawText(spec.header.doctorLine, 60f, 285f, printed)
        val rule = Paint().apply {
            color = Color.rgb(205, 225, 236)
            strokeWidth = 2f
        }
        var y = 380f
        while (y < HEIGHT - 220) {
            canvas.drawLine(60f, y, WIDTH - 60f, y, rule)
            y += LINE_HEIGHT
        }

        // Diagonal SAMPLE watermark.
        val watermark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(30, 198, 40, 40)
            textSize = 210f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.withRotation(-32f, WIDTH / 2f, HEIGHT / 2f) {
            drawText("SAMPLE", WIDTH / 2f, HEIGHT / 2f, watermark)
        }

        // Handwriting.
        val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 50f
            typeface = Typeface.create("cursive", Typeface.NORMAL)
        }
        var baseline = 370f
        for (line in spec.lines) {
            when (line) {
                InkLine.Gap -> baseline += LINE_HEIGHT / 2
                InkLine.Rx -> {
                    drawRx(canvas, 80f, baseline, random)
                    baseline += LINE_HEIGHT
                }
                is InkLine.Text -> {
                    hand.color = if (line.correction) correctionInk else ink
                    val x0 = 90f + line.indent * 40f
                    val endX = drawWords(canvas, line.text, x0, baseline, hand, random)
                    if (line.struck) drawStrike(canvas, x0, endX, baseline - 16f, random)
                    baseline += LINE_HEIGHT
                }
            }
        }

        drawSignature(canvas, spec.signature, WIDTH - 420f, HEIGHT - 260f, random)

        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 40, 40)
            textSize = 22f
        }
        canvas.drawText("SAMPLE: synthetic demo record, not a real patient. Not valid for clinical use.", 60f, HEIGHT - 70f, footer)
        footer.color = Color.rgb(120, 120, 120)
        footer.textAlign = Paint.Align.RIGHT
        canvas.drawText(spec.header.pageLabel, WIDTH - 60f, HEIGHT - 70f, footer)
    }

    private fun drawWords(canvas: Canvas, text: String, x0: Float, baseline: Float, paint: Paint, random: Random): Float {
        var x = x0
        val space = paint.measureText(" ")
        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            val width = paint.measureText(word)
            if (x + width > WIDTH - 70f) break
            val dy = random.nextFloat() * 6f - 3f
            canvas.withRotation(random.nextFloat() * 3f - 1.5f, x, baseline + dy) {
                drawText(word, x, baseline + dy, paint)
            }
            x += width + space * (0.8f + random.nextFloat() * 0.5f)
        }
        return x
    }

    private fun drawStrike(canvas: Canvas, x0: Float, x1: Float, y: Float, random: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = correctionInk
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(x0 - 6f, y + random.nextFloat() * 4f)
            quadTo((x0 + x1) / 2, y - 6f + random.nextFloat() * 8f, x1, y + random.nextFloat() * 4f)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawRx(canvas: Canvas, x: Float, baseline: Float, random: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            strokeWidth = 6f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val j = { random.nextFloat() * 4f - 2f }
        val path = Path().apply {
            moveTo(x + j(), baseline + j())
            lineTo(x + 2f + j(), baseline - 62f + j())
            cubicTo(x + 50f, baseline - 70f, x + 52f, baseline - 30f, x + 4f, baseline - 32f)
            moveTo(x + 18f, baseline - 32f)
            lineTo(x + 52f + j(), baseline + 4f + j())
            moveTo(x + 30f, baseline - 4f)
            lineTo(x + 58f + j(), baseline - 26f + j())
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSignature(canvas: Canvas, name: String, x: Float, y: Float, random: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(x, y)
            var cx = x
            repeat(6) {
                val up = 30f + random.nextFloat() * 40f
                cubicTo(cx + 12f, y - up, cx + 28f, y + 20f, cx + 46f, y - 6f + random.nextFloat() * 12f)
                cx += 46f
            }
            lineTo(cx + 40f, y - 14f)
        }
        canvas.drawPath(path, paint)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 60, 60)
            textSize = 24f
        }
        canvas.drawText(name, x, y + 56f, label)
    }

    private const val LINE_HEIGHT = 86f
}
