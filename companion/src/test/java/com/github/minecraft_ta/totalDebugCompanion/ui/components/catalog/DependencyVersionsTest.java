package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DependencyVersionsTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            [1.21.1,1.21.1] | 1.21.1
            [1.21.1] | 1.21.1
            [21.1.194,) | 21.1.194 or newer
            [19.19.0.219,) | 19.19.0.219 or newer
            [,1.6.0-alpha.2] | 1.6.0-alpha.2 or older
            (,1.6) | Earlier than 1.6
            (1.6,) | Later than 1.6
            [1,2] | 1 through 2
            [1,2) | At least 1, before 2
            (1,2] | Later than 1, up to 2
            (1,2) | Later than 1, before 2
            (,1],[2,) | 1 or older or 2 or newer
            (,) | Any version
            1.2 | 1.2 preferred
            [invalid | [invalid
            """)
    void preservesVersionBoundsInReadableText(String input, String expected) {
        assertEquals(expected, DependencyVersions.describe(input));
    }
}
