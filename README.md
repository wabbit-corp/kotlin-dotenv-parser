**Short answer:** there’s no single, universal “.env spec.” It’s a de‑facto convention popularized by Foreman/Heroku and various *dotenv* libraries. Node.js now documents **its own** `.env` syntax (because there wasn’t a formal one), and other ecosystems have subtly different rules. So you should write for the *lowest common denominator* unless you control the loader. ([ddollar.github.io][1])

---

## What most loaders agree on (the safe subset)

* **One setting per line** as `KEY=VALUE`. Blank lines are fine. ([Node.js][2])
* **Comments** start with `#` at the beginning of a line *or* inline; if a `#` belongs in the value, **quote** the value. ([GitHub][3])
* **Whitespace** around keys, `=`, and values is ignored (outside of quotes). ([Node.js][2])
* **Quotes:** unquoted, `'single'`, or `"double"` all work. Quotes preserve spaces and `#`. ([Node.js][2])
* **`export` prefix** is generally tolerated/ignored so you can `source` the file in a shell. (Node’s built‑in, Ruby’s dotenv, and python‑dotenv accept it.) ([Node.js][2])

**Minimal, highly portable example**

```dotenv
# Server config
PORT=3000
LOG_LEVEL="info"
PASSWORD='pa#ss with spaces'
```

---

## Where implementations diverge (gotchas)

| Feature                            | Node **built‑in** (`--env-file`) | Node `dotenv` (npm)                            | `python-dotenv`       | Ruby `dotenv`                                       |
| ---------------------------------- | -------------------------------- | ---------------------------------------------- | --------------------- | --------------------------------------------------- |
| Inline `#` comments                | Yes                              | Yes (>= v15)                                   | Yes                   | Yes                                                 |
| Multiline values                   | **Yes, but only when quoted**    | **Yes** (>= v15, quoted or with `\n`)          | **Yes** (quoted)      | **Yes** (double‑quoted; `\n` legacy mode)           |
| Variable expansion (`${FOO}`)      | **No**                           | Not by default (use `dotenvx`/`dotenv-expand`) | **Yes** (POSIX‑style) | **Yes** (`$FOO`/`${FOO}` in unquoted/double‑quoted) |
| Command substitution (`$(whoami)`) | **No**                           | Not in core (use `dotenvx`)                    | **No**                | **Yes**                                             |
| `export KEY=...` accepted          | **Yes** (ignored)                | *Unspecified in README*                        | **Yes** (ignored)     | **Yes** (for shell compatibility)                   |

Citations: Node built‑in rules (names/values/spacing/comments/export), Node `dotenv` multiline/comments/expansion, python‑dotenv file‑format & expansion, Ruby dotenv multiline/expansion/exports. ([Node.js][2])

> Translation: if you depend on variable expansion or command substitution, portability goes out the window faster than your staging database when someone runs `DROP`. Stick to literal values unless you *know* which loader will parse them.

---

## Is there a *standard* spec?

* **Historically, no.** Even the people proposing one start with: “there is currently no specification… resulting in a multitude of different syntaxes.” ([GitHub][4])
* **Node.js now documents a spec for Node** (variable name regex, quoting, comments, `export`, etc.), but that’s not binding on Python/Ruby/Go parsers. ([Node.js][2])

The original ecosystem influence came via Foreman/Heroku using simple `KEY=VALUE` lines; everything else accreted in library land. ([ddollar.github.io][1])

---

## Practical rules I recommend (works almost everywhere)

1. **UPPER\_SNAKE\_CASE keys**, ASCII letters/digits/underscores; don’t quote keys. (Matches Node’s documented regex and most linters.) ([Node.js][2])
2. **Quote any value** that contains spaces, `#`, `=`, or leading/trailing whitespace. Use **double quotes** if you need escapes like `\n`. ([Node.js][2])
3. **Avoid interpolation** (`$FOO`) and **command substitution** (`$(...)`) unless you are standardizing on a loader that supports it (e.g., python‑dotenv or Ruby dotenv, or Node with `dotenvx`). ([GitHub][5])
4. **Keep files UTF‑8**; Node `dotenv` defaults to UTF‑8 and people trip over odd encodings. ([GitHub][3])
5. If you need **multiline secrets** (private keys), **always put them in quotes**; all major loaders above handle that. ([GitHub][3])
6. **Layered files** are implementation‑specific. Ruby’s dotenv defines precise precedence (e.g., `.env.development.local` > `.env.local` > `.env.development` > `.env`). Node’s `dotenv` lets you pass an array of files and control override. Decide your policy and stick to it. ([GitHub][6])
7. **Don’t commit real secrets.** Use `.env.example` for keys only. (Both Node and Ruby docs say to keep real `.env` out of VCS.) ([GitHub][3])

---

## TL;DR, in one canonical snippet

```dotenv
# .env (portable)
APP_ENV=production
PORT=8080
DB_URL="postgres://user:pass@db.internal:5432/app"
JWT_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----
MIIBVwIBADANBgkqhkiG9w0BAQEFAASCAT8w...
-----END PRIVATE KEY-----"
# If you must use '#', quote it:
PASSWORD='pa#ss with spaces'
```

If you want the stricter rulebook for Node specifically, use the official Node docs; if you’re writing cross‑language, stick to the safe subset above and don’t get clever. Clever breaks. Boring ships. ([Node.js][2])

---

**Confidence:** high on the “no universal spec” and the safe subset; medium on nuanced feature parity across every third‑party parser (they change). If you tell me which loader(s) you’re standardizing on, I’ll tighten the rules accordingly and flag any edge cases that still bite.

[1]: https://ddollar.github.io/foreman/?utm_source=chatgpt.com "foreman(1) - manage Procfile-based applications - David Dollar"
[2]: https://nodejs.org/api/environment_variables.html "Environment Variables | Node.js v24.8.0 Documentation"
[3]: https://github.com/motdotla/dotenv "GitHub - motdotla/dotenv: Loads environment variables from .env for nodejs projects."
[4]: https://github.com/php-xdg/dotenv-spec "GitHub - php-xdg/dotenv-spec: POSIX-compliant dotenv file format specification"
[5]: https://github.com/theskumar/python-dotenv "GitHub - theskumar/python-dotenv: Reads key-value pairs from a .env file and can set them as environment variables. It helps in developing applications following the 12-factor principles."
[6]: https://github.com/bkeepers/dotenv "GitHub - bkeepers/dotenv: A Ruby gem to load environment variables from `.env`."
