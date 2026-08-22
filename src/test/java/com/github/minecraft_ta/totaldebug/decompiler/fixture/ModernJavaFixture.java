package com.github.minecraft_ta.totaldebug.decompiler.fixture;

import java.util.List;

public sealed interface ModernJavaFixture<T extends Number>
        permits ModernJavaFixture.Value, ModernJavaFixture.Empty {
    default int inspect(Object value) {
        return switch (value) {
            case null -> -1;
            case String text when !text.isBlank() -> text.length();
            case Integer number -> number;
            default -> 0;
        };
    }

    default List<String> stringify(List<? extends T> values) {
        return values.stream()
                .map(value -> value.getClass().getSimpleName() + ':' + value)
                .toList();
    }

    record Value<T extends Number>(T value) implements ModernJavaFixture<T> {
    }

    final class Empty<T extends Number> implements ModernJavaFixture<T> {
        public final class Nested {
            public boolean isEmpty() {
                return true;
            }
        }
    }
}
