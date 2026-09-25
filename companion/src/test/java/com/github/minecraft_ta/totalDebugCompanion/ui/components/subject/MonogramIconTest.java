package com.github.minecraft_ta.totalDebugCompanion.ui.components.subject;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MonogramIconTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CBMicroblock | CB
            Sodium | S
            Mekanism Generators | MG
            code_chicken_lib | CC
            iris | I
            """)
    void takesInitialsFromWordsOrCapitals(String name, String initials) {
        assertEquals(initials, MonogramIcon.initials(name));
    }
}
