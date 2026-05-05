// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.dotenv.parser

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Assumes the production code provides:
//   data class DotenvEntry(val key: String, val value: String, val line: Int)
//   data class DotenvParseOptions(...)
//   fun parseDotenvText(text: String, options: DotenvParseOptions = DotenvParseOptions()):
// List<DotenvEntry>

private fun List<DotenvEntry>.toMap(): Map<String, String> = this.associate { it.key to it.value }

private fun normalizeNewlines(s: String) = s.replace("\r\n", "\n").replace("\r", "\n")

private fun tempFileWith(bytes: ByteArray, suffix: String = ".env"): File {
    val f = Files.createTempFile("dotenv_test_", suffix).toFile()
    f.deleteOnExit()
    f.writeBytes(bytes)
    return f
}

class DotenvCoreParsingTest {
    @Test
    fun portableExample_parses() {
        val text =
            """
            # .env (portable)
            APP_ENV=production
            PORT=8080
            DB_URL="postgres://user:pass@db.internal:5432/app"
            JWT_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----
            MIIBVwIBADANBgkqhkiG9w0BAQEFAASCAT8w...
            -----END PRIVATE KEY-----"
            # If you must use '#', quote it:
            PASSWORD='pa#ss with spaces'
            """
                .trimIndent()

        val m = parseDotenvText(text).toMap()

        assertEquals("production", m["APP_ENV"])
        assertEquals("8080", m["PORT"])
        assertEquals("postgres://user:pass@db.internal:5432/app", m["DB_URL"])

        val expectedKey =
            "-----BEGIN PRIVATE KEY-----\nMIIBVwIBADANBgkqhkiG9w0BAQEFAASCAT8w...\n-----END PRIVATE KEY-----"
        assertEquals(expectedKey, normalizeNewlines(m["JWT_PRIVATE_KEY"] ?: ""))

        assertEquals("pa#ss with spaces", m["PASSWORD"])
    }

    @Test
    fun whitespace_export_trailingComments_unquotedHashes() {
        val text =
            """
              export   A   =   1     # comment
            B=2#hash-is-part-of-value
            C=   # empty with trailing
            D = "x"   # trailing after quoted
            KEY.DASH-N-DOT = value
            E=hello\ world   # escaped space remains
            """
                .trimIndent()

        val m = parseDotenvText(text).toMap()

        assertEquals("1", m["A"])
        assertEquals("2#hash-is-part-of-value", m["B"])
        assertEquals("", m["C"])
        assertEquals("x", m["D"])
        assertEquals("value", m["KEY.DASH-N-DOT"])
        assertEquals("hello world", m["E"])
    }

    @Test
    fun multilineQuoted_single_and_double() {
        val text =
            """
            S='line1
            line2
            line3'
            D="lineA
            lineB
            lineC"
            """
                .trimIndent()

        val m = parseDotenvText(text).toMap()
        assertEquals("line1\nline2\nline3", normalizeNewlines(m["S"] ?: ""))
        assertEquals("lineA\nlineB\nlineC", normalizeNewlines(m["D"] ?: ""))
    }

    @Test
    fun escapes_and_literal_dollar_in_double_and_unquoted() {
        val text =
            """
            A="a\nb\tc\"\${'$'}HOME"
            U=\${'$'}HOME\ \#\#   # becomes "${'$'}HOME ##"
            """
                .trimIndent()

        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
                )
                .toMap()
        assertEquals("a\nb\tc\"\$HOME", normalizeNewlines(m["A"] ?: ""))
        assertEquals("\$HOME ##", m["U"])
    }

    @Test
    fun lastLineWithoutNewline_isParsed() {
        val text = "A=1\nB=2\nC=3" // no trailing newline
        val m = parseDotenvText(text).toMap()
        assertEquals(mapOf("A" to "1", "B" to "2", "C" to "3"), m)
    }
}

class DotenvExpansionTest {
    @Test
    fun basic_and_trailingExpansion() {
        val text =
            """
            A=42
            B=$${"A"}
            C=${'$'}{A}x
            EMPTY=
            D=${'$'}{MISSING:-def}
            E=${'$'}{EMPTY:-def}
            F=${'$'}{EMPTY-def}
            G=${'$'}{A:+yes}
            H=${'$'}{EMPTY:+yes}
            I=${'$'}{EMPTY+yes}
            """
                .trimIndent()

        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
                )
                .toMap()

