# kotlin-dotenv-parser

`kotlin-dotenv-parser` is a Kotlin Multiplatform parser for `.env` documents.

It turns dotenv text into ordered key/value entries, with opt-in POSIX-style variable expansion and
bounded command substitution for loaders that need shell-compatible behavior.

The default mode is intentionally conservative: it parses values, quotes, comments, and `export`
prefixes without reading the host environment or executing commands.

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("one.wabbit:kotlin-dotenv-parser:0.0.1")
}
```

## Quick Start

```kotlin
import one.wabbit.dotenv.parser.parseDotenvText

val entries =
    parseDotenvText(
        """
        APP_ENV=production
        PORT=8080
        DB_URL="postgres://user:pass@db.internal:5432/app"
        PASSWORD='pa#ss with spaces'
        """.trimIndent()
    )

val env = entries.associate { it.key to it.value }

check(env["APP_ENV"] == "production")
check(env["PORT"] == "8080")
check(env["PASSWORD"] == "pa#ss with spaces")
```

Each result is a `DotenvEntry` containing the key, parsed value, and one-based source line.

## Supported Syntax

The parser accepts the common dotenv subset used by most loaders:

- blank lines and full-line comments
- `KEY=value` assignments
- whitespace around keys, `=`, and values
- optional `export` before an assignment
- unquoted values
- single-quoted values
- double-quoted values
- multiline single-quoted and double-quoted values
- trailing comments after whitespace
- keys containing letters, digits, underscores, dots, and dashes

Unquoted `#` starts a trailing comment only when it appears after whitespace. Otherwise it remains
part of the value:

```dotenv
A=1 # comment
B=2#not-a-comment
```

## Escapes And Quotes

Single-quoted values are literal and may span lines.

Double-quoted and unquoted values support the newline, carriage-return, tab, dollar, and hash escapes
used by the parser:

```dotenv
A="line1\nline2"
B=\$HOME\ \#literal
```

Escaped dollars are protected from variable expansion and restored as literal `$` characters in the
final value.

## Variable Expansion

Variable expansion is disabled by default. Enable it with `DotenvParseOptions`:

```kotlin
import one.wabbit.dotenv.parser.DotenvParseOptions
import one.wabbit.dotenv.parser.parseDotenvText

val text =
    """
    HOST=example.test
    URL=https://${'$'}{HOST}/api
    FALLBACK=${'$'}{MISSING:-default}
    """.trimIndent()

val env =
    parseDotenvText(
        text,
        DotenvParseOptions(expandVariables = true)
    ).associate { it.key to it.value }

check(env["URL"] == "https://example.test/api")
check(env["FALLBACK"] == "default")
```

Expansion is applied to unquoted and double-quoted values only. Single-quoted values remain literal.

The resolver is stateful and ordered. `initialEnv` is loaded first, then each parsed entry updates
the environment visible to later entries. System environment lookup is disabled unless
`allowSystemEnv = true`.

## Command Substitution

Command substitution is also disabled by default. When enabled, `$()` substitutions execute through
`ProcessCommandExecutor`:

```kotlin
import one.wabbit.dotenv.parser.DotenvParseOptions
import one.wabbit.dotenv.parser.parseDotenvText

val entries =
    parseDotenvText(
        "BUILD_USER=$(whoami)",
        DotenvParseOptions(commandSubstitution = true)
    )
```

Command execution is bounded by timeout, output-size, and count limits. By default commands run with
a hermetic environment containing only `initialEnv` and variables parsed so far.

Backtick substitutions are rejected by default when command substitution is enabled. Set
`forbidBackticks = false` only when you intentionally need backtick compatibility.

Command substitution currently executes on JVM targets. Android and native targets expose the same
API but report command substitution as unsupported.

## Error Handling

Parsing failures throw `DotenvParseException`. Error messages include the source line, optional
column, and a caret when the parser knows the exact location.

```kotlin
import one.wabbit.dotenv.parser.DotenvParseException
import one.wabbit.dotenv.parser.parseDotenvText

try {
    parseDotenvText("A 1")
} catch (e: DotenvParseException.SyntaxError) {
    println(e.message)
}
```

Specific exception subclasses distinguish missing `=`, unterminated quotes, bad expansion syntax,
required missing variables, command timeouts, output limits, non-zero exits, and unsupported command
substitution.

## Status

This library is pre-1.0 and intentionally small. The parsing API is suitable for use, but command
substitution should be treated as an advanced feature because it executes local processes and is not
available on every target.

## Documentation

- [User guide](docs/user-guide.md)
- [API reference notes](docs/api-reference.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Development](docs/development.md)

Generated API docs can be built locally with Dokka. See [API reference notes](docs/api-reference.md)
for the command.

## Release Notes

- [CHANGELOG.md](CHANGELOG.md)

## Licensing

This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0) for open
source use.

For commercial use, contact Wabbit Consulting Corporation at `wabbit@wabbit.one`.

## Contributing

Before contributions can be merged, contributors need to agree to the repository CLA.
