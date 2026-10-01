package com.safesignal.core.common.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The severity a caller chose must reach the sink.
 *
 * This exists because it previously did not. `LogRecord` carried no severity, so
 * [RedactingLogger] used the level only to decide whether to emit and then threw it
 * away. Every sink had to infer severity from whatever was left — the logcat sink
 * inferred it from the presence of a throwable, which meant a deliberate
 * `logger.e("...")` with no exception was written as `Log.i`. An error filed as
 * information is invisible to anyone reading logcat by priority, which is how a
 * genuinely failed recording looks like a routine one.
 *
 * The tests below pin the contract at the core boundary. They cannot assert what
 * logcat does with the priority — that is the platform's business — so the claim
 * this protects is narrower and still worth protecting: the sink receives what the
 * caller asked for.
 */
class SafeLoggerSeverityTest {

    @Test
    fun `each level reaches the sink as itself`() {
        val sink = RecordingLogSink()
        val logger = RedactingLogger(tag = "SafeSignal", sink = sink)

        logger.d("debug")
        logger.i("info")
        logger.w("warn")
        logger.e("error")

        assertEquals(
            listOf(Severity.DEBUG, Severity.INFO, Severity.WARN, Severity.ERROR),
            sink.snapshot().map { it.severity },
        )
    }

    @Test
    fun `an error without a throwable is still an error`() {
        // The specific regression: throwable presence was the only signal a sink
        // had, so an error that happened to carry no exception was downgraded.
        val sink = RecordingLogSink()
        RedactingLogger(tag = "SafeSignal", sink = sink).e("recording failed")

        val record = sink.snapshot().single()
        assertEquals(Severity.ERROR, record.severity)
        assertEquals(null, record.throwable)
    }

    @Test
    fun `minLevel filters before the sink and does not rewrite severity`() {
        val sink = RecordingLogSink()
        val logger = RedactingLogger(tag = "SafeSignal", sink = sink, minLevel = Severity.WARN)

        logger.d("dropped")
        logger.i("dropped")
        logger.w("kept")
        logger.e("kept")

        assertEquals(listOf(Severity.WARN, Severity.ERROR), sink.snapshot().map { it.severity })
    }

    @Test
    fun `severity is preserved alongside redaction`() {
        // Guards against a future change that rebuilds the record for redaction and
        // forgets to carry the severity across.
        val sink = RecordingLogSink()
        val logger = RedactingLogger(tag = "SafeSignal", sink = sink)

        logger.e("failed", IllegalStateException("boom"), mapOf("token" to "abc123", "attempt" to "3"))

        val record = sink.snapshot().single()
        assertEquals(Severity.ERROR, record.severity)
        assertEquals("[redacted]", record.fields["token"])
        assertEquals("3", record.fields["attempt"])
    }

    @Test
    fun `severity does not weaken the no-secret guarantee`() {
        val sink = RecordingLogSink()
        val logger = RedactingLogger(tag = "SafeSignal", sink = sink)

        logger.e("auth failed", null, mapOf("authorization" to "Bearer eyJhbGciOiJIUzI1NiJ9"))

        assertEquals("[redacted]", sink.snapshot().single().fields["authorization"])
        assertFalse(sink.containsSecretLikeValue())
    }
}