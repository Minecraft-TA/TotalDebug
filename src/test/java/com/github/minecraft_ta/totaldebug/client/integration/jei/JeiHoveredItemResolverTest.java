package com.github.minecraft_ta.totaldebug.client.integration.jei;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IScreenHelper;
import net.minecraft.client.renderer.Rect2i;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiHoveredItemResolverTest {
    @Test
    void ingredientListTakesPriorityOverBookmarksAndScreenHandlers() {
        AtomicBoolean bookmarkQueried = new AtomicBoolean();
        AtomicBoolean screenQueried = new AtomicBoolean();
        IJeiRuntime runtime = runtime(
                () -> Optional.of(ingredient("list")),
                () -> {
                    bookmarkQueried.set(true);
                    return Optional.of(ingredient("bookmark"));
                },
                () -> {
                    screenQueried.set(true);
                    return Stream.of(clickable("screen"));
                }
        );

        Optional<String> result = resolve(runtime, Function.identity());

        assertEquals("list", result.orElseThrow());
        assertFalse(bookmarkQueried.get());
        assertFalse(screenQueried.get());
    }

    @Test
    void rejectedIngredientListValueFallsThroughToBookmarks() {
        IJeiRuntime runtime = runtime(
                () -> Optional.of(ingredient("unsupported")),
                () -> Optional.of(ingredient("bookmark")),
                Stream::empty
        );

        Optional<String> result = resolve(
                runtime,
                value -> value.equals("unsupported") ? null : value
        );

        assertEquals("bookmark", result.orElseThrow());
    }

    @Test
    void screenHelperSuppliesRecipeAndPluginIngredients() {
        AtomicBoolean screenQueried = new AtomicBoolean();
        IJeiRuntime runtime = runtime(
                Optional::empty,
                Optional::empty,
                () -> {
                    screenQueried.set(true);
                    return Stream.of(clickable("recipe"));
                }
        );

        Optional<String> result = resolve(runtime, Function.identity());

        assertEquals("recipe", result.orElseThrow());
        assertTrue(screenQueried.get());
    }

    @Test
    void pluginUsesAStableTotalDebugIdentifier() {
        var plugin = new TotalDebugJeiPlugin();

        assertEquals("total_debug:integration", plugin.getPluginUid().toString());
    }

    private static Optional<String> resolve(IJeiRuntime runtime, Function<String, String> converter) {
        return JeiHoveredItemResolver.resolveIngredient(
                runtime,
                null,
                12,
                34,
                ingredient -> Optional.ofNullable(converter.apply((String) ingredient.getIngredient()))
        );
    }

    private static IJeiRuntime runtime(
            IngredientSupplier listIngredient,
            IngredientSupplier bookmarkIngredient,
            ClickableSupplier screenIngredients
    ) {
        IIngredientListOverlay listOverlay = proxy(IIngredientListOverlay.class, (method, arguments) -> {
            if (method.getName().equals("getIngredientUnderMouse") && method.getParameterCount() == 0) {
                return listIngredient.get();
            }
            return defaultValue(method.getReturnType());
        });
        IBookmarkOverlay bookmarkOverlay = proxy(IBookmarkOverlay.class, (method, arguments) -> {
            if (method.getName().equals("getIngredientUnderMouse") && method.getParameterCount() == 0) {
                return bookmarkIngredient.get();
            }
            return defaultValue(method.getReturnType());
        });
        IScreenHelper screenHelper = proxy(IScreenHelper.class, (method, arguments) -> {
            if (method.getName().equals("getClickableIngredientUnderMouse")) {
                return screenIngredients.get();
            }
            return defaultValue(method.getReturnType());
        });
        return proxy(IJeiRuntime.class, (method, arguments) -> switch (method.getName()) {
            case "getIngredientListOverlay" -> listOverlay;
            case "getBookmarkOverlay" -> bookmarkOverlay;
            case "getScreenHelper" -> screenHelper;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ITypedIngredient<String> ingredient(String value) {
        return new ITypedIngredient<>() {
            @Override
            public IIngredientType<String> getType() {
                return () -> String.class;
            }

            @Override
            public String getIngredient() {
                return value;
            }
        };
    }

    private static IClickableIngredient<String> clickable(String value) {
        return new IClickableIngredient<>() {
            @Override
            public ITypedIngredient<String> getTypedIngredient() {
                return ingredient(value);
            }

            @Override
            public Rect2i getArea() {
                return new Rect2i(0, 0, 16, 16);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + " test proxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> throw new UnsupportedOperationException(method.toString());
                        };
                    }
                    return handler.invoke(method, arguments);
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @FunctionalInterface
    private interface IngredientSupplier {
        Optional<ITypedIngredient<?>> get();
    }

    @FunctionalInterface
    private interface ClickableSupplier {
        Stream<IClickableIngredient<?>> get();
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
