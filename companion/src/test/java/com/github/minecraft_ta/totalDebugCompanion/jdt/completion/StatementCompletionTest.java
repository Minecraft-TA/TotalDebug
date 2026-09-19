package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class StatementCompletionTest {
    static Stream<String[]> cases() {
        return Stream.of(
            pair("call(|); /* note\nmore */ next();", "call(); /* note\nmore */\n|next();"),
            pair("call(|); /* note */ next();", "call(); /* note */\n|next();"),
            pair("call(|) /* note\nmore */", "call(); /* note\nmore */\n|"),
            pair("call(|); /* one */ /* two\nthree */ // four\nnext();", "call(); /* one */ /* two\nthree */ // four\n|\nnext();"),
            pair("if (ready|) // why", "if (ready) { // why\n    |\n}"),
            pair("if (ready|) /* why\nmore */", "if (ready) { /* why\nmore */\n    |\n}"),
            pair("if (ready;\nother +|", "if (ready;\nother +|"),
            pair("while (ready; other +|)", "while (ready; other +|)"),
            pair("if (object.|)", "if (object.|) {\n    \n}"),
            pair("for (ItemStack stack : |)", "for (ItemStack stack : |) {\n    \n}"),
            pair("import java.util.*|", "import java.util.*;\n|"),
            pair("import static java.util.Collections.*;|", "import static java.util.Collections.*;\n|"),
            pair("import\njava.util.Li|st", "import\njava.util.List;\n|"),
            pair("if (x) { call(|); }", "if (x) { call();\n    |\n}"),
            pair("if (x) { call(|); next(); }", "if (x) { call();\n    |next(); }"),
            pair("int\ncount = 1|0", "int\ncount = 10;\n|"),
            pair("int[]\nvalues = new int[] {1, |2}", "int[]\nvalues = new int[] {1, 2};\n|"),
            pair("final\nString name = \"va|lue\"", "final\nString name = \"value\";\n|"),
            pair("// note\n|call()", "// note\ncall();\n|"),
            pair("/* note */ |call()", "/* note */ call();\n|"),
            pair("return|", "return|"),
            pair("import java.util.|", "import java.util.|"),
            pair("if (x) {\ncall(value|", "if (x) {\ncall(value);\n|"),
            pair("int x = ;|", "int x = |;"),
            pair("for (int i=0; i<10|)", "for (int i=0; i<10|)"),
            pair("call(\"\"\"\nhello {|world}\n\"\"\")", "call(\"\"\"\nhello {world}\n\"\"\");\n|"),
            pair("if (ready|) { /* keep */ }", "if (ready) {| /* keep */ }"),
            pair("a();|bbb()", "a();bbb();\n|"),
            pair("call(value|;", "call(value);\n|"),
            pair("call(nested(value|; next();", "call(nested(value));\n|next();"),
            pair("call(|", "call();\n|"),
            pair("|\ncall();", "|\ncall();"),
            pair("call(); |\nnext();", "call(); \n|\nnext();"),
            pair("if (ready) {|", "if (ready) {\n    |\n}"),
            pair("int a=1, b=|2", "int a=1, b=2;\n|"),
            pair("if (x) if (y|)", "if (x) if (y) {\n    |\n}"),
            pair("new java.util.ArrayList<String>(|)", "new java.util.ArrayList<String>();\n|"),
            pair("logln(\"hel|lo\")", "logln(\"hello\");\n|"),
            pair("logln(\"hello\"|", "logln(\"hello\");\n|"),
            pair("int count = 1|0", "int count = 10;\n|"),
            pair("return sta|ck", "return stack;\n|"),
            pair("call(|); // note", "call(); // note\n|"),
            pair("int count = |", "int count = |"),
            pair("call(value + |", "call(value + |"),
            pair("object.|", "object.|"),
            pair("call(a, |", "call(a, |"),
            pair("if|", "if (|) {\n    \n}"),
            pair("if (ready|)", "if (ready) {\n    |\n}"),
            pair("if (stack.isEmpty()|", "if (stack.isEmpty()) {\n    |\n}"),
            pair("while (|)", "while (|) {\n    \n}"),
            pair("for (;;|)", "for (;;) {\n    |\n}"),
            pair("for (int i = 0; i < 10; i++|)", "for (int i = 0; i < 10; i++) {\n    |\n}"),
            pair("for (ItemStack stack : stacks|)", "for (ItemStack stack : stacks) {\n    |\n}"),
            pair("if (ready|) {}", "if (ready) {\n    |\n}"),
            pair("if (ready|) {\n    call();\n}", "if (ready) {\n    |call();\n}"),
            pair("if (ready|) call();", "if (ready) |call();"),
            pair("if (ready) {\n    call(|)\n}", "if (ready) {\n    call();\n    |\n}"),
            pair("first(); seco|nd(); third();", "first(); second();\n|third();"),
            pair("call(\n    value,\n    oth|er\n)", "call(\n    value,\n    other\n);\n|"),
            pair("int[] values = {1, |2}", "int[] values = {1, 2};\n|"),
            pair("call(\"{ }\", |value)", "call(\"{ }\", value);\n|"),
            pair("call(\"hel|lo\")", "call(\"hello\");\n|"),
            pair("// comment|", "// comment|"),
            pair("/* comment | */ call();", "/* comment | */ call();"),
            pair("import java.util.Li|st", "import java.util.List;\n|"),
            pair("import java.util.List;\n\ncall(|)", "import java.util.List;\n\ncall();\n|"),
            pair("first()\nsecond(|)", "first()\nsecond();\n|"),
            pair("first(|)\nsecond();", "first();\n|\nsecond();"),
            pair("\tcall(|)\r\n", "\tcall();\r\n\t|"),
            pair("\n    |", "\n    |"),
            pair("if (|) {\n    \n}", "if (|) {\n    \n}"),
            pair("((EventBus) NeoForge.EVENT_BUS).listeners.clear(|)", "((EventBus) NeoForge.EVENT_BUS).listeners.clear();\n|"),
            pair("ItemStack stack = new ItemStack(Items.STO|NE)", "ItemStack stack = new ItemStack(Items.STONE);\n|"),
            pair("Runnable r = () -> { call(|); };", "Runnable r = () -> { call();\n    |\n};"),
            pair("if (x) {} else|", "if (x) {} else {\n    |\n}"),
            pair("if (ready|", "if (ready) {\n    |\n}"),
            pair("if (|", "if (|) {\n    \n}"),
            pair("if (ready|) {", "if (ready) {\n    |\n}"),
            pair("if (ready|) {\n    \n}", "if (ready) {\n    |\n}"),
            pair("if (ready && |)", "if (ready && |) {\n    \n}"),
            pair("if (ready) {\n    first();\n    second(|)\n}", "if (ready) {\n    first();\n    second();\n    |\n}"),
            pair("if (x) cal|l()", "if (x) call();\n|"),
            pair("for (int i=0; i<3; i++) call(|)", "for (int i=0; i<3; i++) call();\n|"),
            pair("switch (value) { case 1: call(|); }", "switch (value) { case 1: call();\n    |\n}"),
            pair("try { call(|); } catch (Exception ex) {}", "try { call();\n    |\n} catch (Exception ex) {}"),
            pair("call(\"unfinished|", "call(\"unfinished|"),
            pair("int[] values = new int[|", "int[] values = new int[|"),
            pair("call((int) array[0|", "call((int) array[0]);\n|"),
            pair("call(\n    nested(1, 2|", "call(\n    nested(1, 2));\n|"),
            pair("call(first|)\n    .next()", "call(first)\n    .next();\n|"),
            pair("if (ready|\nother();", "if (ready|\nother();"),
            pair("call(value|\nint other = 2;", "call(value|\nint other = 2;"),
            pair("broken.\ncall(|)", "broken.\ncall();\n|"),
            pair("call(|)\nbroken.", "call();\n|\nbroken."),
            pair("for (|)", "for (|) {\n    \n}"),
            pair("while|", "while (|) {\n    \n}"),
            pair("  if (ready|)", "  if (ready) {\n      |\n  }"),
            pair("if (x) {} else if (ready|)", "if (x) {} else if (ready) {\n    |\n}"),
            pair("call(|);\n\nnext();", "call();\n|\nnext();"),
            pair("import static java.util.Collections.emptyLi|st", "import static java.util.Collections.emptyList;\n|"),
            pair("throw new Exception(\"x\"|", "throw new Exception(\"x\");\n|"),
            pair("foo(() -> { return 1; }|)", "foo(() -> { return 1; });\n|"),
            pair("\tif (ready|)\r\n", "\tif (ready) {\r\n\t    |\r\n\t}\r\n")
        );
    }
    @Test void everyCaretOffsetProducesBoundedEditsWithoutRemovingUserCode() {
        for (String source : List.of("", "\n", "if", "if (x) { call(); } else { other(); }", "import java.util.List;\n\nint a = 3;\ncall(a)",
                "for (int i=0; i<3; i++) { logln(i); }", "call(a,\n b)\nnext();", "if (ready &&)", "// comment\ncall()", "if ((", "call(\"unfinished", "new int[]")) {
            for (int caret = 0; caret <= source.length(); caret++) {
                var plan = StatementCompletion.plan(source, caret, "\t");
                int previous = 0, length = source.length();
                for (var edit : plan.edits()) {
                    assertTrue(edit.offset() >= previous, source + " at " + caret);
                    assertTrue(edit.offset() + edit.length() <= source.length());
                    assertTrue(source.substring(edit.offset(), edit.offset() + edit.length()).isBlank());
                    length += edit.text().length() - edit.length();
                    previous = edit.offset();
                }
                assertTrue(plan.caret() >= 0 && plan.caret() <= length, source + " at " + caret);
            }
        }
    }

    private static String[] pair(String before, String after) { return new String[]{before, after}; }

    @ParameterizedTest @MethodSource("cases")
    void completesOnlyTheContainingStatement(String before, String expected) {
        int caret = before.indexOf('|');
        String source = before.substring(0, caret) + before.substring(caret + 1);
        var plan = StatementCompletion.plan(source, caret, "    ");
        var text = new StringBuilder(source);
        // Later inserts at the same original offset follow earlier inserts.
        for (int i = plan.edits().size() - 1; i >= 0; i--) {
            var edit = plan.edits().get(i);
            text.replace(edit.offset(), edit.offset() + edit.length(), edit.text());
        }
        text.insert(plan.caret(), '|');
        assertEquals(expected, text.toString(), before);
    }
}
