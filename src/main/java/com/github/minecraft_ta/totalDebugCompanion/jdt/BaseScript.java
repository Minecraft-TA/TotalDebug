package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.util.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BaseScript {

    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s(.*?);");

    private static final String BASE_SCRIPT_IMPORTS = """
            import net.minecraft.network.chat.Component;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.neoforged.neoforge.server.ServerLifecycleHooks;
            
            import java.io.StringWriter;
            import java.lang.reflect.Constructor;
            import java.lang.reflect.Field;
            import java.lang.reflect.Method;
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.List;
            import java.util.Objects;
            """;

    //language=Java
    private static final String BASE_SCRIPT_TEXT = """
            abstract class BaseScript {
                /*
                ---- MC stuff
                */
                        
                public MinecraftServer getServer() {
                    return Objects.requireNonNull(
                        ServerLifecycleHooks.getCurrentServer(),
                        "No Minecraft server is running in this JVM"
                    );
                }
                        
                public void sendToAllPlayers(String message) {
                    getServerPlayers().forEach(player -> player.sendSystemMessage(Component.literal(message)));
                }
                        
                public ServerLevel getServerOverworld() {
                    return getServer().overworld();
                }
                        
                public List<ServerLevel> getServerWorlds() {
                    List<ServerLevel> levels = new ArrayList<>();
                    getServer().getAllLevels().forEach(levels::add);
                    return List.copyOf(levels);
                }
                        
                public List<ServerPlayer> getServerPlayers() {
                    return getServer().getPlayerList().getPlayers();
                }
                           \s
                /*
                ---- Reflection
                */
                        
                public static <T> T createInstance(Class<T> clazz, Object... args) {
                    try {
                        Constructor<T> ctor = clazz.getDeclaredConstructor(Arrays.stream(args).map(Object::getClass).toArray(Class[]::new));
                        ctor.setAccessible(true);
                        return ctor.newInstance(args);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
                        
                public static <T> T createInstance(Class<T> clazz, Class<?>[] argClasses, Object... args) {
                    try {
                        Constructor<T> ctor = clazz.getDeclaredConstructor(argClasses);
                        ctor.setAccessible(true);
                        return ctor.newInstance(args);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
                        
                public static <T> T invokeMethod(Object o, String methodName, Object... args) {
                    return invokeMethod(o, methodName, Arrays.stream(args).map(Object::getClass).toArray(Class[]::new), args);
                }
                        
                public static <T> T invokeMethod(Object o, String methodName, Class<?>[] argClasses, Object... args) {
                    try {
                        Method method = o.getClass().getDeclaredMethod(methodName, argClasses);
                        method.setAccessible(true);
                        return (T) method.invoke(o, args);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
                        
                public static <T> T invokeStaticMethod(Class<?> c, String methodName, Object... args) {
                    return  invokeStaticMethod(c, methodName, Arrays.stream(args).map(Object::getClass).toArray(Class[]::new), args);
                }
                        
                public static <T> T invokeStaticMethod(Class<?> c, String methodName, Class<?>[] argClasses, Object... args) {
                    try {
                        Method method = c.getDeclaredMethod(methodName, argClasses);
                        method.setAccessible(true);
                        return (T) method.invoke(null, args);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
                        
                public static <T> T getFieldValue(Object o, String fieldName) {
                    Field f = findField(o.getClass(), fieldName);
                    f.setAccessible(true);
                    try {
                        return (T) f.get(o);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
                        
                public static void setField(Object o, String fieldName, Object value) {
                    setField(o.getClass(), o, fieldName, value);
                }
                        
                public static void setField(Class<?> clazz, Object o, String fieldName, Object value) {
                    try {
                        Field field = findField(clazz, fieldName);
                        field.setAccessible(true);
                        field.set(o, value);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
                        
                public static Field findField(Object o, String fieldName) {
                    return findField(o.getClass(), fieldName);
                }
                        
                public static Field findField(Class<?> clazz, String fieldName) {
                    Class<?> current = clazz;
                    while (current != null) {
                        try {
                            return current.getDeclaredField(fieldName);
                        } catch (NoSuchFieldException e) {
                            current = current.getSuperclass();
                        }
                    }
                        
                    throw new RuntimeException("Field not found: " + fieldName);
                }
                        
                public static void setStaticField(Class<?> c, String fieldName, Object value) {
                    try {
                        Field field = c.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        field.set(null, value);
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }
                           \s
                /*
                ---- Logging
                */
                        
                private StringWriter logWriter = new StringWriter();
                private Object resultValue;
                private boolean resultSet;
                        
                public void logln(Object s) {
                    this.log(String.format("%s%n", s));
                }
                        
                public void log(Object s) {
                    this.logWriter.append(String.valueOf(s));
                }

                public void result(Object value) {
                    this.resultValue = value;
                    this.resultSet = true;
                }
                        
                public abstract void run() throws Throwable;
            }""".replace("    ", "\t");

    private static final String BASE_SCRIPT = BASE_SCRIPT_IMPORTS + BASE_SCRIPT_TEXT;
    private static FileTime lastChanged;
    private static Path cachedPath;
    private static String cachedContents;

    private BaseScript() {
    }

    public static String mergeWithNormalScript(String scriptText) {
        var pair = extractImports(getText());
        return pair.a() + scriptText + pair.b();
    }

    public static void writeToFileIfNotExists() {
        try {
            Path path = path();
            if (!Files.exists(path))
                Files.writeString(path, BASE_SCRIPT);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getText() {
        try {
            Path path = path();
            var lastModifiedTime = Files.getLastModifiedTime(path);
            if (!path.equals(cachedPath) || !lastModifiedTime.equals(lastChanged)) {
                cachedPath = path;
                lastChanged = lastModifiedTime;
                cachedContents = Files.readString(path).replace("\r\n", "\n");
            }

            return cachedContents;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static Path path() {
        return CompanionApp.getRootPath().resolve("scripts").resolve("BaseScript.java");
    }

    private static Pair<String, String> extractImports(String code) {
        Matcher matcher = IMPORT_PATTERN.matcher(code);

        StringBuilder imports = new StringBuilder();
        while (matcher.find()) {
            imports.append(matcher.group());
        }

        return new Pair<>(imports.toString(), code.replaceAll(IMPORT_PATTERN.pattern() + "(\\r\\n|\\r|\\n)", ""));
    }
}
