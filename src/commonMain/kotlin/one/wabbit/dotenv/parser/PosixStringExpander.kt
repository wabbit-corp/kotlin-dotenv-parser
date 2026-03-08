package one.wabbit.dotenv.parser

interface VarResolver {
    fun get(name: String): String?
}

interface MutableVarResolver : VarResolver {
    fun set(name: String, value: String)
}

data class ExpansionOptions(
    val expandVariables: Boolean = true,
    val commandSubstitution: Boolean = false,
    val strictNames: Boolean = false,
    val forbidBackticks: Boolean = true,
    val maxExpansionDepth: Int = 16,
    val maxCommandsPerValue: Int = 5,
    val commandOptions: CommandOptions = CommandOptions(),
)

sealed class ExpansionException(message: String) : RuntimeException(message) {
    class BadParameterName(val name: String? = null) :
        ExpansionException("Bad parameter name ${if (name != null) "'$name' " else ""}in \${...}")

    class UnsupportedOperator(val op: Char) :
        ExpansionException("Unsupported operator '$op' in \${...}")

    class UnclosedBracedParameter : ExpansionException("Unclosed \${ in expansion")

    class ExpansionTooDeep : ExpansionException("Expansion too deep / nested")

    class ParameterNotSet(val name: String, val msg: String?) :
        ExpansionException(msg ?: "Parameter '$name' is not set")

    class BackticksForbidden : ExpansionException("Backticks are forbidden (use \$() instead)")

    class UnterminatedCommandSubstitution :
        ExpansionException("Unterminated \$() command substitution")

    class UnterminatedBacktickSubstitution :
        ExpansionException("Unterminated backtick command substitution")

    class TooManyCommandSubstitutions(val max: Int) :
        ExpansionException("Too many command substitutions (max $max)")

    class CommandFailed(val causeType: String, val messageOnly: String) :
        ExpansionException("Command failed: $causeType: $messageOnly")
}

