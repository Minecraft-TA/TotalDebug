package com.github.minecraft_ta.totaldebug.script;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.util.LinkedHashMap;
import java.util.Map;

final class InMemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {
    private final Map<String, BytecodeOutputObject> outputs = new LinkedHashMap<>();

    InMemoryJavaFileManager(JavaFileManager fileManager) {
        super(fileManager);
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
}
