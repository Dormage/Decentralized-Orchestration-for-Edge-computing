package logging

import data.DebugType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import utils.tryAndReport
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue

/**
 * Created by Mihael Berčič
 * on 26/03/2020 15:35
 * using IntelliJ IDEA
 *
 * Used for any type of logging.
 */
object Logger {

    private val myIP: String = InetAddress.getLocalHost().hostAddress

    @Serializable
    data class Log(val type: DebugType, val log: String, val ip: String, val timestamp: Long)

    private var isLoggingEnabled = false
    private var currentDebug: DebugType = DebugType.ALL
    private val timeFormatter = DateTimeFormatter.ofPattern("dd. MM | HH:mm:ss.SSS")

    const val red = "\u001b[31m"
    const val blue = "\u001B[34;1m"
    const val cyan = "\u001b[36m"
    const val green = "\u001b[32m"
    const val black = "\u001b[30m"
    const val yellow = "\u001b[33m"
    const val magenta = "\u001b[35m"
    const val white = "\u001b[37m"
    const val reset = "\u001B[0m"

    private val httpClient = HttpClient.newHttpClient()
    private val queue = LinkedBlockingQueue<Log>()
    private val batch = mutableListOf<Log>()
    private const val batchSize = 10

    /** Prints the given message with the coloring and debug information provided.*/
    private fun log(debugType: DebugType, message: Any, color: String = black) {
        val typeString = LocalDateTime.now().format(timeFormatter).padEnd(11) + " | " + padRight(debugType.name)
        val output = "$color$typeString$reset$message"
        val timestamp = System.currentTimeMillis()
        val log = Log(debugType, output, myIP, timestamp)
        queue.add(log)
    }

    private fun clearQueue() {
        while (true) tryAndReport {
            val log = queue.take()
            if (isLoggingEnabled) println(log.log)
            batch.add(log)
            if (batch.size >= batchSize) {
                val json = Json.encodeToString(batch)
                val request = HttpRequest.newBuilder()
                    .uri(URI("http://88.200.63.133:8101/logs"))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build()
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                batch.clear()
            }
        }
    }

    /** Enables or disables software logging.  */
    fun toggleLogging(enable: Boolean) {
        isLoggingEnabled = enable
        Thread(::clearQueue).start()
    }

    fun info(message: Any) = log(DebugType.INFO, message, green)
    fun debug(message: Any) = log(DebugType.DEBUG, message, magenta)
    fun error(message: Any) = log(DebugType.ERROR, message, red)
    fun trace(message: Any) = log(DebugType.TRACE, message, yellow)
    fun chain(message: Any) = log(DebugType.CHAIN, message, cyan)
    fun consensus(message: Any) = log(DebugType.CONSENSUS, message, blue)

    /** Pads the string with the default character of ' ' at the end. */
    private fun padRight(string: String) = string.padEnd(12)

}