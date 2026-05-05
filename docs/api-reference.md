# API Reference Notes

Generated Dokka output is the authoritative API reference for this library.

Build it locally from the workspace root:

```bash
./dev dokka kotlin-dotenv-parser
```

## Main Parser API

- `parseDotenvText(text, options)` parses a full dotenv document and returns entries in source
  order.
- `DotenvEntry` contains the parsed key, value, and source line.
- `DotenvParseOptions` controls variable expansion, command substitution, resolver state, command
  limits, and naming rules.
- `DotenvParseException` and its subclasses describe syntax, expansion, and command-substitution
  failures with source context.

## Expansion API

- `StringExpander` expands POSIX-style variables and command substitutions in a plain string.
- `ExpansionOptions` controls the lower-level expander.
- `VarResolver` and `MutableVarResolver` provide variable lookup and assignment support.
- `ExpansionException` reports expander failures before they are mapped to parser exceptions.

## Command API

- `CommandExecutor` is the abstraction used by command substitution.
- `ProcessCommandExecutor` is the default implementation.
- `CommandOptions` controls shell selection, environment inheritance, working directory, timeout,
  output limit, stderr handling, charset, and non-zero exit policy.
- `CommandResult` returns stdout, exit code, and captured byte count.

Command execution is implemented on JVM targets. Android and native targets return
`CommandRunException.Unsupported`.