/** Generic POSIX-ish expander; independent of dotenv and CharInput. */
class StringExpander(
    private val resolver: VarResolver,
    private val cmd: CommandExecutor? = null,
    private val options: ExpansionOptions = ExpansionOptions(),
) {
    private data class CmdCtx(var count: Int = 0)

    fun expand(input: String): String {
        if (!options.expandVariables && !options.commandSubstitution) return input
        val ctx = CmdCtx()
        val afterVars = if (options.expandVariables) expandVariables(input, 0, ctx) else input
        return if (options.commandSubstitution) expandCommands(afterVars, ctx) else afterVars
    }

    // ——— variable pass (skips command blocks) ———
    private fun expandVariables(s: String, depth: Int, ctx: CmdCtx): String {
        if (s.isEmpty()) return s
        if (depth >= options.maxExpansionDepth) throw ExpansionException.ExpansionTooDeep()

        val out = StringBuilder()
        var j = 0

        fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'

        while (j < s.length) {
            val c = s[j]
            when {
                c == '`' -> {
                    val (body, next) = scanBackticks(s, j + 1)
                    out.append('`').append(body).append('`')
                    j = next
                }
                c == '$' && j + 1 < s.length && s[j + 1] == '(' -> {
                    val (body, next) = scanDollarParen(s, j + 2)
                    out.append("$(").append(body).append(')')
                    j = next
                }
                c != '$' -> {
                    out.append(c)
                    j++
                }

                // $NAME
                j + 1 < s.length && isNameChar(s[j + 1]) -> {
                    var k = j + 2
                    while (k < s.length && isNameChar(s[k])) k++
                    val name = s.substring(j + 1, k)
                    val v = resolver.get(name)
                    out.append(v ?: "")
                    j = k
                }

                // ${...}
                j + 1 < s.length && s[j + 1] == '{' -> {
                    val (value, next) = expandBraced(s, j, depth, ctx)
                    out.append(value)
                    j = next
                }

                else -> {
                    out.append('$')
                    j++
                } // lone '$'
            }
        }
        return out.toString()
    }

    // ——— command pass ———
    private fun expandCommands(s: String, ctx: CmdCtx): String {
        if (s.isEmpty()) return s
        val out = StringBuilder()
        var j = 0
        while (j < s.length) {
            val c = s[j]
            when {
                c == '`' -> {
                    if (options.forbidBackticks) throw ExpansionException.BackticksForbidden()
                    val (cmdText, next) = scanBackticks(s, j + 1)
                    runLimit(ctx)
                    out.append(runCommand(cmdText))
                    j = next
                }
                c == '$' && j + 1 < s.length && s[j + 1] == '(' -> {
                    val (cmdText, next) = scanDollarParen(s, j + 2)
                    runLimit(ctx)
                    out.append(runCommand(cmdText))
                    j = next
                }
                else -> {
                    out.append(c)
                    j++
                }
            }
        }
        return out.toString()
    }

    private fun runLimit(ctx: CmdCtx) {
        ctx.count++
        if (ctx.count > options.maxCommandsPerValue) {
            throw ExpansionException.TooManyCommandSubstitutions(options.maxCommandsPerValue)
        }
    }

    private fun runCommand(cmdText: String): String {
        val ex =
            cmd ?: throw IllegalStateException("CommandExecutor required for command substitution")
        return try {
            ex.runShell(cmdText, options.commandOptions).stdout
        } catch (e: CommandRunException.Timeout) {
            throw ExpansionException.CommandFailed("Timeout", e.message ?: "timeout")
        } catch (e: CommandRunException.OutputTooLarge) {
            throw ExpansionException.CommandFailed(
                "OutputTooLarge",
                e.message ?: "output too large",
            )
        } catch (e: CommandRunException.NonZeroExit) {
            throw ExpansionException.CommandFailed("NonZeroExit", e.message ?: "non-zero exit")
        }
    }

    // ——— scanners ———
    private fun scanDollarParen(s: String, start: Int): Pair<String, Int> {
        val b = StringBuilder()
        var k = start
        var depth = 1
        var inS = false
        var inD = false
        var escaped = false
        while (k < s.length) {
            val c = s[k++]
            when (c) {
                '\\' -> {
                    if (inD) escaped = !escaped else escaped = false
                    b.append(c)
                }
                '"' -> {
                    if (!inS && !escaped) inD = !inD
                    escaped = false
                    b.append(c)
                }
                '\'' -> {
                    if (!inD) inS = !inS
                    escaped = false
                    b.append(c)
                }
                '(' -> {
                    if (!inS && !inD) depth++
                    escaped = false
                    b.append(c)
                }
                ')' -> {
                    if (!inS && !inD) {
                        depth--
                        if (depth == 0) return b.toString() to k
                    }
                    escaped = false
                    b.append(c)
                }
                else -> {
                    escaped = false
                    b.append(c)
                }
            }
        }
        throw ExpansionException.UnterminatedCommandSubstitution()
    }

    private fun scanBackticks(s: String, start: Int): Pair<String, Int> {
        val b = StringBuilder()
        var k = start
        var escaped = false
        while (k < s.length) {
            val c = s[k++]
            if (!escaped && c == '`') return b.toString() to k
            if (!escaped && c == '\\' && k < s.length && s[k] == '`') {
                b.append('`')
                k++
                continue
            }
            escaped = (!escaped && c == '\\')
            b.append(c)
        }
        throw ExpansionException.UnterminatedBacktickSubstitution()
    }

    // ——— ${...} ———
    private fun expandBraced(s: String, jStart: Int, depth: Int, ctx: CmdCtx): Pair<String, Int> {
        var k = jStart + 2 // skip ${
        var brace = 1
        val buf = StringBuilder()
        while (k < s.length && brace > 0) {
            val x = s[k++]
            if (x == '{') {
                brace++
            } else if (x == '}') {
                brace--
            }
            if (brace > 0) buf.append(x)
        }
        if (brace != 0) throw ExpansionException.UnclosedBracedParameter()

        val expr = buf.toString()
        var p = 0

        fun next() = if (p < expr.length) expr[p] else '\u0000'

        fun take(): Char = expr[p++]

        fun isStartStrict(c: Char) = c == '_' || c.isLetter()

        fun isCharStrict(c: Char) = c.isLetterOrDigit() || c == '_'

        fun isCharLoose(c: Char) = c.isLetterOrDigit() || c == '_' || c == '.' || c == '-'

        val startOk =
            if (options.strictNames) {
                isStartStrict(next())
            } else {
                (next().isLetterOrDigit() || next() == '_' || next() == '.' || next() == '-')
            }
        if (!startOk) throw ExpansionException.BadParameterName()

        val nameStart = p
        if (options.strictNames) {
            if (!isStartStrict(next())) throw ExpansionException.BadParameterName()
            p++
            while (p < expr.length && isCharStrict(next())) p++
        } else {
            while (p < expr.length && isCharLoose(next())) p++
        }
        val name = expr.substring(nameStart, p)

        if (p == expr.length) {
            return (resolver.get(name) ?: "") to k
        } else {
            val colon =
                if (next() == ':') {
                    p++
                    true
                } else {
                    false
                }
            val op = take()
            if (op !in charArrayOf('-', '+', '=', '?'))
                throw ExpansionException.UnsupportedOperator(op)
            val word = if (p < expr.length) expr.substring(p) else ""

            fun evalWord(): String {
                if (depth >= options.maxExpansionDepth) throw ExpansionException.ExpansionTooDeep()
                val afterVars = expandVariables(word, depth + 1, ctx)
                return if (options.commandSubstitution) expandCommands(afterVars, ctx)
                else afterVars
            }

            val presentAndNotEmpty =
                resolver.get(name)?.let { if (colon) it.isNotEmpty() else true } ?: false
            val current = resolver.get(name)

            val result =
                when (op) {
                    '-' -> if (presentAndNotEmpty) current ?: "" else evalWord()
                    '+' -> if (presentAndNotEmpty) evalWord() else ""
                    '=' -> {
                        if (!presentAndNotEmpty) {
                            val assigned = evalWord()
                            if (resolver is MutableVarResolver) resolver.set(name, assigned)
                            assigned
                        } else {
                            current ?: ""
                        }
                    }
                    '?' -> {
                        if (!presentAndNotEmpty) {
                            val msg = if (word.isEmpty()) null else evalWord()
                            throw ExpansionException.ParameterNotSet(name, msg)
                        }
                        current ?: ""
                    }
                    else -> "" // unreachable
                }
            return result to k
        }
    }
}
