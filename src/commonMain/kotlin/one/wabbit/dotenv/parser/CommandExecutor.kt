// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.dotenv.parser

/**
 * Controls how command substitutions are executed.
 *
 * These options are used by [CommandExecutor.runShell] and [CommandExecutor.runRaw]. The default
 * configuration is intentionally bounded and hermetic: commands do not inherit the parent process
 * environment, output is capped, and non-zero exits are reported as failures.
 *
 * @property shell command prefix used by [CommandExecutor.runShell], or `null` to use the platform
 *   default shell. The JVM default is `cmd /c` on Windows and `sh -c` elsewhere.
 * @property inheritParentEnv whether commands inherit the parent process environment before
 *   applying [baseEnv].
 * @property baseEnv environment variables made visible to the command. When [inheritParentEnv] is
 *   `false`, this is the entire command environment.
 * @property cwd optional working directory path for the command.
 * @property timeoutMs timeout in milliseconds. JVM execution rejects negative values.
 * @property maxOutputBytes maximum stdout bytes captured before the command is stopped.
 * @property redirectErrorStream whether stderr is merged into stdout. When `false`, stderr is
 *   discarded by the JVM implementation.
 * @property charsetName charset name used to decode captured stdout.
 * @property allowNonZeroExit whether a non-zero process exit code is returned as a [CommandResult]
 *   instead of throwing [CommandRunException.NonZeroExit].
 */
data class CommandOptions(
    val shell: List<String>? = null, // null -> OS default
    val inheritParentEnv: Boolean = false, // default: hermetic
    val baseEnv: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val timeoutMs: Long = 10_000,
    val maxOutputBytes: Int = 1 * 1024 * 1024,
    val redirectErrorStream: Boolean = true,
    val charsetName: String = "UTF-8",
    val allowNonZeroExit: Boolean = false,
)

/**
 * Result of a completed command substitution.
 *
 * @property stdout captured stdout decoded with [CommandOptions.charsetName]. The JVM executor
 *   trims trailing carriage returns and line feeds.
 * @property exitCode process exit code.
 * @property bytesRead number of stdout bytes captured before decoding.
 */
data class CommandResult(val stdout: String, val exitCode: Int, val bytesRead: Int)

/**
 * Base type for command execution failures reported by [CommandExecutor].
 *
 * Dotenv parsing maps these exceptions to the corresponding [DotenvParseException] variants so
 * callers parsing `.env` text receive line-oriented errors.
 */
sealed class CommandRunException(message: String) : RuntimeException(message) {
    /**
     * Thrown when command execution exceeds [timeoutMs].
     *
     * @property timeoutMs timeout that was applied.
     * @property cmd rendered command string used in the error message.
     */
    class Timeout(val timeoutMs: Long, val cmd: String) :
        CommandRunException("Command timed out after ${timeoutMs}ms: $cmd")

    /**
     * Thrown when captured stdout exceeds [maxBytes].
     *
     * @property maxBytes configured stdout byte limit.
     * @property cmd rendered command string used in the error message.
     */
    class OutputTooLarge(val maxBytes: Int, val cmd: String) :
        CommandRunException("Command output exceeded $maxBytes bytes: $cmd")

    /**
     * Thrown when a command exits non-zero and non-zero exits are not allowed.
     *
     * @property exit process exit code.
     * @property cmd rendered command string used in the error message.
     * @property stdout raw captured stdout. On the JVM this preserves trailing newlines.
     */
    class NonZeroExit(val exit: Int, val cmd: String, val stdout: String) :
        CommandRunException("Command exited with $exit: $cmd\n$stdout")

    /**
     * Thrown when the current platform cannot execute commands for substitution.
     *
     * @property cmd rendered command string used in the error message.
     */
    class Unsupported(val cmd: String) :
        CommandRunException("Command substitution is not supported on this platform: $cmd")
}

/**
 * Executes shell and raw commands for dotenv command substitution.
 *
 * Implementations must enforce [CommandOptions.timeoutMs], [CommandOptions.maxOutputBytes], and
 * non-zero exit handling consistently enough for callers to bound untrusted `.env` input. The
 * multiplatform [ProcessCommandExecutor] currently executes commands on JVM and reports
 * [CommandRunException.Unsupported] on Android and native targets.
 */
interface CommandExecutor {
    /**
     * Executes [cmd] through a shell.
     *
     * @param cmd command text passed after the configured shell prefix.
     * @param options execution limits and environment policy.
     * @return captured command output and exit metadata.
     * @throws CommandRunException when execution times out, exceeds output limits, exits non-zero
     *   without [CommandOptions.allowNonZeroExit], or is unsupported on the current platform.
     */
    fun runShell(cmd: String, options: CommandOptions = CommandOptions()): CommandResult

    /**
     * Executes [argv] directly without shell parsing.
     *
     * @param argv executable and arguments. Implementations reject empty lists.
     * @param options execution limits and environment policy.
     * @return captured command output and exit metadata.
     * @throws IllegalArgumentException when [argv] is empty or command limits are invalid.
     * @throws CommandRunException when execution times out, exceeds output limits, exits non-zero
     *   without [CommandOptions.allowNonZeroExit], or is unsupported on the current platform.
     */
    fun runRaw(argv: List<String>, options: CommandOptions = CommandOptions()): CommandResult
}

/**
 * Default process-backed [CommandExecutor].
 *
 * JVM targets execute commands through `one.wabbit:kotlin-exec`. Android and native targets expose
 * the same API but throw [CommandRunException.Unsupported] for command execution.
 */
expect class ProcessCommandExecutor() : CommandExecutor {
    /**
     * Executes [cmd] through the platform shell.
     *
     * @see CommandExecutor.runShell
     */
    override fun runShell(cmd: String, options: CommandOptions): CommandResult

    /**
     * Executes [argv] directly.
     *
     * @see CommandExecutor.runRaw
     */
    override fun runRaw(argv: List<String>, options: CommandOptions): CommandResult
}

/**
 * Looks up an environment variable from the host platform.
 *
 * The parser only calls this function when [DotenvParseOptions.allowSystemEnv] is `true`. Native
 * targets currently return `null` for all names.
 */
expect fun platformSystemEnv(name: String): String?
