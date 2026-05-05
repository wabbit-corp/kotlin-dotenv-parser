# Troubleshooting

## Variable Did Not Expand

Variable expansion is disabled by default. Pass `DotenvParseOptions(expandVariables = true)`.

Expansion is not applied to single-quoted values. Use unquoted or double-quoted values when a value
must expand variables.

## Host Environment Variable Is Missing

The parser does not read the host environment by default. Use `initialEnv` for deterministic inputs,
or set `allowSystemEnv = true` if host environment fallback is intentional.

## Command Substitution Is Literal

Command substitution is disabled by default. Pass `DotenvParseOptions(commandSubstitution = true)`.

If `$()` still fails, check the target platform. The default executor runs commands on JVM targets;
Android and native targets report command substitution as unsupported.

## Backticks Fail Parsing

Backtick command substitution is rejected by default when command substitution is enabled. Prefer
`$()` syntax. If you need compatibility with existing files, set `forbidBackticks = false`.

## Command Output Is Truncated Or Fails

Command output is capped by `maxCommandOutputBytes`, which defaults to 1 MiB. Increase the limit only
for trusted commands that are expected to produce larger output.

## Non-Zero Command Exit Fails Parsing

Non-zero command exits throw `DotenvParseException.CommandNonZeroExit` by default. Set
`allowNonZeroExit = true` only if the command output is still meaningful when the command fails.

## A `#` Was Treated As A Comment

In unquoted values, `#` starts a trailing comment when it appears after whitespace. Quote the value
or escape the hash when it must be part of the value.
