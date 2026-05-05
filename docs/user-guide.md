# User Guide

`kotlin-dotenv-parser` parses dotenv text into ordered entries. It is a parser, not a process-wide
environment mutator.

## Basic Parsing

```kotlin
import one.wabbit.dotenv.parser.parseDotenvText

val entries =
    parseDotenvText(
        """
        APP_ENV=production
        PORT=8080
        PASSWORD='pa#ss with spaces'
        """.trimIndent()
    )

val env = entries.associate { it.key to it.value }
```

The parser ignores blank lines and full-line comments. Each assignment must contain `=`.

## Keys

Keys may contain letters, digits, underscores, dots, and dashes. An optional `export` prefix is
accepted and ignored:

```dotenv
export APP_ENV=production
SERVICE.NAME=api
SERVICE-VERSION=2026.05
```

If you need stricter shell-style names for braced variable expansion, set
`DotenvParseOptions(strictNames = true)`. This affects `${...}` expansion names, not assignment key
parsing.

## Values

Values may be unquoted, single-quoted, or double-quoted.

```dotenv
A=plain
B='literal value with # and spaces'
C="double quoted\nwith escapes"
```

Single-quoted values are literal and can span lines. Double-quoted values can also span lines and
support escapes for newline, carriage return, tab, quote, backslash, and dollar. Unquoted values are
trimmed and support escapes for whitespace, dollar, hash, newline, carriage return, and tab.

Trailing comments start with `#` only after whitespace:

```dotenv
A=1 # comment
B=2#hash-is-part-of-value
```

## Variable Expansion

Variable expansion is opt-in:

```kotlin
import one.wabbit.dotenv.parser.DotenvParseOptions
import one.wabbit.dotenv.parser.parseDotenvText

val entries =
    parseDotenvText(
        """
        HOST=example.test
        URL=https://${'$'}{HOST}/api
        DEFAULTED=${'$'}{MISSING:-fallback}
        """.trimIndent(),
        DotenvParseOptions(expandVariables = true)
    )
```

Supported forms include `$NAME`, `${NAME}`, `${NAME:-word}`, `${NAME-word}`, `${NAME:+word}`,
`${NAME+word}`, `${NAME:=word}`, `${NAME=word}`, `${NAME:?message}`, and `${NAME?message}`.

The colon variants treat empty strings like unset values. The non-colon variants only treat missing
variables as unset.

Expansion is ordered. Earlier parsed entries and `initialEnv` are visible to later entries. Later
assignments do not retroactively change already parsed values.

## Command Substitution

Command substitution is opt-in and should only be enabled for trusted dotenv input:

```kotlin
import one.wabbit.dotenv.parser.DotenvParseOptions
import one.wabbit.dotenv.parser.parseDotenvText

val entries =
    parseDotenvText(
        "USER=$(whoami)",
        DotenvParseOptions(commandSubstitution = true)
    )
```

The JVM implementation executes commands through a shell. Android and native implementations report
command substitution as unsupported.

The parser applies these safeguards by default:

- commands do not inherit the parent process environment
- stdout is capped at 1 MiB
- each command has a 10 second timeout
- each value may contain at most five command substitutions
- non-zero exits fail parsing
- backtick substitutions are rejected

Use `DotenvParseOptions` to tune those limits when you intentionally need different behavior.

## Errors

All parser errors extend `DotenvParseException`. The exception carries the one-based line number,
optional column, and source context used to render the diagnostic.

Catch specific subclasses when you need to distinguish malformed syntax from expansion and command
execution failures.
