package one.wabbit.dotenv.parser

import one.wabbit.exec.EnvPolicy
import one.wabbit.exec.Exec
import one.wabbit.exec.ExecError
import one.wabbit.exec.ExecException
import one.wabbit.exec.ExecSpec
import one.wabbit.exec.ExitPolicy
import one.wabbit.exec.ShutdownPolicy
import one.wabbit.exec.VirtualThreadsPolicy
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class CommandOptions(
    val shell: List<String>? = null, // null → OS default
    val inheritParentEnv: Boolean = false, // default: hermetic
    val baseEnv: Map<String, String> = emptyMap(),
    val cwd: File? = null,
    val timeoutMs: Long = 10_000,
    val maxOutputBytes: Int = 1 * 1024 * 1024,
    val redirectErrorStream: Boolean = true,
    val charset: Charset = StandardCharsets.UTF_8,
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
}

interface CommandExecutor {
    fun runShell(cmd: String, options: CommandOptions = CommandOptions()): CommandResult

    fun runRaw(argv: List<String>, options: CommandOptions = CommandOptions()): CommandResult
}

class ProcessCommandExecutor : CommandExecutor {
    private fun defaultShell(): List<String> =
        if (System.getProperty("os.name").lowercase().contains("win")) {
            val comspec = System.getenv("ComSpec") ?: "cmd"
            listOf(comspec, "/c")
        } else {
            listOf("sh", "-c")
        }

    override fun runShell(cmd: String, options: CommandOptions): CommandResult {
        val shell = options.shell ?: defaultShell()
        return runImpl(shell + cmd, options)
    }

    override fun runRaw(argv: List<String>, options: CommandOptions): CommandResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        return runImpl(argv, options)
    }

    @Throws(CommandRunException::class)
    private fun runImpl(argv: List<String>, options: CommandOptions): CommandResult {
        require(argv.isNotEmpty()) { "argv must not be empty" }
        require(options.timeoutMs >= 0) { "timeoutMs must be >= 0" }
        require(options.maxOutputBytes > 0) { "maxOutputBytes must be > 0" }

        val cmdStr = argv.joinToString(" ")

        val envPolicy: EnvPolicy =
            if (options.inheritParentEnv) {
                // Inherit parent's env, overlay baseEnv.
                EnvPolicy.Inherit(overlay = options.baseEnv)
            } else {
                // Hermetic: minimal OS env + baseEnv only.
                EnvPolicy.Hermetic(base = options.baseEnv)
            }

        val stdoutSink =
            ExecSpec.SinkSpec.Capture(
                maxBytes = options.maxOutputBytes,
                keep = ExecSpec.Keep.Head,
                overflow = ExecSpec.OverflowPolicy.KillProcess,
            )

        val spec =
            ExecSpec(
                argv = argv,
                cwd = options.cwd?.toPath(),
                env = envPolicy,
                stdin = ExecSpec.Input.None,
                stdout = ExecSpec.StdoutSpec.Pipe(stdoutSink),
                // If not merging stderr, discard it to avoid deadlocks.
                // (The previous implementation *also* didn't drain stderr when not merged,
                // it just did it in a more exciting, deadlock-prone way.)
                stderr =
                    if (options.redirectErrorStream) {
                        ExecSpec.StderrSpec.ToStdout
                    } else {
                        ExecSpec.StderrSpec.Discard
                    },
                timeout = options.timeoutMs.milliseconds,
                shutdown = ShutdownPolicy.KillTree,
                cleanupTimeout = 2.seconds,
                exitPolicy = ExitPolicy.Return, // we enforce allowNonZeroExit ourselves
            )

        val r =
            try {
                Exec.execBlocking(spec, virtualThreads = VirtualThreadsPolicy.Prefer)
            } catch (e: ExecException) {
                when (e.error) {
                    is ExecError.TimedOut ->
                        throw CommandRunException.Timeout(options.timeoutMs, cmdStr)

                    is ExecError.OutputLimitExceeded ->
                        throw CommandRunException.OutputTooLarge(options.maxOutputBytes, cmdStr)

                    else -> {
                        // Preserve the old behavior: for "regular" failures, throw the underlying cause
                        // (IOException, etc) rather than always wrapping in ExecException.
                        val c = e.error.cause
                        if (c != null) throw c
                        throw e
                    }
                }
            }

        val cap = r.stdout
        val stdoutBytes = cap?.bytes ?: ByteArray(0)

        // Match old semantics:
        // - exceptions get *raw* stdout (untrimmed)
        // - successful return trims trailing CR/LF
        val stdoutRaw = stdoutBytes.toString(options.charset)
        val stdoutTrimmed = stdoutRaw.trimEnd('\r', '\n')

        val exit = r.exitCode.value
        if (!options.allowNonZeroExit && exit != 0) {
            throw CommandRunException.NonZeroExit(exit, cmdStr, stdoutRaw)
        }

        return CommandResult(
            stdout = stdoutTrimmed,
            exitCode = exit,
            bytesRead = stdoutBytes.size,
        )
    }
}
