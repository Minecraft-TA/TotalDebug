package com.github.minecraft_ta.totalDebugCompanion.ui.categories;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.ui.categories.logs.LogsPage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PagesTest {
    @Test
    void everyCategoryTargetHasItsPage() throws Exception {
        Pages pages = new Pages();
        for (Class<?> target : NavigationTarget.CategoryTarget.class.getPermittedSubclasses()) {
            NavigationTarget.CategoryTarget example = example(target);
            assertSame(target, pages.of(example).target(), target.getSimpleName() + " is opened by its own page");
        }
    }

    @Test
    void twoPagesForOneTargetAreRefused() {
        assertThrows(IllegalStateException.class, () -> new Pages(List.of(new LogsPage(), new LogsPage())));
    }

    /** An instance of a target record, made with its canonical constructor and harmless values. */
    private static NavigationTarget.CategoryTarget example(Class<?> target) throws Exception {
        var components = target.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            types[index] = components[index].getType();
            values[index] = value(types[index]);
        }
        return (NavigationTarget.CategoryTarget) target.getDeclaredConstructor(types).newInstance(values);
    }

    private static Object value(Class<?> type) {
        if (type == String.class) return "example";
        if (type == Path.class) return Path.of("example");
        if (type.isEnum()) return type.getEnumConstants()[0];
        throw new IllegalArgumentException("No example value for " + type);
    }
}
