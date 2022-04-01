package com.github.minecraft_ta.totaldebug.util.compiler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.mappings.RuntimeMappingsTransformer;
import com.google.common.collect.Lists;
import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.commons.lang3.SystemUtils;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InMemoryJavaCompiler {

    private static final IClassTransformer TRANSFORMER = new RuntimeMappingsTransformer(true);
    private static final boolean DEOBFUSCATED_ENVIRONMENT = (Boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment");

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
                loadedClasses.add(UnsafeAccess.UNSAFE.defineClass(className, bytes, 0, bytes.length, InMemoryJavaCompiler.class.getClassLoader(), null));
            }

            return loadedClasses;
        } catch (URISyntaxException e) {
            throw new InMemoryCompilationFailedException(e);
        }
    }

    public static String constructClassPathArgument() {
        try {
            return Stream.concat(
                    ((LaunchClassLoader) InMemoryJavaCompiler.class.getClassLoader()).getSources().stream().filter(url -> !url.toString().contains("forge-") && !url.toString().endsWith("/1.12.2.jar")),
                    Stream.of(TotalDebug.PROXY.getMinecraftClassDumpPath().toUri().toURL())
            ).map(url -> {
                try {
                    return URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException ignored) {
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.joining(SystemUtils.IS_OS_UNIX ? ":" : ";"));
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
