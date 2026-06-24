package app.bighead.vpn.vpn

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val FILE_NAME = "vpn-debug.log"
    private const val MAX_BYTES = 24_000
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(context: Context, message: String, error: Throwable? = null) {
        runCatching {
            val file = file(context)
            val line = buildString {
                append(formatter.format(Date()))
                append("  ")
                append(message)
                error?.let {
                    append("  ")
                    append(it::class.java.simpleName)
                    append(": ")
                    append(it.message.orEmpty())
                }
                append('\n')
            }
            file.appendText(line)
            trim(file)
        }
    }

    fun read(context: Context): String {
        return runCatching { file(context).readText() }.getOrDefault("")
    }

    fun clear(context: Context) {
        runCatching { file(context).writeText("") }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun trim(file: File) {
        if (file.length() <= MAX_BYTES) return
        val text = file.readText()
        file.writeText(text.takeLast(MAX_BYTES))
    }
}
