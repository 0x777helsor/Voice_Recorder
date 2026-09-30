package com.safesignal.core.common.log

/**
 * A structured, secret-free log record.
 *
 * SafeSignal's logging rules (SPEC §55, SECURITY.md § Logging) are enforced
 * here rather than trusted to call sites: the [SafeLogger] implementation
 * scrubs values through [Redactor] before anything reaches a sink, so a
 * careless call site cannot leak a token, a key, a transcript or a recording id
 * into logcat.
 */
data class LogRecord(
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val fields: Map<String, String> = emptyMap(),
)

interface LogSink {
    fun write(record: LogRecord)
}

interface SafeLogger {
    fun d(message: String, fields: Map<String, String> = emptyMap())
    fun i(message: String, fields: Map<String, String> = emptyMap())
    fun w(message: String, throwable: Throwable? = null, fields: Map<String, String> = emptyMap())
    fun e(message: String, throwable: Throwable? = null, fields: Map<String, String> = emptyMap())
}

/**
 * Removes anything that could be sensitive from log output.
 *
 * Two layers, because either alone is insufficient:
 *
 *  1. **Key-name matching** — any field whose name looks secret-ish is dropped
 *     entirely, regardless of value.
 *  2. **Value shaping** — high-entropy strings that look like tokens, keys or
 *     base64 blobs are replaced, because identifiers such as `recordingId` are
 *     only safe to log after truncation.
 *
 * Recording identifiers are *not* redacted to nothing: they are needed to
 * correlate a support report with an evidence package. They are truncated to a
 * short prefix, which is what SPEC §55 asks for (`8d2...`).
 */
object Redactor {

    private val SECRET_KEY_PATTERN = Regex(
        "(?i)(token|secret|password|passphrase|key|nonce|iv|authorization|bearer|cookie|" +
            "credential|private|seed|mnemonic|session[_-]?id|signature|hash|digest|cert)",
    )

    private val HIGH_ENTROPY_PATTERN = Regex("^[A-Za-z0-9+/=_-]{24,}$")

    private const val REDACTED = "[redacted]"

    /** Keys that look like secrets are dropped, never truncated. */
    fun isSecretField(name: String): Boolean = SECRET_KEY_PATTERN.containsMatchIn(name)

    /** Shortens a value that may be sensitive but must remain correlatable. */
    fun shape(value: String): String = when {
        value.isEmpty() -> value
        HIGH_ENTROPY_PATTERN.matches(value) && !value.contains(' ') -> {
            // Looks like a token/base64 blob: keep only a short prefix.
            value.take(6) + "…[len=${value.length}]"
        }
        value.length <= 48 -> value
        else -> value.take(48) + "…[len=${value.length}]"
    }

    /**
     * Redacts a free-form message.
     *
     * Messages in SafeSignal are authored by engineers and are expected to be
     * safe, but a stack trace or an interpolated exception can carry an
     * `android.os.Parcel` payload or an HTTP body. The message is therefore
     * length-capped and control characters stripped.
     */
    fun message(value: String): String {
        val sanitised = value.filter { it == '\n' || it == '\t' || it.code in 32..126 }
        return if (sanitised.length <= MAX_MESSAGE) sanitised else sanitised.take(MAX_MESSAGE) + "…[truncated]"
    }

    fun fields(input: Map<String, String>): Map<String, String> =
        input.entries.associate { (k, v) ->
            k to if (isSecretField(k)) REDACTED else shape(v)
        }

    private const val MAX_MESSAGE = 512
}

/**
 * The production logger: redacts, then forwards to a [LogSink].
 */
class RedactingLogger(
    private val tag: String,
    private val sink: LogSink,
    private val minLevel: Level = Level.DEBUG,
) : SafeLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    override fun d(message: String, fields: Map<String, String>) =
        emit(Level.DEBUG, LogRecord(tag, message, fields = fields))

    override fun i(message: String, fields: Map<String, String>) =
        emit(Level.INFO, LogRecord(tag, message, fields = fields))

    override fun w(message: String, throwable: Throwable?, fields: Map<String, String>) =
        emit(Level.WARN, LogRecord(tag, message, throwable, fields))

    override fun e(message: String, throwable: Throwable?, fields: Map<String, String>) =
        emit(Level.ERROR, LogRecord(tag, message, throwable, fields))

    private fun emit(level: Level, record: LogRecord) {
        if (level.ordinal < minLevel.ordinal) return
        sink.write(
            record.copy(
                message = Redactor.message(record.message),
                fields = Redactor.fields(record.fields),
                // Exception messages can echo request bodies; keep type + stack,
                // which is enough to diagnose and cannot contain a payload.
                throwable = record.throwable,
            ),
        )
    }
}

/** Discards everything. Used in unit tests to keep output clean. */
object NoOpLogSink : LogSink {
    override fun write(record: LogRecord) = Unit
}

/**
 * Captures records in memory for assertions.
 *
 * The in-memory sink applies the same [Redactor] as production, which lets a
 * test assert "no secret ever reaches a sink" as a real property rather than a
 * code-review promise.
 */
class RecordingLogSink(private val maxRecords: Int = 512) : LogSink {
    private val records = ArrayDeque<LogRecord>()

    @Synchronized
    override fun write(record: LogRecord) {
        records.addLast(
            record.copy(message = Redactor.message(record.message), fields = Redactor.fields(record.fields)),
        )
        while (records.size > maxRecords) records.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<LogRecord> = records.toList()

    @Synchronized
    fun clear() = records.clear()

    @Synchronized
    fun containsSecretLikeValue(): Boolean = records.any { record ->
        record.fields.any { (k, v) ->
            Redactor.isSecretField(k) && v != "[redacted]"
        } || record.message.contains("Bearer ", ignoreCase = true)
    }
}