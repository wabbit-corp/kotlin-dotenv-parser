package one.wabbit.dotenv.parser

actual class ProcessCommandExecutor : CommandExecutor {
    actual override fun runShell(cmd: String, options: CommandOptions): CommandResult {
        throw CommandRunException.Unsupported(cmd)
    }

    actual override fun runRaw(argv: List<String>, options: CommandOptions): CommandResult {
        throw CommandRunException.Unsupported(argv.joinToString(" "))
    }
}

actual fun platformSystemEnv(name: String): String? = System.getenv(name)
