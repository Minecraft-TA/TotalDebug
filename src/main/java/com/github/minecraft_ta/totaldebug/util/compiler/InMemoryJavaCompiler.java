package com.github.minecraft_ta.totaldebug.util.compiler;

import com.github.minecraft_ta.totaldebug.companionApp.messages.script.ClassPathMessage;
import com.google.common.collect.Lists;
import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess;
import net.minecraft.launchwrapper.LaunchClassLoader;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InMemoryJavaCompiler {

    public static List<Class<?>> compile(String code, String... classNames) throws InMemoryCompilationFailedException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

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
                byte[] bytes = outputObject.getByteCode();
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
                    ((LaunchClassLoader) InMemoryJavaCompiler.class.getClassLoader()).getSources().stream(),
                    Stream.of(ClassPathMessage.MINECRAFT_CLASS_LIB_PATH.toUri().toURL())
            ).map(URL::getFile).collect(Collectors.joining(";"));
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
