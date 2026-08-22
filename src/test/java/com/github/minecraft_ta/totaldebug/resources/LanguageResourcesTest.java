package com.github.minecraft_ta.totaldebug.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageResourcesTest {
    private static final String LANGUAGE_RESOURCE = "assets/total_debug/lang/en_us.json";
    private static final Set<String> CORE_KEYS = Set.of(
            "key.categories.total_debug",
            "key.total_debug.open_code_gui",
            "commands.total_debug.decompile.class.failed",
            "commands.total_debug.decompile.class.usage",
            "commands.total_debug.searchreference.progress",
            "companion_app.starting",
            "companion_app.connecting",
            "companion_app.connection_success",
            "companion_app.connection_fail",
            "companion_app.download_start",
            "companion_app.open_file",
            "companion_app.dumping_minecraft_classes",
            "companion_app.start_indexing"
    );

    @Test
    void coreLanguageFileIsValidAndComplete() throws Exception {
        var resource = LanguageResourcesTest.class.getClassLoader().getResourceAsStream(LANGUAGE_RESOURCE);
        assertNotNull(resource, () -> "Missing " + LANGUAGE_RESOURCE);

        JsonObject language;
        try (resource; var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            language = JsonParser.parseReader(reader).getAsJsonObject();
        }

        assertTrue(language.keySet().containsAll(CORE_KEYS));
        language.entrySet().forEach(entry -> {
            assertTrue(entry.getValue().isJsonPrimitive(), () -> entry.getKey() + " must be a JSON string");
            assertFalse(entry.getValue().getAsString().isBlank(), () -> entry.getKey() + " must not be blank");
        });
    }
}
