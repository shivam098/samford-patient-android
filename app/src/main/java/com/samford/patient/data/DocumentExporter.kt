package com.samford.patient.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.samford.patient.core.blob.BlobResult
import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.DocumentEntity
import com.samford.patient.core.db.DocumentVersionEntity
import com.samford.patient.core.db.PageEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Localised strings stamped onto exported PDFs. */
data class PdfLabels(
    val watermark: String,
    val footer: String,
)

sealed interface ExportResult {
    data class Ready(val file: File, val fromHospital: Boolean) : ExportResult
    data class Missing(val pagesNotDownloaded: Int) : ExportResult
}

/**
 * Produces a shareable PDF of one document version.
 * - Normally the PDF is assembled on the phone from the hash-verified page images, unchanged, with a
 *   SAMPLE banner and a footer identifying the version, record hash and page hashes. The pages are
 *   the canonical handwriting, so the share copy shows exactly what the doctor wrote.
 * - If a version has no page images, the hospital's own PDF (hash-verified) is shared as-is.
 */
class DocumentExporter(
    private val blobs: BlobStore,
    private val shareDir: File,
) {
    suspend fun export(
        document: DocumentEntity,
        version: DocumentVersionEntity,
        pages: List<PageEntity>,
        displayTitle: String,
        labels: PdfLabels,
        allowDownload: Boolean,
    ): ExportResult = withContext(Dispatchers.IO) {
        shareDir.mkdirs()
        val safeName = displayTitle.replace(Regex("[^A-Za-z0-9\\u0900-\\u097F]+"), "-").trim('-').ifEmpty { "record" }
        val target = File(shareDir, "Samford-$safeName-v${version.version}.pdf")

        if (pages.isEmpty()) {
            val sha = version.pdfSha256 ?: return@withContext ExportResult.Missing(0)
            val file = (if (allowDownload) (blobs.fetch(sha, "application/pdf") as? BlobResult.Ready)?.file else blobs.cached(sha))
                ?: return@withContext ExportResult.Missing(1)
            file.copyTo(target, overwrite = true)
            return@withContext ExportResult.Ready(target, fromHospital = true)
        }

        val files = pages.map { page ->
            if (allowDownload) (blobs.fetch(page.sha256, "image/png") as? BlobResult.Ready)?.file else blobs.cached(page.sha256)
        }
        val missing = files.count { it == null }
        if (missing > 0) return@withContext ExportResult.Missing(missing)

        buildPdf(files.filterNotNull(), pages, document, version, labels, target)
        ExportResult.Ready(target, fromHospital = false)
    }

    private fun buildPdf(
        files: List<File>,
        pages: List<PageEntity>,
        document: DocumentEntity,
        version: DocumentVersionEntity,
        labels: PdfLabels,
        target: File,
    ) {
        val pdf = PdfDocument()
        try {
            files.forEachIndexed { index, file ->
                val pageInfo = PdfDocument.PageInfo.Builder(A4_W, A4_H, index + 1).create()
                val page = pdf.startPage(pageInfo)
                drawPage(page.canvas, file, index, files.size, pages[index], document, version, labels)
                pdf.finishPage(page)
            }
            target.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun drawPage(
        canvas: Canvas,
        file: File,
        index: Int,
        count: Int,
        page: PageEntity,
        document: DocumentEntity,
        version: DocumentVersionEntity,
        labels: PdfLabels,
    ) {
        canvas.drawColor(Color.WHITE)
        val bitmap = decodeBounded(file, MAX_DECODE_WIDTH)
        if (bitmap != null) {
            val area = RectF(MARGIN, MARGIN + BAND, A4_W - MARGIN, A4_H - MARGIN - FOOTER)
            val scale = minOf(area.width() / bitmap.width, area.height() / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val left = area.left + (area.width() - w) / 2
            val dst = RectF(left, area.top, left + w, area.top + h)
            canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dst, Paint(Paint.FILTER_BITMAP_FLAG))
            bitmap.recycle()
        }
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(198, 40, 40) }
        canvas.drawRect(0f, 0f, A4_W.toFloat(), BAND, band)
        val bandText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(labels.watermark, MARGIN, BAND - 6f, bandText)

        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 7.5f
        }
        val line1 = "${labels.footer} · ${document.id} · v${version.version} · ${index + 1}/$count" +
            (version.recordHash?.let { " · record ${it.take(16)}" } ?: "")
        val line2 = "page SHA-256 ${page.sha256}"
        canvas.drawText(line1, MARGIN, A4_H - MARGIN - 12f, footer)
        canvas.drawText(line2, MARGIN, A4_H - MARGIN - 2f, footer)
    }

    companion object {
        private const val A4_W = 595
        private const val A4_H = 842
        private const val MARGIN = 18f
        private const val BAND = 22f
        private const val FOOTER = 24f
        private const val MAX_DECODE_WIDTH = 1654

        fun decodeBounded(file: File, maxWidth: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/**
 * Fallback for a version that only has a PDF (no page images): renders a page with PdfRenderer to a
 * cached PNG so the zoomable viewer can show it.
 */
class PdfPageRasterizer(private val cacheDir: File) {
    suspend fun pageCount(pdf: File): Int = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }

    suspend fun render(pdf: File, sha256: String, index: Int, targetWidth: Int = 1600): File? = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val out = File(cacheDir, "$sha256-$index-$targetWidth.png")
        if (out.isFile) return@withContext out
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (index !in 0 until renderer.pageCount) return@withContext null
                renderer.openPage(index).use { page ->
                    val height = (targetWidth.toFloat() * page.height / page.width).toInt()
                    val bitmap = createBitmap(targetWidth, height)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
        out
    }
}