        assertEquals("42", m["A"])
        assertEquals("42", m["B"])
        assertEquals("42x", m["C"])
        assertEquals("def", m["D"])
        assertEquals("def", m["E"])
        assertEquals(
            "",
            m["F"],
        ) // '-' without colon → only unset triggers default; EMPTY is set → no default
        assertEquals("yes", m["G"])
        assertEquals("", m["H"]) // ':+': EMPTY is set but empty → treated as unset, so no alt
        assertEquals("yes", m["I"]) // '+': no colon → EMPTY is set, so alt is used
    }

    @Test
    fun assignment_operators_and_statefulness() {
        val text =
            """
            X=${'$'}{X:=foo}
            Y=${'$'}{X}
            X=
            Z=${'$'}{X=bar}
            W=${'$'}{X}
            """
                .trimIndent()

        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
                )
                .toMap()

        // Last write wins; 'X=' overwrote the earlier "foo"
        assertEquals("", m["X"])

        // Y was computed before 'X='; it should still see "foo"
        assertEquals("foo", m["Y"])

        // ${X=bar} (no colon) does NOT assign because X is set (even if empty)
        assertEquals("", m["Z"])
        assertEquals("", m["W"])
    }

    @Test
    fun nested_defaults_and_stateful_order() {
        val text =
            """
            OUT=${'$'}{B:-${'$'}{C:-z}}
            A=1
            B=${'$'}{A}2
            A=3
            C=${'$'}{B}${'$'}{A}
            """
                .trimIndent()

        val opts = DotenvParseOptions(expandVariables = true, allowSystemEnv = false)
        val m = parseDotenvText(text, opts).toMap()

        assertEquals("z", m["OUT"]) // computed before B/C are set
        assertEquals("12", m["B"])
        assertEquals("3", m["A"])
        assertEquals("123", m["C"])
    }

    @Test
    fun question_operator_errors() {
        val text1 = "A=${'$'}{MISSING:?boom}"
        val ex1 =
            assertFailsWith<DotenvParseException.ParameterNotSet> {
                parseDotenvText(
                    text1,
                    DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
                )
            }
        assertTrue(ex1.message?.contains("boom") == true)

        val text2 = "A=${'$'}{NOPE?}"
        val ex2 =
            assertFailsWith<DotenvParseException.ParameterNotSet> {
                parseDotenvText(
                    text2,
                    DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
                )
            }
        assertTrue(ex2.message?.contains("Parameter 'NOPE' is not set") == true)
    }

    @Test
    fun expansion_depth_limit() {
        // 16 nested expansions → should exceed depth limit (<16 allowed)
        val deep = buildString {
            append("A=")
            repeat(16) { append("\${A:-") }
            append("end")
            repeat(16) { append("}") }
        }
        assertFailsWith<DotenvParseException.ExpansionTooDeep> {
            parseDotenvText(
                deep,
                DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
            )
        }
    }
}

