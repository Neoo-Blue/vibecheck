package dev.vibecheck

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
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

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * What OCR read, in the picture's pixels: [blocks], one per paragraph with its lines joined,
     * which is how messages are taken, and [lines], each line of them on its own, which is how the
     * title bar is: OCR may take a name and the line drawn under it (WeChat's status) as one
     * paragraph, and the name read as both.
     */
    class Read(val blocks: List<Pair<String, Chat.Box>>, val lines: List<Pair<String, Chat.Box>>)

    /**
     * Recognize the text in [bmp]. [onResult] runs on the main thread, and what it throws is
     * logged rather than ending the service (see [Crash]).
     */
    fun read(bmp: Bitmap, onResult: (Read) -> Unit) {
        val started = System.currentTimeMillis()
        val task = try {
            recognizer.process(InputImage.fromBitmap(bmp, 0))
        } catch (e: Throwable) {
            Crash.caught("ocr", e)
            // On the main thread like every other result: the caller may be a capture's worker,
            // and what [onResult] does (the card, the window, the verdicts) belongs to the main thread.
            main.post { Crash.guard("ocr") { onResult(Read(emptyList(), emptyList())) } }
            return
        }
        task.addOnSuccessListener { text ->
                Crash.guard("ocr") {
                    val blocks = ArrayList<Pair<String, Chat.Box>>()
                    val lines = ArrayList<Pair<String, Chat.Box>>()
                    for (block in text.textBlocks) {
                        val r = block.boundingBox ?: continue
                        val whole = block.text.replace('\n', ' ').trim()
                        if (whole.isEmpty()) continue
                        blocks += whole to box(r)
                        val own = block.lines.mapNotNull { l -> l.boundingBox?.let { b -> l.text.trim().takeIf { it.isNotEmpty() }?.let { it to box(b) } } }
                        lines += if (own.isNotEmpty() && own.size == block.lines.size) own else listOf(whole to box(r))
                    }
                    Diag.log("ocr: ${blocks.size} blocks in ${System.currentTimeMillis() - started}ms")
                    onResult(Read(blocks, lines))
                }
            }
            .addOnFailureListener {
                // Usually the model is still downloading, or Play services is missing altogether.
                Diag.lastError = "ocr: ${it.message} (needs Google Play services and its OCR model)"
                Diag.log(Diag.lastError)
                Crash.guard("ocr") { onResult(Read(emptyList(), emptyList())) }
            }
    }

    private fun box(r: Rect) = Chat.Box(r.left, r.top, r.right, r.bottom)
}
