package io.github.uirlisi.mostor.settings

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi

/** Reads the text of a PDF with the platform's own PDF renderer (Android 15+; no library). */
object PdfText {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** All pages' text, page after page. Blocking: call it off the main thread. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun read(context: Context, uri: Uri): String {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("cannot open")
        return fd.use {
            PdfRenderer(it).use { pdf ->
                (0 until pdf.pageCount).joinToString("\n") { i ->
                    pdf.openPage(i).use { page -> page.textContents.joinToString("\n") { c -> c.text } }
                }
            }
        }
    }
}
