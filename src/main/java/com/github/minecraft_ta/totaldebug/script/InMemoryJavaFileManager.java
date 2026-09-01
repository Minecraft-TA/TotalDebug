package com.github.minecraft_ta.totaldebug.script;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InMemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
    private final Map<String, BytecodeOutputObject> outputs = new LinkedHashMap<>();

    InMemoryJavaFileManager(JavaFileManager fileManager) {
        super(fileManager);
    }

    @Override
    public Iterable<JavaFileObject> list(
            Location location,
            String packageName,
            Set<JavaFileObject.Kind> kinds,
            boolean recurse
    ) throws java.io.IOException {
        Iterable<JavaFileObject> listed = super.list(location, packageName, kinds, recurse);
        if (!shouldRelax(location) || !kinds.contains(JavaFileObject.Kind.CLASS)) {
            return listed;
        }
        List<JavaFileObject> relaxed = new java.util.ArrayList<>();
        for (JavaFileObject file : listed) {
            relaxed.add(relax(file));
        }
        return relaxed;
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind)
            throws java.io.IOException {
        JavaFileObject input = super.getJavaFileForInput(location, className, kind);
        return shouldRelax(location) && kind == JavaFileObject.Kind.CLASS ? relax(input) : input;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return super.inferBinaryName(
                location,
                file instanceof RelaxedClassInput relaxed ? relaxed.delegate() : file
        );
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location,
            String className,
            JavaFileObject.Kind kind,
            FileObject sibling
    ) {
        BytecodeOutputObject output = new BytecodeOutputObject(className, kind);
        this.outputs.put(className, output);
        return output;
    }

    Map<String, byte[]> bytecode() {
        Map<String, byte[]> bytecode = new LinkedHashMap<>();
        this.outputs.forEach((name, output) -> bytecode.put(name, output.bytecode()));
        return Map.copyOf(bytecode);
    }

    private static boolean shouldRelax(Location location) {
        return StandardLocation.CLASS_PATH.getName().equals(location.getName());
    }

    private static JavaFileObject relax(JavaFileObject file) {
        if (file == null || file instanceof RelaxedClassInput || "jrt".equalsIgnoreCase(file.toUri().getScheme())) {
            return file;
        }
        return new RelaxedClassInput(file);
    }
}
