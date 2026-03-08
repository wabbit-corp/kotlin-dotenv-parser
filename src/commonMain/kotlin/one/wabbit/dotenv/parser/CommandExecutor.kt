package one.wabbit.dotenv.parser

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

data class CommandResult(val stdout: String, val exitCode: Int, val bytesRead: Int)

sealed class CommandRunException(message: String) : RuntimeException(message) {
    class Timeout(val timeoutMs: Long, val cmd: String) :
        CommandRunException("Command timed out after ${timeoutMs}ms: $cmd")

    class OutputTooLarge(val maxBytes: Int, val cmd: String) :
        CommandRunException("Command output exceeded $maxBytes bytes: $cmd")

    class NonZeroExit(val exit: Int, val cmd: String, val stdout: String) :
        CommandRunException("Command exited with $exit: $cmd\n$stdout")

    class Unsupported(val cmd: String) :
        CommandRunException("Command substitution is not supported on this platform: $cmd")
}

interface CommandExecutor {
    fun runShell(cmd: String, options: CommandOptions = CommandOptions()): CommandResult

    fun runRaw(argv: List<String>, options: CommandOptions = CommandOptions()): CommandResult
}

expect class ProcessCommandExecutor() : CommandExecutor {
    override fun runShell(cmd: String, options: CommandOptions): CommandResult

    override fun runRaw(argv: List<String>, options: CommandOptions): CommandResult
}

expect fun platformSystemEnv(name: String): String?
