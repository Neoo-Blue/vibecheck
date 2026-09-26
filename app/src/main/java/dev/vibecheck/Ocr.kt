package dev.vibecheck

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * On-device Chinese text recognition (it reads Latin script too). Recognition runs on the phone,
 * so no image and no text leaves it for OCR. The model is delivered by Google Play services
 * rather than bundled, which keeps the APK small but means a phone without Play services cannot
 * run it: the failure then shows under Tools → Diagnostics.
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
                // Usually the model is still downloading, or Play services is missing altogether.
                Diag.lastError = "ocr: ${it.message} (needs Google Play services and its OCR model)"
                Diag.log(Diag.lastError)
                onResult(emptyList())
            }
    }
}
