// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.dotenv.parser

/**
 * Native [CommandExecutor] placeholder.
 *
 * Native command substitution is not supported by this library, so both execution methods throw
 * [CommandRunException.Unsupported].
 */
actual class ProcessCommandExecutor : CommandExecutor {
    /** Always throws [CommandRunException.Unsupported] on native targets. */
    actual override fun runShell(cmd: String, options: CommandOptions): CommandResult {
        throw CommandRunException.Unsupported(cmd)
    }

    /** Always throws [CommandRunException.Unsupported] on native targets. */
    actual override fun runRaw(argv: List<String>, options: CommandOptions): CommandResult {
        throw CommandRunException.Unsupported(argv.joinToString(" "))
    }
}

/** Returns `null` for every lookup on native targets. */
actual fun platformSystemEnv(name: String): String? = null
