# Module kotlin-dotenv-parser

Kotlin Multiplatform parser for dotenv documents.

The module exposes a small API centered on `parseDotenvText`, `DotenvParseOptions`, and
`DotenvEntry`. It also exposes lower-level expansion and command-execution types for callers that
need to reuse the POSIX-style string expander outside the dotenv parser.

Variable expansion and command substitution are opt-in. The default parser mode does not read the
host environment and does not execute commands.
