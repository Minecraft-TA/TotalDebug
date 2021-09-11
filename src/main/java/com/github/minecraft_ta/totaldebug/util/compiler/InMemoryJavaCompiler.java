package com.github.minecraft_ta.totaldebug.util.compiler;

import com.google.common.collect.Lists;
import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess;
import net.minecraft.launchwrapper.LaunchClassLoader;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.stream.Collectors;

public class InMemoryJavaCompiler {

    public static Class<?> compile(String className, String code) throws InMemoryCompilationFailedException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        try {
            InMemoryJavaFileManager fileManager = new InMemoryJavaFileManager(compiler.getStandardFileManager(null, null, null));
            DiagnosticCollector<JavaFileObject> dia = new DiagnosticCollector<>();
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    dia, Lists.newArrayList("-cp", constructClassPathArgument()), null,
                    Lists.newArrayList(new StringInputObject(className, code))
            );

            boolean result = task.call();
            if (!result) {
                throw new InMemoryCompilationFailedException(dia.getDiagnostics().stream().map(Object::toString).collect(Collectors.joining("\n")));
            }

            byte[] bytes = fileManager.getOutputObjectList().get(0).getByteCode();
            return UnsafeAccess.UNSAFE.defineClass(className, bytes, 0, bytes.length, InMemoryJavaCompiler.class.getClassLoader(), null);
        } catch (URISyntaxException e) {
            throw new InMemoryCompilationFailedException(e);
        }
    }

    private static String constructClassPathArgument() {
        return ((LaunchClassLoader) InMemoryJavaCompiler.class.getClassLoader()).getSources().stream().map(URL::getFile).collect(Collectors.joining(";"));
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
