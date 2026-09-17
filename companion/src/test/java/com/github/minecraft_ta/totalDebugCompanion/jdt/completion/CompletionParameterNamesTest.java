package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.fixture.ParameterNamesFixture;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import com.github.tth05.jindex.ClassIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.*;

class CompletionParameterNamesTest {
    @BeforeAll static void index() throws Exception {
        List<byte[]> classes = new ArrayList<>();
        for (Class<?> type : List.of(Object.class, String.class, ParameterNamesFixture.class, ParameterNamesFixture.Inner.class,
                ParameterNamesFixture.GenericInner.class,
                BonemealableBlock.class, ServerLevel.class, RandomSource.class,
                BlockPos.class, BlockState.class)) {
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                classes.add(input.readAllBytes());
            }
        }
        CompanionClassIndex.set(ClassIndex.fromBytes(classes));
    }

    @AfterAll static void close() { CompanionClassIndex.get().close(); CompanionClassIndex.clear(); }

    @Test void usesRealNamesWithGenericSubstitutionAndWideSlots() throws Exception {
        assertCompletion("fixture.conver|", "convert(String inputValue) : String", "convert(${1:inputValue})${0}");
        assertCompletion("fixture.wid|", "wide(long ticks, double ratio, String label) : void",
                "wide(${1:ticks}, ${2:ratio}, ${3:label});${0}");
        assertCompletion("fixture.vararg|", "varargs(String... entries) : void", "varargs(${1:entries});${0}");
    }

    @Test void sharesFallbackAndParchmentNamesBetweenLabelAndInsertion() throws Exception {
        assertCompletion("fixture.generate|", "generated(String s1, int i, String s) : void",
                "generated(${1:s1}, ${2:i}, ${3:s});${0}");
        assertCompletion("block.performBoneme|", "performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) : void",
                "performBonemeal(${1:level}, ${2:random}, ${3:pos}, ${4:state});${0}");
    }

    @Test void keepsSourceNamesEvenWhenTheyLookGenerated() throws Exception {
        assertCompletion("sourceMet|", "sourceMethod(int arg0, String userText) : void",
                "sourceMethod(${1:arg0}, ${2:userText});${0}");
    }

    @Test void insertsUnicodeAndDollarNamesAsEditableArguments() throws Exception {
        assertCompletion("fixture.unico|", "unicode(String 名前, int $count) : void", "unicode(${1:名前}, ${2:$count});${0}");
        SwingUtilities.invokeAndWait(() -> {
            var text = new JTextArea();
            new SnippetCompletionAdapter(text).insert(new CustomTextEdit(new Range(0, 0), "unicode(${1:名前}, ${2:$count});${0}"));
            assertEquals("unicode(名前, $count);", text.getText());
            assertEquals("名前", text.getSelectedText());
        });
    }

    @Test void constructorHidesSyntheticOuterParameter() throws Exception {
        assertCompletion("fixture.new Inn|", "Inner(String title)", "Inner(${1:title})${0}");
        assertCompletion("fixture.new GenericInn|", "GenericInner(String content)", "GenericInner(${1:content})${0}");
        assertCompletion("new ParameterNamesFixt|", "ParameterNamesFixture(T initialValue)",
                "ParameterNamesFixture<>(${1:initialValue})${0}");
    }

    @Test void lambdaParametersAvoidEnclosingParametersAndLocals() throws Exception {
        assertSourceCompletion("interface Fn { int apply(int value, int value1); } "
                        + "class Proof { void run(int value) { int value2 = 0; Fn fn = |; } }",
                "(int value3, int value1) -> : int", "(${1:value3}, ${2:value1}) -> ${0}");
        assertSourceCompletion("interface Fn { int apply(int fn); } class Proof { void run() { Fn fn = |; } }",
                "(int fn1) -> : int", "(${1:fn1}) -> ${0}");
    }

    @Test void sourceInitializerPreviewPreservesLiteralWhitespace() throws Exception {
        assertSourceCompletion("class Proof { static final String VALUE = \"a  b\"; void run() { VAL|; } }",
                "VALUE (= \"a  b\") : String", "VALUE");
        assertSourceCompletion("class Proof { static final String FIRST = \"first\", VALUE = \"second\"; void run() { VAL|; } }",
                "VALUE (= \"second\") : String", "VALUE");
        assertSourceCompletion("class Proof { static final Object VALUE = new Object(); void run() { VAL|; } }",
                "VALUE (= new Object()) : Object", "VALUE");
    }

    private static void assertCompletion(String expression, String label, String insertion) throws Exception {
        String source = "import " + ParameterNamesFixture.class.getName() + ";\n"
                + "import " + BonemealableBlock.class.getName() + ";\n"
                + "class Proof { ParameterNamesFixture<String> fixture; BonemealableBlock block; "
                + "void sourceMethod(int arg0, String userText) {} void run() { " + expression + " } }";
        assertSourceCompletion(source, label, insertion);
    }

    private static void assertSourceCompletion(String source, String label, String insertion) throws Exception {
        int caret = source.indexOf('|');
        var unit = new CompilationUnitImpl("Proof", source.replace("|", ""));
        var result = new CompletableFuture<List<CompletionItem>>();
        var requestor = new CustomCompletionRequestor(unit, caret, (ignored, items) -> result.complete(items));
        unit.codeComplete(caret, requestor, requestor);
        var item = result.join().stream().filter(candidate -> label.equals(candidate.getLabel())).findFirst()
                .orElseThrow(() -> new AssertionError(result.join().stream().map(CompletionItem::getLabel).toList()));
        assertEquals(insertion, item.getTextEdits().getFirst().getNewText());
    }
}
