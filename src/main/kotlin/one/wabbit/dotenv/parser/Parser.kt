@file:Suppress("SpellCheckingInspection")

package one.wabbit.dotenv.parser

import one.wabbit.parsing.CharInput

// ———————————————————————————————————————————————————————————————————————————
// Error model (same public API)
// ———————————————————————————————————————————————————————————————————————————

sealed class DotenvParseException(
    val line: Int,
    val col: Int?, // nullable when we don't know exact column
    message: String,
    val context: String,
) :
    IllegalArgumentException(
        buildString {
            append("Line ").append(line)
            if (col != null) append(", col ").append(col)
            append(": ").append(message)
            if (context.isNotEmpty()) {
                append('\n').append("    ").append(context)
                if (col != null) {
                    append('\n').append("    ")
                    val spaces = (col - 1).coerceAtLeast(0)
                    repeat(spaces) { append(' ') }
                    append('^')
                }
            }
        }
    ) {
    class SyntaxError(line: Int, col: Int?, message: String, context: String) :
        DotenvParseException(line, col, message, context)

    class UnterminatedSingleQuoted(line: Int, col: Int?, context: String) :
        DotenvParseException(line, col, "Unterminated single-quoted value", context)

    class UnterminatedDoubleQuoted(line: Int, col: Int?, context: String) :
        DotenvParseException(line, col, "Unterminated double-quoted value", context)

    class UnterminatedCommandSubstitution(line: Int, col: Int?, context: String) :
        DotenvParseException(line, col, "Unterminated \$() command substitution", context)

    class UnterminatedBacktickSubstitution(line: Int, col: Int?, context: String) :
        DotenvParseException(line, col, "Unterminated backtick command substitution", context)

    class BackticksForbidden(line: Int, col: Int?, context: String) :
        DotenvParseException(line, col, "Backticks are forbidden (use \$() instead)", context)

    class ExpansionTooDeep(line: Int, col: Int?, context: String) :
        DotenvParseException(line, col, "Expansion too deep/nested", context)

    class BadParameterName(line: Int, col: Int?, name: String, context: String) :
        DotenvParseException(line, col, "Bad parameter name '$name' in \${...}", context)

    class UnsupportedOperator(line: Int, col: Int?, op: Char, context: String) :
        DotenvParseException(line, col, "Unsupported operator '$op' in \${...}", context)

    class ParameterNotSet(line: Int, col: Int?, name: String, message: String?, context: String) :
        DotenvParseException(line, col, message ?: "Parameter '$name' is not set", context)

    class TooManyCommandSubstitutions(line: Int, col: Int?, max: Int, context: String) :
        DotenvParseException(line, col, "Too many command substitutions (max $max)", context)

    class CommandTimeout(line: Int, col: Int?, timeoutMs: Long, cmd: String, context: String) :
        DotenvParseException(line, col, "Command timed out after ${timeoutMs}ms: $cmd", context)

    class CommandNonZeroExit(line: Int, col: Int?, exit: Int, cmd: String, context: String) :
        DotenvParseException(line, col, "Command exited with $exit: $cmd", context)

    class CommandOutputTooLarge(line: Int, col: Int?, maxBytes: Int, context: String) :
        DotenvParseException(line, col, "Command output exceeded $maxBytes bytes", context)
}

// ———————————————————————————————————————————————————————————————————————————
// Data & options (unchanged)
// ———————————————————————————————————————————————————————————————————————————

data class DotenvEntry(val key: String, val value: String, val line: Int)

data class DotenvParseOptions(
    val expandVariables: Boolean = false,
    val commandSubstitution: Boolean = false,
    val initialEnv: Map<String, String> = emptyMap(),
    val allowSystemEnv: Boolean = false,
    val shell: List<String>? = null,
    val commandTimeoutMs: Long = 10_000L,
    val inheritParentEnv: Boolean = false,
    val maxCommandOutputBytes: Int = 1 * 1024 * 1024,
    val maxCommandsPerValue: Int = 5,
    val allowNonZeroExit: Boolean = false,
    val strictNames: Boolean = false,
    val forbidBackticks: Boolean = true,
    val maxExpansionDepth: Int = 16,
)

