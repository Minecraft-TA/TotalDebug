package com.github.minecraft_ta.totaldebug.resource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigValuesTest {
    @Test
    void numbersAndNamesTakeTheTypesTheSettingHolds() {
        assertEquals(12, ConfigValues.convert(12L, 5, null));
        assertEquals(TimeUnit.SECONDS, ConfigValues.convert("SECONDS", TimeUnit.MINUTES, null));
    }

    @Test
    void listElementsTakeTheTypeOfTheSettingsElements() {
        // TOML reads [1, 2] as longs; a List<Integer> setting must receive integers.
        assertEquals(List.of(1, 2), ConfigValues.convert(List.of(1L, 2L), List.of(5), null));
        assertEquals(List.of(TimeUnit.SECONDS), ConfigValues.convert(List.of("SECONDS"), List.of(TimeUnit.MINUTES), null));
        assertEquals(List.of(1, 2), ConfigValues.convert(List.of(1L, 2L), List.of(), List.of(7)),
                "an empty default leaves the current value's elements to go by");
        assertEquals(List.of(1L), ConfigValues.convert(List.of(1L), List.of(), List.of()), "nothing to go by keeps the parsed value");
    }
}
