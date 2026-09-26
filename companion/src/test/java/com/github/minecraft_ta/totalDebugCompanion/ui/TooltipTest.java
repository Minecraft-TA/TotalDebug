package com.github.minecraft_ta.totalDebugCompanion.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipTest {
    @Test
    void anEmptyTooltipShowsNothing() {
        assertNull(Tooltip.of("").detail(" ").text(null).code("").html());
    }

    @Test
    void shortTooltipsStayNarrowAndLongTextWraps() {
        assertFalse(Tooltip.of("Read again").html().contains("width:"));
        assertTrue(Tooltip.of("").text("x".repeat(100)).html().contains("width:"));
    }

    @Test
    void escapesTextAndColorsFactValues() {
        String html = Tooltip.of("List<String> & more").fact("Default", "4", new Color(0x2AACB8)).html();

        assertTrue(html.contains("List&lt;String&gt; &amp; more"), html);
        assertTrue(html.contains("Default <font color='#2AACB8'>4</font>"), html);
    }

    @Test
    void codeIsCutToWhatATooltipCanShow() {
        String html = Tooltip.of("").code("line\n".repeat(60)).html();

        assertTrue(html.contains("… 20 more lines"), html);
    }

    @Test
    void longPathsKeepTheirLastThreeParts() {
        Path path = Path.of("C:", "Users", "Admin", "AppData", "Roaming", "PrismLauncher", "instances", "Pack", "minecraft");

        assertEquals("…" + path.getFileSystem().getSeparator() + Path.of("instances", "Pack", "minecraft"),
                Tooltip.shortPath(path));
        assertEquals(Path.of("a", "b").toString(), Tooltip.shortPath(Path.of("a", "b")));
    }
}