// ———————————————————————————————————————————————————————————————————————————
// Parser (CharInput) — only the expansion/exec part changed
// ———————————————————————————————————————————————————————————————————————————

private enum class Quote {
    NONE,
    SINGLE,
    DOUBLE,
}

private class DotenvParser(
    private val input: CharInput<*>,
    private val options: DotenvParseOptions,
    private val original: String,
) {
    // Sentinel used by parsing to protect $ in values; expander ignores it.
    private val ESC_DOLLAR = '\uE000'

    val entries = mutableListOf<DotenvEntry>()
    val env = LinkedHashMap<String, String>().apply { putAll(options.initialEnv) }

    private fun eof() = input.current == CharInput.EOB

    private fun isEol(c: Char) = (c == '\n' || c == '\r')

    private fun consumeEol() = input.skipNewline()

    private fun skipHSpace(): Int {
        var n = 0
        while (!eof() && (input.current == ' ' || input.current == '\t')) {
            input.advance()
            n++
        }
        return n
    }

    private fun skipToEol() {
        while (!eof() && !isEol(input.current)) input.advance()
    }

    private fun ctxLine(ln: Int): String {
        if (ln < 1) return ""
        val s = original
        val n = s.length
        var p = 0
        var cur = 1
        var start = 0
        while (p < n && cur < ln) {
            val ch = s[p++]
            if (ch == '\n') {
                cur++
                start = p
            } else if (ch == '\r') {
                cur++
                if (p < n && s[p] == '\n') p++
                start = p
            }
        }
        val iCr = s.indexOf('\r', start)
        val iLf = s.indexOf('\n', start)
        val end =
            when {
                iCr == -1 && iLf == -1 -> n
                iCr == -1 -> iLf
                iLf == -1 -> iCr
                else -> minOf(iCr, iLf)
            }
        return if (ln >= 1) s.substring(start, end) else ""
    }

    private fun syntaxErrHere(msg: String): Nothing {
        val pos = input.pos()
        throw DotenvParseException.SyntaxError(
            pos.line.toInt(),
            pos.column.toInt(),
            msg,
            ctxLine(pos.line.toInt()),
        )
    }

    private fun isKeyChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-'

    private fun maybeConsumeExport(): Boolean {
        val ok =
            input.withMark<Boolean> {
                if (!input.takeExact("export")) return@withMark null
                val c = input.current
                if (c == CharInput.EOB || isEol(c) || c == ' ' || c == '\t') {
                    skipHSpace()
                    true
                } else {
                    null
                }
            }
        return ok ?: false
    }

    private fun parseKey(): String {
        if (eof() || !isKeyChar(input.current)) syntaxErrHere("Expected key")
        val sb = StringBuilder()
        while (!eof() && isKeyChar(input.current)) {
            sb.append(input.current)
            input.advance()
        }
        return sb.toString()
    }

    private fun parseSingleQuoted(): String {
        val out = StringBuilder()
        while (!eof()) {
            val c = input.current
            if (c == '\'') {
                input.advance()
                return out.toString()
            }
            out.append(c)
            input.advance()
        }
        val ln = input.pos().line.toInt()
        throw DotenvParseException.UnterminatedSingleQuoted(ln, null, ctxLine(ln))
    }

    private fun parseDoubleQuoted(): String {
        val out = StringBuilder()
        while (!eof()) {
            val c = input.current
            when (c) {
                '"' -> {
                    input.advance()
                    return out.toString()
                }
                '\\' -> {
                    input.advance()
                    if (eof()) {
                        out.append('\\')
                        break
                    }
                    when (val e = input.current) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        '"',
                        '\\' -> out.append(e)
                        '$' -> out.append(ESC_DOLLAR)
                        else -> out.append('\\').append(e)
                    }
                    input.advance()
                }
                else -> {
                    out.append(c)
                    input.advance()
                }
            }
        }
        val ln = input.pos().line.toInt()
        throw DotenvParseException.UnterminatedDoubleQuoted(ln, null, ctxLine(ln))
    }

    private fun parseUnquoted(): String {
        val out = StringBuilder()
        while (!eof()) {
            val c = input.current
            if (isEol(c)) break
            if (c == '#') {
                if (out.isNotEmpty() && out.last().isWhitespace()) {
                    while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(
                        out.length - 1
                    )
                    skipToEol()
                    break
                }
                out.append('#')
                input.advance()
                continue
            }
            if (c == '\\') {
                input.advance()
                if (eof()) {
                    out.append('\\')
                    break
                }
                when (val e = input.current) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    ' ' -> out.append(' ')
                    '$' -> out.append(ESC_DOLLAR)
                    '#' -> out.append('#')
                    else -> out.append(e)
                }
                input.advance()
                continue
            }
            out.append(c)
            input.advance()
        }
        return out.toString().trim()
    }

    private fun parseValue(hadWhitespaceAfterEq: Boolean): Pair<String, Quote> {
        if (eof() || isEol(input.current)) return "" to Quote.NONE
        return when (val c = input.current) {
            '\'' -> {
                input.advance()
                parseSingleQuoted() to Quote.SINGLE
            }
            '"' -> {
                input.advance()
                parseDoubleQuoted() to Quote.DOUBLE
            }
            '#' ->
                if (hadWhitespaceAfterEq) {
                    skipToEol()
                    "" to Quote.NONE
                } else {
                    parseUnquoted() to Quote.NONE
                }
            else -> parseUnquoted() to Quote.NONE
        }
    }

    // —— expansion plumbing via extracted libs ——

    private class EnvResolver(
        private val env: MutableMap<String, String>,
        private val allowSystemEnv: Boolean,
    ) : MutableVarResolver {
        override fun get(name: String): String? =
            env[name] ?: if (allowSystemEnv) System.getenv(name) else null

        override fun set(name: String, value: String) {
            env[name] = value
        }
    }

    private fun makeExpander(currentLine: Int): StringExpander {
        val exec = ProcessCommandExecutor()
        val exOpts =
            ExpansionOptions(
                expandVariables = options.expandVariables,
                commandSubstitution = options.commandSubstitution,
                strictNames = options.strictNames,
                forbidBackticks = options.forbidBackticks,
                maxExpansionDepth = options.maxExpansionDepth,
                maxCommandsPerValue = options.maxCommandsPerValue,
                commandOptions =
                    CommandOptions(
                        shell = options.shell,
                        inheritParentEnv = options.inheritParentEnv,
                        baseEnv = env.toMap(), // hermetic: only parsed env is visible
                        timeoutMs = options.commandTimeoutMs,
                        maxOutputBytes = options.maxCommandOutputBytes,
                        allowNonZeroExit = options.allowNonZeroExit,
                        // charset is UTF-8 by convention for dotenv command output
                    ),
            )
        return StringExpander(
            resolver = EnvResolver(env, options.allowSystemEnv),
            cmd = exec,
            options = exOpts,
        )
    }

    private fun mapExpandExceptions(e: Throwable, firstLine: Int): Nothing {
        val ctx = ctxLine(firstLine)
        when (e) {
            is ExpansionException.UnclosedBracedParameter ->
                throw DotenvParseException.SyntaxError(
                    firstLine,
                    null,
                    "Unclosed \${ in expansion",
                    ctx,
                )
            is ExpansionException.BadParameterName ->
                throw DotenvParseException.BadParameterName(firstLine, null, e.name ?: "", ctx)
            is ExpansionException.UnsupportedOperator ->
                throw DotenvParseException.UnsupportedOperator(firstLine, null, e.op, ctx)
            is ExpansionException.ExpansionTooDeep ->
                throw DotenvParseException.ExpansionTooDeep(firstLine, null, ctx)
            is ExpansionException.ParameterNotSet ->
                throw DotenvParseException.ParameterNotSet(firstLine, null, e.name, e.msg, ctx)
            is ExpansionException.BackticksForbidden ->
                throw DotenvParseException.BackticksForbidden(firstLine, null, ctx)
            is ExpansionException.UnterminatedCommandSubstitution ->
                throw DotenvParseException.UnterminatedCommandSubstitution(firstLine, null, ctx)
            is ExpansionException.UnterminatedBacktickSubstitution ->
                throw DotenvParseException.UnterminatedBacktickSubstitution(firstLine, null, ctx)
            is ExpansionException.TooManyCommandSubstitutions ->
                throw DotenvParseException.TooManyCommandSubstitutions(firstLine, null, e.max, ctx)
            is ExpansionException.CommandFailed -> {
                // Distinguish underlying CommandRunException kinds if possible by message
                val m = e.message ?: ""
                when {
                    m.contains("timed out", ignoreCase = true) ->
                        throw DotenvParseException.CommandTimeout(
                            firstLine,
                            null,
                            options.commandTimeoutMs,
                            "<shell>",
                            ctx,
                        )
                    m.contains("exceeded", ignoreCase = true) ->
                        throw DotenvParseException.CommandOutputTooLarge(
                            firstLine,
                            null,
                            options.maxCommandOutputBytes,
                            ctx,
                        )
                    m.contains("exited", ignoreCase = true) || m.contains("NonZeroExit") ->
                        throw DotenvParseException.CommandNonZeroExit(
                            firstLine,
                            null,
                            -1,
                            "<shell>",
                            ctx,
                        )
                    else ->
                        throw DotenvParseException.SyntaxError(
                            firstLine,
                            null,
                            e.message ?: "Command failed",
                            ctx,
                        )
                }
            }
            is CommandRunException.Timeout ->
                throw DotenvParseException.CommandTimeout(
                    firstLine,
                    null,
                    options.commandTimeoutMs,
                    "<shell>",
                    ctx,
                )
            is CommandRunException.OutputTooLarge ->
                throw DotenvParseException.CommandOutputTooLarge(
                    firstLine,
                    null,
                    options.maxCommandOutputBytes,
                    ctx,
                )
            is CommandRunException.NonZeroExit ->
                throw DotenvParseException.CommandNonZeroExit(
                    firstLine,
                    null,
                    e.exit,
                    "<shell>",
                    ctx,
                )
            else -> throw e
        }
    }

    fun parse(): List<DotenvEntry> {
        var atLineStart = true

        while (!eof()) {
            if (atLineStart) skipHSpace()

            if (eof()) break
            if (isEol(input.current)) {
                consumeEol()
                atLineStart = true
                continue
            }
            if (atLineStart && input.current == '#') {
                skipToEol()
                consumeEol()
                atLineStart = true
                continue
            }

            val firstLine = input.pos().line.toInt()

            if (maybeConsumeExport()) {
                if (eof() || !isKeyChar(input.current)) {
                    throw DotenvParseException.SyntaxError(
                        firstLine,
                        null,
                        "Expected key after 'export'",
                        ctxLine(firstLine),
                    )
                }
            }

            val key = parseKey()
            skipHSpace()
            if (eof() || input.current != '=') {
                syntaxErrHere("Expected '=' after key '$key'")
            }
            input.advance() // '='
            val wsAfterEq = skipHSpace() > 0

            val (raw, quote) = parseValue(wsAfterEq)
            skipHSpace()
            if (!eof() && input.current == '#') skipToEol()
            if (!eof() && isEol(input.current)) consumeEol()

            var v = raw
            if (quote != Quote.SINGLE && (options.expandVariables || options.commandSubstitution)) {
                val expander = makeExpander(firstLine)
                try {
                    v = expander.expand(v)
                } catch (t: Throwable) {
                    mapExpandExceptions(t, firstLine)
                }
            }

            // Turn sentinel back to literal '$'
            v = buildString { for (c in v) append(if (c == ESC_DOLLAR) '$' else c) }

            entries += DotenvEntry(key, v, firstLine)
            env[key] = v
            atLineStart = true
        }

        return entries
    }
}

/** Public API unchanged. */
fun parseDotenvText(
    text: String,
    options: DotenvParseOptions = DotenvParseOptions(),
): List<DotenvEntry> {
    val input = CharInput.withPosOnlySpans(text)
    return DotenvParser(input, options, text).parse()
}
