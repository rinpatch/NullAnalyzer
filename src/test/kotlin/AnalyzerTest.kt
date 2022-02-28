import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import Analyzer.Error
import java.nio.file.Path
import java.nio.file.Paths

internal class AnalyzerTest {
    @TempDir
    @JvmField
    val tempDir: Path? = null

    private fun assertNoError(methods: String) {
        val (_, errors) = analyzeMethods(methods)
        assertEquals(listOf<Error>(), errors)
    }

    private fun assertErrorOnLine(type: Error.Type, methods: String) {
        val (errorOnLine, errors) = analyzeMethods(methods)
        assertNotEquals(listOf<Error>(), errors)
        assertEquals(1, errors.size)
        val error = errors.first()
        println(errorOnLine)
        val line = error.causedBy.range.get().begin.line
        assertEquals(type, error.type)
        assertEquals(errorOnLine, line)
    }

    private fun analyzeMethods(methods: String): Pair<Int, List<Error>> {
        val code =
            """public class Test {
                   $methods
               }""".trimIndent()
        val errorOnLine = (code.split("\n").indexOfFirst { line -> line.contains("//errormark") }) + 2
        val codeFile = tempDir!!.resolve(Paths.get("test.java")).toFile()
        codeFile.writeText(code)
        val errors = Analyzer.parseAndAnalyze(codeFile)
        return errorOnLine to errors
    }

