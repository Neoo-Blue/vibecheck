package dev.vibecheck

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * On-device Chinese text recognition. The model is bundled in the APK, so no image and no text
 * ever leaves the phone for OCR, and it works with no network at all.
 */
object Ocr {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** Block-level results: one entry per recognized paragraph, with its box in screen pixels. */
    fun read(bmp: Bitmap, onResult: (List<Pair<String, Chat.Box>>) -> Unit) {
        val started = System.currentTimeMillis()
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                val items = text.textBlocks.mapNotNull { block ->
                    val r = block.boundingBox ?: return@mapNotNull null
                    val line = block.text.replace('\n', ' ').trim()
                    if (line.isEmpty()) null else line to Chat.Box(r.left, r.top, r.right, r.bottom)
                }
                Diag.log("ocr: ${items.size} blocks in ${System.currentTimeMillis() - started}ms")
                onResult(items)
            }
            .addOnFailureListener {
                Diag.lastError = "ocr: ${it.message}"
                Diag.log(Diag.lastError)
                onResult(emptyList())
            }
    }
}
