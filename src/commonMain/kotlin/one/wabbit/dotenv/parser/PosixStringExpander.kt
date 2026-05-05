// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.dotenv.parser

/** Resolves variable names during POSIX-style string expansion. */
interface VarResolver {
    /**
     * Returns the value for [name], or `null` when the variable is unset.
     *
     * Empty strings are considered set values. Operators with a `:` prefix, such as `${NAME:-x}`,
     * decide separately whether an empty value should behave like an unset value.
     */
    fun get(name: String): String?
}

/** Resolver that can be updated by assignment-style parameter expansion. */
interface MutableVarResolver : VarResolver {
    /**
     * Stores [value] for [name].
     *
     * This is used by `${NAME=word}` and `${NAME:=word}`. When a [StringExpander] is constructed
     * with a read-only [VarResolver], assignment operators return the assigned value but cannot
     * persist it.
     */
    fun set(name: String, value: String)
}

/**
 * Options for [StringExpander].
 *
 * @property expandVariables whether `$NAME` and `${NAME}` forms are expanded.
 * @property commandSubstitution whether `$()` and, when allowed, backtick command substitutions are
 *   executed.
 * @property strictNames whether braced parameter names must be shell-style identifiers. When
 *   `false`, braced names may also contain dots and dashes.
 * @property forbidBackticks whether backtick command substitution is rejected when
 *   [commandSubstitution] is enabled.
 * @property maxExpansionDepth maximum recursive depth for nested parameter expansion.
 * @property maxCommandsPerValue maximum command substitutions allowed during one [StringExpander]
 *   expansion.
 * @property commandOptions execution options used for command substitution.
 */
data class ExpansionOptions(
    val expandVariables: Boolean = true,
    val commandSubstitution: Boolean = false,
    val strictNames: Boolean = false,
    val forbidBackticks: Boolean = true,
    val maxExpansionDepth: Int = 16,
    val maxCommandsPerValue: Int = 5,
    val commandOptions: CommandOptions = CommandOptions(),
)

/** Base type for failures raised by [StringExpander]. */
sealed class ExpansionException(message: String) : RuntimeException(message) {
    /**
     * Raised when a braced parameter has an invalid variable name.
     *
     * @property name invalid name text when it was available.
     */
    class BadParameterName(val name: String? = null) :
        ExpansionException("Bad parameter name ${if (name != null) "'$name' " else ""}in \${...}")

    /**
     * Raised when a braced parameter uses an operator other than `-`, `+`, `=`, or `?`.
     *
     * @property op unsupported operator character.
     */
    class UnsupportedOperator(val op: Char) :
        ExpansionException("Unsupported operator '$op' in \${...}")

    /** Raised when a `${...}` expression is missing its closing brace. */
    class UnclosedBracedParameter : ExpansionException("Unclosed \${ in expansion")

    /** Raised when recursive parameter expansion exceeds [ExpansionOptions.maxExpansionDepth]. */
    class ExpansionTooDeep : ExpansionException("Expansion too deep / nested")

    /**
     * Raised by `${NAME?message}` and `${NAME:?message}` when a required variable is not set.
     *
     * @property name required variable name.
     * @property msg optional custom message supplied by the expansion expression.
     */
    class ParameterNotSet(val name: String, val msg: String?) :
        ExpansionException(msg ?: "Parameter '$name' is not set")

    /** Raised when backtick command substitution is encountered while backticks are forbidden. */
    class BackticksForbidden : ExpansionException("Backticks are forbidden (use \$() instead)")

    /** Raised when a `$()` command substitution is missing its closing parenthesis. */
    class UnterminatedCommandSubstitution :
        ExpansionException("Unterminated \$() command substitution")

    /** Raised when a backtick command substitution is missing its closing backtick. */
    class UnterminatedBacktickSubstitution :
        ExpansionException("Unterminated backtick command substitution")

    /**
     * Raised when command substitution exceeds [max].
     *
     * @property max configured maximum number of commands per expanded value.
     */
    class TooManyCommandSubstitutions(val max: Int) :
        ExpansionException("Too many command substitutions (max $max)")

    /**
     * Wraps a command-execution failure in expander-specific form.
     *
     * @property causeType stable command failure category, such as `Timeout` or `NonZeroExit`.
     * @property messageOnly failure message without additional source context.
     */
    class CommandFailed(val causeType: String, val messageOnly: String) :
        ExpansionException("Command failed: $causeType: $messageOnly")
}

/**
 * Expands POSIX-style variables and command substitutions in a string.
 *
 * The expander is independent of dotenv parsing and works on plain strings. Variable expansion
 * supports `$NAME`, `${NAME}`, and the `-`, `+`, `=`, and `?` braced operators with optional `:`
 * semantics. Command output is inserted literally; it is not passed through a second variable
 * expansion pass.
 *
 * @param resolver variable resolver used by simple and braced parameter expansion.
 * @param cmd command executor used when [ExpansionOptions.commandSubstitution] is enabled.
 * @param options expansion and command-execution options.
 */
class StringExpander(
    private val resolver: VarResolver,
    private val cmd: CommandExecutor? = null,
    private val options: ExpansionOptions = ExpansionOptions(),
) {
    private data class CmdCtx(var count: Int = 0)

    /**
     * Expands [input] according to [options].
     *
     * When both variable expansion and command substitution are disabled, this returns [input]
     * unchanged. Otherwise, variable expansion runs first and skips command-substitution bodies so
     * shell text is not modified before command execution.
     *
     * @throws ExpansionException when expansion syntax is invalid or a configured limit is hit.
     * @throws IllegalStateException when command substitution is enabled but no command executor
     *   was provided.
     */
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
