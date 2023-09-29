package com.github.minecraft_ta.totaldebug.util.compiler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.bytecode.RuntimeMappingsTransformer;
import com.google.common.collect.Lists;
import dev.xdark.deencapsulation.Deencapsulation;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.commons.lang3.SystemUtils;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InMemoryJavaCompiler {

    private static final IClassTransformer TRANSFORMER = new RuntimeMappingsTransformer(true);
    private static final boolean DEOBFUSCATED_ENVIRONMENT = (Boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment");

    private static final Object INTERNAL_UNSAFE;
    private static final Method DEFINE_CLASS_METHOD;
    static {
        try {
            Deencapsulation.deencapsulate(InMemoryJavaCompiler.class);
            Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            INTERNAL_UNSAFE = unsafeClass.getDeclaredMethod("getUnsafe").invoke(null);
            DEFINE_CLASS_METHOD = unsafeClass.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class, ClassLoader.class, ProtectionDomain.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get internal Unsafe instance!", e);
        }
    }

    public static List<Class<?>> compile(String code, String... classNames) throws InMemoryCompilationFailedException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new InMemoryCompilationFailedException("Java Compiler not found! Please use JDK instead of JRE.\nCurrent JAVA_HOME: " + System.getProperty("java.home"));

        try {
            InMemoryJavaFileManager fileManager = new InMemoryJavaFileManager(compiler.getStandardFileManager(null, null, null));
            DiagnosticCollector<JavaFileObject> dia = new DiagnosticCollector<>();
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    dia, Lists.newArrayList("-cp", constructClassPathArgument()), null,
                    Lists.newArrayList(new StringInputObject(classNames[0], code))
            );

            boolean result = task.call();
            if (!result) {
                throw new InMemoryCompilationFailedException(dia.getDiagnostics().stream().map(Object::toString).collect(Collectors.joining("\n")));
            }

            List<Class<?>> loadedClasses = new ArrayList<>();
            List<BytecodeOutputObject> outputObjectList = fileManager.getOutputObjectList();
            for (int i = outputObjectList.size() - 1; i >= 0; i--) {
                BytecodeOutputObject outputObject = outputObjectList.get(i);
                String className = classNames[i];

                byte[] bytes = DEOBFUSCATED_ENVIRONMENT ? outputObject.getByteCode() : TRANSFORMER.transform(className, className, outputObject.getByteCode());
                loadedClasses.add((Class<?>) DEFINE_CLASS_METHOD.invoke(INTERNAL_UNSAFE, className, bytes, 0, bytes.length, InMemoryJavaCompiler.class.getClassLoader(), null));
            }

            return loadedClasses;
        } catch (Exception e) {
            throw new InMemoryCompilationFailedException(e);
        }
    }

    public static String constructClassPathArgument() {
        try {
            return Stream.concat(
                            ((LaunchClassLoader) InMemoryJavaCompiler.class.getClassLoader()).getSources().stream(),
                            Stream.of(TotalDebug.PROXY.getMinecraftClassDumpPath().toUri().toURL())
                    ).map(url -> {
                        try {
                            return url.toURI();
                        } catch (URISyntaxException ignored) {
                            TotalDebug.LOGGER.error("Failed to convert URL to URI: " + url);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .map(Paths::get)
                    .distinct()
                    .filter(path -> {
                        String str = path.getFileName().toString();
                        return !str.startsWith("forge-") && !str.equals("1.7.10.jar")
                               // This jar has a Class-Path manifest attribute which causes unwanted jars to be added to the classpath
                               && !str.equals("lwjgl3ify-forgePatches.jar")
                               && (!str.contains("minecraft") || str.equals("minecraft-class-dump.jar"));
                    })
                    .map(Path::toString)
                    .collect(Collectors.joining(SystemUtils.IS_OS_UNIX ? ":" : ";"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public static class InMemoryCompilationFailedException extends Exception {

        public InMemoryCompilationFailedException(String message) {
            super(message);
        }

        public InMemoryCompilationFailedException(Throwable cause) {
            super(cause);
        }
    }
}
