package com.safesignal.core.common.json

/**
 * A minimal, strict JSON reader/writer.
 *
 * ### Why not `org.json`?
 *
 * `org.json` ships inside `android.jar`, so on the JVM it is a *stub* whose
 * methods throw "not mocked". Every unit test that touched manifest parsing
 * would need `unitTests.isReturnDefaultValues = true`, which turns hard failures
 * into silent `null`s — exactly the wrong behaviour for a security-relevant
 * parser. The usual workaround, adding a real `org.json` to the test classpath,
 * produces a classpath-ordering hazard that can mask real bugs.
 *
 * ### Why not kotlinx.serialization?
 *
 * It would work, but the manifest has one extra requirement serialisation
 * libraries do not make easy to *prove*: a byte-for-byte canonical form. A
 * signature over "whatever the library produced today" silently stops verifying
 * the day the library changes its float formatting or field ordering. Here the
 * canonical form is ~80 lines of explicit code, covered by tests that assert the
 * exact output string.
 *
 * Scope is deliberately small: the subset needed for evidence metadata. It is
 * strict — unknown input throws rather than guessing — because a lenient parser
 * in an evidence path is a liability.
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val value: Double, val raw: String) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

/** Raised for any malformed input. Never carries the offending payload. */
class JsonParseException(message: String) : Exception(message)

object MiniJson {

    // ---------------------------------------------------------------- writing

    /**
     * Escapes a string per RFC 8259.
     *
     * Non-ASCII is emitted as-is rather than \u-escaped: SafeSignal's manifests
     * may contain a device timezone id or a localised engine version, and UTF-8
     * output is both shorter and unambiguous.
     */
    fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (ch.code < 0x20) {
                        out.append("\\u").append(HEX[(ch.code shr 12) and 0xF])
                            .append(HEX[(ch.code shr 8) and 0xF])
                            .append(HEX[(ch.code shr 4) and 0xF])
                            .append(HEX[ch.code and 0xF])
                    } else {
                        out.append(ch)
                    }
            }
        }
        return out.toString()
    }

    /**
     * Renders a [JsonValue] deterministically.
     *
     * Object fields keep insertion order, which is what makes the manifest's
     * canonical form reproducible: the builder adds keys in a fixed sequence.
     */
    fun write(value: JsonValue): String = buildString { writeTo(this, value) }

    private fun writeTo(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            // Integral values are emitted without a decimal point so that
            // sequence numbers and byte counts round-trip as integers.
            is JsonValue.Num ->
                if (value.value == Math.floor(value.value) && !value.value.isInfinite()) {
                    sb.append(value.value.toLong().toString())
                } else {
                    sb.append(value.value.toString())
                }
            is JsonValue.Str -> sb.append('"').append(escape(value.value)).append('"')
            is JsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(escape(k)).append("\":")
                    writeTo(sb, v)
                }
                sb.append('}')
            }
        }
    }

    // ---------------------------------------------------------------- parsing

    fun parse(text: String): JsonValue = Parser(text).run {
        val value = parseValue()
        skipWhitespace()
        if (!atEnd()) fail("trailing content")
        value
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun fail(reason: String): Nothing =
            throw JsonParseException("$reason at offset $index")

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else ->
                    if (c == '-' || c.isDigit()) parseNumber()
                    else fail("unexpected character")
            }
        }

        private fun parseObject(): JsonValue {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                fields[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.Obj(fields)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonValue {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.Arr(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseBool(): JsonValue = when {
            text.startsWith("true", index) -> {
                index += 4; JsonValue.Bool(true)
            }
            text.startsWith("false", index) -> {
                index += 5; JsonValue.Bool(false)
            }
            else -> fail("invalid literal")
        }

        private fun parseNull(): JsonValue {
            if (!text.startsWith("null", index)) fail("invalid literal")
            index += 4
            return JsonValue.Null
        }

        private fun parseNumber(): JsonValue {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index].isDigit()) index++
            if (!atEnd() && text[index] == '.') {
                index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            val value = raw.toDoubleOrNull() ?: fail("invalid number")
            return JsonValue.Num(value, raw)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = text[index++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val esc = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16) ?: fail("invalid \\u escape")
                                sb.append(code.toChar())
                                index += 4
                            }
                            else -> fail("invalid escape")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[index]

        private fun expect(c: Char) {
            if (atEnd() || text[index] != c) fail("expected '$c'")
            index++
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

// --------------------------------------------------------------- accessors

fun JsonValue.asObject(): JsonValue.Obj = this as? JsonValue.Obj
    ?: throw JsonParseException("expected an object")

fun JsonValue.asArray(): JsonValue.Arr = this as? JsonValue.Arr
    ?: throw JsonParseException("expected an array")

fun JsonValue.asString(): String = (this as? JsonValue.Str)?.value
    ?: throw JsonParseException("expected a string")

fun JsonValue.asLong(): Long = (this as? JsonValue.Num)?.value?.toLong()
    ?: throw JsonParseException("expected a number")

fun JsonValue.asInt(): Int = (this as? JsonValue.Num)?.value?.toInt()
    ?: throw JsonParseException("expected a number")

fun JsonValue.asFloat(): Float = (this as? JsonValue.Num)?.value?.toFloat()
    ?: throw JsonParseException("expected a number")

fun JsonValue.asBool(): Boolean = (this as? JsonValue.Bool)?.value
    ?: throw JsonParseException("expected a boolean")

fun JsonValue?.orNull(): JsonValue? = this?.takeUnless { it is JsonValue.Null }

fun JsonValue.Obj.optionalLong(key: String): Long? = fields[key].orNull()?.asLong()

fun JsonValue.Obj.optionalInt(key: String): Int? = fields[key].orNull()?.asInt()

fun JsonValue.Obj.optionalFloat(key: String): Float? = fields[key].orNull()?.asFloat()

fun JsonValue.Obj.requiredLong(key: String): Long =
    fields[key]?.asLong() ?: throw JsonParseException("missing required field '$key'")

fun JsonValue.Obj.requiredInt(key: String): Int =
    fields[key]?.asInt() ?: throw JsonParseException("missing required field '$key'")

fun JsonValue.Obj.requiredString(key: String): String =
    fields[key]?.asString() ?: throw JsonParseException("missing required field '$key'")