class DotenvCommandSubstitutionTest {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun dollarParen_disabled_isLiteral() {
        val text = """A=$(echo hi)"""
        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(commandSubstitution = false, expandVariables = false),
                )
                .toMap()
        assertEquals("\$(echo hi)", m["A"]) // literal
    }

    @Test
    fun backticks_disabled_areLiteral() {
        val text = """A=`echo 12`"""
        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(commandSubstitution = false, expandVariables = false),
                )
                .toMap()
        assertEquals("`echo 12`", m["A"]) // literal
    }

    @Test
    fun dollarParen_enabled_executes() {
        val text = """A=$(echo hello)"""
        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(commandSubstitution = true, expandVariables = false),
                )
                .toMap()
        assertEquals("hello", m["A"])
    }

    @Test
    fun backticks_enabled_executes_when_allowed() {
        val text = """A=`echo 123`"""
        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(
                        commandSubstitution = true,
                        expandVariables = false,
                        forbidBackticks = false,
                    ),
                )
                .toMap()
        assertEquals("123", m["A"])
    }

    @Test
    fun backticks_forbidden_by_default_throw() {
        val text = """A=`echo nope`"""
        val ex =
            assertFailsWith<DotenvParseException.BackticksForbidden> {
                parseDotenvText(
                    text,
                    DotenvParseOptions(commandSubstitution = true, expandVariables = false),
                )
            }
        assertTrue(ex.message?.contains("Backticks are forbidden") == true)
    }

    @Test
    fun command_output_is_not_reexpanded() {
        val text =
            if (isWindows) {
                // cmd will just echo literal '$BAR'
                "BAR=42\nA=\$(echo \$BAR)"
            } else {
                // printf outputs '$BAR' which must remain literal (no re-expansion)
                "BAR=42\nA=\$(printf '\$BAR')"
            }
        val m =
            parseDotenvText(
                    text,
                    DotenvParseOptions(
                        commandSubstitution = true,
                        expandVariables = true,
                        allowSystemEnv = false,
                    ),
                )
                .toMap()
        assertEquals("\$BAR", m["A"]) // critical: no second expansion pass on command output
    }

    @Test
    fun max_commands_per_value_is_enforced() {
        val text = "A=\$(echo 1)\$(echo 2)\$(echo 3)\$(echo 4)\$(echo 5)\$(echo 6)"
        val ex =
            assertFailsWith<DotenvParseException.TooManyCommandSubstitutions> {
                parseDotenvText(
                    text,
                    DotenvParseOptions(
                        commandSubstitution = true,
                        expandVariables = false,
                        maxCommandsPerValue = 5,
                    ),
                )
            }
        assertTrue(ex.message?.contains("Too many command substitutions") == true)
    }
}

class DotenvLineEndingsAndEncodingTest {
    @Test
    fun different_line_endings() {
        val lf = "A=1\nB=\"x\nY\"\nC=3\n"
        val crlf = lf.replace("\n", "\r\n")
        val cr = lf.replace("\n", "\r")

        val m1 = parseDotenvText(lf).toMap()
        val m2 = parseDotenvText(crlf).toMap()
        val m3 = parseDotenvText(cr).toMap()

        assertEquals("1", m1["A"])
        assertEquals("1", m2["A"])
        assertEquals("1", m3["A"])
        assertEquals("3", m1["C"])
        assertEquals("3", m2["C"])
        assertEquals("3", m3["C"])
        assertEquals("x\nY", normalizeNewlines(m1["B"] ?: ""))
        assertEquals("x\nY", normalizeNewlines(m2["B"] ?: ""))
        assertEquals("x\nY", normalizeNewlines(m3["B"] ?: ""))
    }
}

class DotenvErrorTest {
    @Test
    fun missing_equals_throws() {
        val ex = assertFailsWith<DotenvParseException.SyntaxError> { parseDotenvText("A 1") }
        assertTrue(ex.message?.contains("Expected '=' after key 'A'") == true)
    }

    @Test
    fun unterminated_single_quote_throws_with_line() {
        val ex =
            assertFailsWith<DotenvParseException.UnterminatedSingleQuoted> {
                parseDotenvText("A='abc")
            }
        assertTrue(ex.message?.contains("Unterminated single-quoted value") == true)
        assertTrue(ex.message?.contains("Line 1") == true)
    }

    @Test
    fun unterminated_double_quote_throws_with_line() {
        val ex =
            assertFailsWith<DotenvParseException.UnterminatedDoubleQuoted> {
                parseDotenvText("A=\"abc")
            }
        assertTrue(ex.message?.contains("Unterminated double-quoted value") == true)
        assertTrue(ex.message?.contains("Line 1") == true)
    }

    @Test
    fun unclosed_parameter_expansion_throws_when_expansion_enabled() {
        val ex =
            assertFailsWith<DotenvParseException.SyntaxError> {
                parseDotenvText(
                    "A=\${FOO",
                    DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
                )
            }
        assertTrue(ex.message?.contains("Unclosed \${") == true)
    }

    @Test
    fun bad_parameter_name_in_expansion() {
        val ex =
            assertFailsWith<DotenvParseException.BadParameterName> {
                parseDotenvText(
                    "A=\${:bad}",
                    DotenvParseOptions(expandVariables = true, allowSystemEnv = false),
                )
            }
        assertTrue(ex.message?.contains("Bad parameter name") == true)
    }
}
