// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.dotenv.parser

/**
 * Android [CommandExecutor] placeholder.
 *
 * Android command substitution is not supported by this library, so both execution methods throw
 * [CommandRunException.Unsupported].
 */
actual class ProcessCommandExecutor : CommandExecutor {
    /** Always throws [CommandRunException.Unsupported] on Android. */
    actual override fun runShell(cmd: String, options: CommandOptions): CommandResult {
        throw CommandRunException.Unsupported(cmd)
    }

    /** Always throws [CommandRunException.Unsupported] on Android. */
    actual override fun runRaw(argv: List<String>, options: CommandOptions): CommandResult {
        throw CommandRunException.Unsupported(argv.joinToString(" "))
    }
}

/** Returns the Android process environment variable named [name]. */
actual fun platformSystemEnv(name: String): String? = System.getenv(name)