    @Test
    internal fun `Redundant not null check with a literal`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """void literal() {
                    String test = "";
                    //errormark
                    if (test != null) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant not null check with an enclosed literal`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """void literal() {
                    String test = ((""));
                    //errormark
                    if (test != null) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant not null check with an annotation`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """void literal(@NotNull String test) {
                    //errormark
                    if (test != null) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant not null check with a negated condition`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """void literal(@NotNull String test) {
                    //errormark
                    if (!(test == null)) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant null check with a negated variable check`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """void test(@NotNull String test2) {
                    String test = null;
                    //errormark
                    if (!(test != test2)) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant not null check with a double negated variable check`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """void test(@NotNull String test2, boolean bool1) {
                    String test = null;
                    //errormark
                    if (!(bool1 || !(test == test2))) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant not-null check with a negated variable check`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NULL_CHECK,
            """void test(@NotNull String test2) {
                    String test = null;
                    //errormark
                    if (!(test == test2)) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant nested not null check`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """String nestedRedundantNullCheck(@NotNull String test, bool cond1, bool cond2, bool cond3) {
       if (cond1) {
           if (cond2) {
               return "1";
           } else {
               return "2";
           }
       } else {
           if (cond3) {
               return "3";
           } else {
               //errormark
               if (test != null) {
                   return test;
               } else {
                   return "4";
               }
           }
       }
    }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant not null check in a complex if condition`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NOT_NULL_CHECK,
            """String wrappedComplexBinaryExpresison(@NotNull String test, boolean cond1, boolean cond2, boolean cond3) {
                        //errormark
                        if ((((cond1))) || (((cond2)) && ((((((((((cond3) || ((((((((((test != null))))))))))))))))))))) {
                            return test + "test success";
                        } else {
                            return "test failed";
                        }
                      }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant null check`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NULL_CHECK,
            """void literal(@NotNull String test) {
                    //errormark
                    if (test == null) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant null check through another variable`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NULL_CHECK,
            """void literal(@NotNull String test) {
                    String nullStr = null;
                    //errormark
                    if (test == nullStr) {
                        throw IllegalStateException("wtf");
                    }
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Redundant null check with assignment inside the if`() {
        assertErrorOnLine(
            Error.Type.REDUNDANT_NULL_CHECK, """
    public static int test (Point test) { 
        if (test != null) {
            //errormark
            if ((test = null) == null) {
                return test.x;
            }
        }
        return 0;
    }"""
        )

    }

    @Test
    internal fun `Calling a NotNull method with unknown argument type`() {
        assertErrorOnLine(
            Error.Type.FUNCTION_CALL_MAY_BE_NULL,
            """
                void foo(@NotNull String str) {
                }
                
                void test(String test) {
                    //errormark
                    foo(test);
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Calling a NotNull method with null argument type in assignment`() {
        assertErrorOnLine(
            Error.Type.FUNCTION_CALL_IS_NULL,
            """
                String foo(@NotNull String str) {
                    return str;
                }
                
                void test() {
                    String test = null;
                    //errormark
                    String test2 = foo(test);
                }""".trimIndent()
        )
    }

    @Test
    internal fun `Calling a method of a proven null object`() {
        assertErrorOnLine(
            Error.Type.FIELD_ACCESS_IS_NULL,
            """
                void test(String test) {
                    if (test == null) {
                        //errormark
                        int length = test.length(); 
                    }
                }""".trimIndent()
        )
    }

    @Test
    fun `May be null error when accessing a field with @Nullable argument`() {
        assertErrorOnLine(
            Error.Type.FIELD_ACCESS_MAY_BE_NULL,
            """
                void test(@Nullable Point test) {
                    //errormark
                    int xCoord = test.x;
                }""".trimIndent()
        )
    }

    @Test
    fun `Is null error when accessing a field of an object defined as null in the same declaration`() {
        assertErrorOnLine(
            Error.Type.FIELD_ACCESS_IS_NULL,
            """
                void test() {
                    //errormark
                    TreeNode test = null, test2 = test.getChildAt(0);
                }""".trimIndent()
        )
    }

    @Test
    fun `May be null error when accessing a field of a nullable in an if statement`() {
        assertErrorOnLine(
            Error.Type.FIELD_ACCESS_MAY_BE_NULL,
            """
                void test(@Nullable Point test) {
                    //errormark
                    if (test.x == 5) {
                    }
                }""".trimIndent()
        )
    }

    @Test
    fun `No error when accessing a field of a nullable in an if statement, if it is checked before`() {
        assertNoError(
            """
                void test(@Nullable Point test) {
                    if (test != null && test.x == 5) {
                    }
                }""".trimIndent()
        )
    }
    @Test
    fun `Field access may be null error, after comparing nullable to nullable`() {
        assertErrorOnLine(Error.Type.FIELD_ACCESS_MAY_BE_NULL,
            """
                void test(@Nullable Point test, @Nullable Point test2) {
                    if (test != test2) {
                        //errormark
                        int x = test.x;
                    }
                }""".trimIndent()
        )
    }

    @Test
    fun `Fields are assumed to be nullable`() {
        assertErrorOnLine(Error.Type.FUNCTION_CALL_MAY_BE_NULL,
            """
                void foo(@NotNull Point test) {
                }
                void test(Point test) {
                    if (test.rightNeighbour != null) {
                      foo(test.rightNeighbour);
                    }
                    //errormark
                    foo(test.rightNeighbour);
                }""".trimIndent()
        )
    }
    @Test
    fun `Else blocks have assumptions flipped`() {
        assertErrorOnLine(Error.Type.FIELD_ACCESS_IS_NULL,
            """
                void test(Point test) {
                    if (test != null) {
                    } else {
                        //errormark
                        Point test2 = test.rightNeighbour;
                    }
                }""".trimIndent()
        )
    }

    @Test
    fun `Switching on null produces an error`() {
        assertErrorOnLine(Error.Type.SWITCH_ON_NULL,
            """
                void test() {
                    Integer value = null;
                    //errormark
                    switch (value) {
                        default:
                        throw IllegalArgumentException("wtf");
                    }
                }""".trimIndent()
        )
    }
    @Test
    fun `Errors are detected inside switch statements`() {
        assertErrorOnLine(Error.Type.REDUNDANT_NULL_CHECK,
            """
                void test(@NotNull Point p) {
                    Integer value = 4;
                    switch (value) {
                        case 1:
                        break; 
                        default:
                        //errormark
                         if (p == null) {
                           throw IllegalArgumentException("p is null");
                         }
                         break;
                    }
                }""".trimIndent()
        )
    }
}