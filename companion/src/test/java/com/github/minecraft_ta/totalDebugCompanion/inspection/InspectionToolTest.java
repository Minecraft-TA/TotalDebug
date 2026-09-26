package com.github.minecraft_ta.totalDebugCompanion.inspection;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.script.ScriptFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionToolTest {
    @Test
    void readsPatternsOnlyFromLeadingCommentLines() {
        assertEquals(List.of("mekanism:*", "minecraft:furnace", "create:*_tank"), InspectionTool.patterns("""

                // Reads machine side configuration.
                // inspect: mekanism:*, minecraft:furnace
                //inspect:create:*_tank
                import java.util.List;
                // inspect: ignored:after_code
                return null;
                """));
    }

    @Test
    void matchesRegistryIdsWithGlobs() {
        InspectionTool tool = new InspectionTool(Path.of("Tool.tdscript"), "Tool", "",
                List.of("mekanism:*", "create:*_tank", "minecraft:furnace"));

        assertTrue(tool.appliesTo("mekanism:basic_energy_cube"));
        assertTrue(tool.appliesTo("create:fluid_tank"));
        assertTrue(tool.appliesTo("MINECRAFT:FURNACE"));
        assertFalse(tool.appliesTo("minecraft:blast_furnace"));
        assertFalse(tool.appliesTo("create:fluid_tank_controller"));
    }

    @Test
    void loadsScriptsWithTheirPatternsAndSkipsInvalidClassNames(@TempDir Path directory) throws Exception {
        ScriptFiles files = new ScriptFiles(directory);
        Path folder = files.create(directory, InspectionTool.FOLDER, true, "");
        files.create(folder, "FurnaceTool", false, InspectionTool.template("minecraft:furnace", "Furnace"));
        files.create(directory, "Scratch", false, "return 1;");
        Files.writeString(directory.resolve("not valid.tdscript"), "return 2;");

        List<InspectionTool> tools = InspectionTool.load(files);

        assertEquals(List.of("Scratch", "FurnaceTool"), tools.stream().map(InspectionTool::name).toList());
        assertFalse(tools.getFirst().appliesTo("minecraft:furnace"));
        assertTrue(tools.get(1).appliesTo("minecraft:furnace"));
    }

    @Test
    void templateBecomesASnippetWithItsImport() {
        String source = JavaSnippetSource.body("FurnaceTool", InspectionTool.template("minecraft:furnace", "Furnace"))
                .source();

        assertTrue(source.contains("import com.github.minecraft_ta.totaldebug.script.ScriptTarget;"), source);
        assertTrue(source.contains("class FurnaceTool"), source);
    }
}
