# Development

Use the workspace `dev` tooling from `/Users/wabbit/ws/datatron`.

## Useful Commands

```bash
./dev verify docs kotlin-dotenv-parser
./dev build kotlin-dotenv-parser
./dev publish --dry-run kotlin-dotenv-parser
```

## Project Shape

The library is a Kotlin Multiplatform module configured from `root.clj`.

The public API is split across three common source files:

- `Parser.kt` contains dotenv parsing options, entries, exceptions, and `parseDotenvText`.
- `PosixStringExpander.kt` contains the reusable POSIX-style string expander.
- `CommandExecutor.kt` contains command execution abstractions used by command substitution.

Platform source sets provide `ProcessCommandExecutor` and `platformSystemEnv` actuals.

## Documentation Expectations

Keep the README focused on installation, quickstart, supported behavior, status, and links to the
docs directory. Keep KDoc synchronized with actual parser behavior and command-substitution support.

When behavior changes, update the README, user guide, API notes, troubleshooting page, and
`CHANGELOG.md` in the same change.
