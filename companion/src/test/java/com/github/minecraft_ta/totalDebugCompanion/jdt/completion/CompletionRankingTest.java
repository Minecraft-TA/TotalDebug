package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Flags;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompletionRankingTest {
    @Test void minecraftBreaksTypeTiesButDoesNotOverrideSemanticRelevance() {
        var minecraft = type("net.minecraft.world.item.ItemStack", 100);
        var mod = type("a.random.mod.ItemStack", 100);
        assertEquals(minecraft, CompletionRanking.order(List.of(mod, minecraft), "ItemStack").getFirst());
        mod.setRelevance(120); // JDT's import/expected-type context wins.
        assertEquals(mod, CompletionRanking.order(List.of(mod, minecraft), "ItemStack").getFirst());
        assertEquals(1, CompletionRanking.order(List.of(mod, mod), "ItemStack").size());
    }

    @Test void visibilityOnlyBreaksOtherwiseEqualMemberMatches() {
        var privateField = field("value", Flags.AccPrivate, 100);
        var publicField = field("values", Flags.AccPublic, 100);
        assertEquals(privateField, CompletionRanking.order(List.of(publicField, privateField), "value").getFirst());
        publicField = field("value", Flags.AccPublic, 100);
        assertEquals(publicField, CompletionRanking.order(List.of(privateField, publicField), "value").getFirst());
    }

    @Test void overridesOnlyTheGenericJavaBonusForAmbiguousMinecraftTypes() {
        var minecraft = type("net.minecraft.world.level.Level", 49);
        var java = type("java.util.logging.Level", 51);
        assertEquals(minecraft, CompletionRanking.order(List.of(java, minecraft), "Lev").getFirst());
        java.setRelevance(54); // An explicit import is a stronger signal.
        assertEquals(java, CompletionRanking.order(List.of(java, minecraft), "Lev").getFirst());
        java = type("java.util.ArrayList", 51);
        minecraft = type("net.minecraft.util.ArrayListDeque", 49);
        assertEquals(java, CompletionRanking.order(List.of(minecraft, java), "ArrayLi").getFirst());
    }

    @Test void onlyExactTemplateTriggersGetPriority() {
        var template = new CompletionItem(null);
        template.setPresentation("sout", "", "");
        template.setKind(CompletionItemKind.KEYWORD);
        var member = field("sout", Flags.AccPublic, 100);
        assertEquals(template, CompletionRanking.order(List.of(member, template), "sout").getFirst());
        assertEquals(member, CompletionRanking.order(List.of(member, template), "so").getFirst());
    }

    private static CompletionItem type(String type, int score) {
        var proposal = CompletionProposal.create(CompletionProposal.TYPE_REF, 0);
        proposal.setSignature(("L" + type + ";").toCharArray());
        proposal.setRelevance(score);
        var item = new CompletionItem(null, proposal);
        item.setKind(CompletionItemKind.CLASS);
        return item;
    }

    private static CompletionItem field(String name, int flags, int score) {
        var proposal = CompletionProposal.create(CompletionProposal.FIELD_REF, 0);
        proposal.setName(name.toCharArray());
        proposal.setSignature("I".toCharArray());
        proposal.setFlags(flags);
        proposal.setRelevance(score);
        var item = new CompletionItem(null, proposal);
        item.setKind(CompletionItemKind.FIELD);
        return item;
    }
}
