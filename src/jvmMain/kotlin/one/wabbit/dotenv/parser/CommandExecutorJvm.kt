// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.dotenv.parser

import java.io.File
import java.nio.charset.Charset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.files.Path as KxPath
import one.wabbit.exec.EnvPolicy
import one.wabbit.exec.Exec
import one.wabbit.exec.ExecError
import one.wabbit.exec.ExecException
import one.wabbit.exec.ExecSpec
import one.wabbit.exec.ExitPolicy
import one.wabbit.exec.ShutdownPolicy

/** JVM [CommandExecutor] backed by `one.wabbit:kotlin-exec`. */
actual class ProcessCommandExecutor : CommandExecutor {
    private fun defaultShell(): List<String> =
        if (
            (platformSystemEnv("ComSpec") ?: "").isNotBlank() ||
                System.getProperty("os.name").lowercase().contains("win")
        ) {
            listOf(platformSystemEnv("ComSpec") ?: "cmd", "/c")
        } else {
            listOf("sh", "-c")
        }

    /** Executes [cmd] through [CommandOptions.shell] or the JVM platform default shell. */
    actual override fun runShell(cmd: String, options: CommandOptions): CommandResult {
        val shell = options.shell ?: defaultShell()
        return runImpl(shell + cmd, options)
    }

    /** Executes [argv] directly without shell parsing. */
    actual override fun runRaw(argv: List<String>, options: CommandOptions): CommandResult {
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
                EnvPolicy.Inherit(overlay = options.baseEnv)
            } else {
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
                cwd = options.cwd?.let { KxPath(File(it).path) },
                env = envPolicy,
                stdin = ExecSpec.Input.None,
                stdout =
                    ExecSpec.StdoutSpec.Pipe(
                        ExecSpec.SinkSpec.Capture(
                            maxBytes = stdoutSink.maxBytes,
                            keep = stdoutSink.keep,
                            overflow = stdoutSink.overflow,
                        )
                    ),
                stderr =
                    if (options.redirectErrorStream) {
                        ExecSpec.StderrSpec.ToStdout
                    } else {
                        ExecSpec.StderrSpec.Discard
                    },
                timeout = options.timeoutMs.milliseconds,
                shutdown = ShutdownPolicy.KillTree,
                cleanupTimeout = 2.seconds,
                exitPolicy = ExitPolicy.Return,
            )

        val r =
            try {
                Exec.execBlocking(spec)
            } catch (e: ExecException) {
                when (e.error) {
                    is ExecError.TimedOut ->
                        throw CommandRunException.Timeout(options.timeoutMs, cmdStr)

                    is ExecError.OutputLimitExceeded ->
                        throw CommandRunException.OutputTooLarge(options.maxOutputBytes, cmdStr)

                    else -> {
                        val c = e.error.cause
                        if (c != null) throw c
                        throw e
                    }
                }
            }

        val cap = r.stdout
        val stdoutBytes = cap?.bytes ?: ByteArray(0)
        val charset = Charset.forName(options.charsetName)
        val stdoutRaw = stdoutBytes.toString(charset)
        val stdoutTrimmed = stdoutRaw.trimEnd('\r', '\n')

        val exit = r.exitCode.value
        if (!options.allowNonZeroExit && exit != 0) {
            throw CommandRunException.NonZeroExit(exit, cmdStr, stdoutRaw)
        }

        return CommandResult(stdout = stdoutTrimmed, exitCode = exit, bytesRead = stdoutBytes.size)
    }
}

/** Returns the process environment variable named [name]. */
actual fun platformSystemEnv(name: String): String? = System.getenv(name)
