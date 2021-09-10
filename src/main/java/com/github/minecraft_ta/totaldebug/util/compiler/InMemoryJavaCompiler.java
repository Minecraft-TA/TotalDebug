package com.github.minecraft_ta.totaldebug.util.compiler;

import com.google.common.collect.Lists;
import io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess;
import net.minecraft.launchwrapper.LaunchClassLoader;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.stream.Collectors;

public class InMemoryJavaCompiler {

    public static Class<?> compile(String className, String code, ClassLoader classLoader) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        try {
            InMemoryJavaFileManager fileManager = new InMemoryJavaFileManager(compiler.getStandardFileManager(null, null, null), classLoader);
            DiagnosticCollector<JavaFileObject> dia = new DiagnosticCollector<>();
            StringWriter writer = new StringWriter(100);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    writer,
                    fileManager,
                    dia, Lists.newArrayList("-cp", constructClassPathArgument()), null,
                    Lists.newArrayList(new StringInputObject(className, code))
            );

            boolean result = task.call();
            writer.close();
            if (!result)
                return null;

            byte[] bytes = fileManager.getOutputObjectList().get(0).getByteCode();
            return UnsafeAccess.UNSAFE.defineClass(className, bytes, 0, bytes.length, InMemoryJavaCompiler.class.getClassLoader(), null);
        } catch (URISyntaxException | IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private static String constructClassPathArgument() {
        return ((LaunchClassLoader) InMemoryJavaCompiler.class.getClassLoader()).getSources().stream().map(URL::getFile).collect(Collectors.joining(";"));
    }
}
