package com.github.minecraft_ta.totaldebug.util.compiler;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class InMemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    private final List<BytecodeOutputObject> outputObjectList = new ArrayList<>();

    public InMemoryJavaFileManager(JavaFileManager fileManager) {
        super(fileManager);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
        try {
            BytecodeOutputObject outputObject = new BytecodeOutputObject(className);
            this.outputObjectList.add(outputObject);
            return outputObject;
        } catch (URISyntaxException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public boolean handleOption(String current, Iterator<String> remaining) {
        return super.handleOption(current, remaining);
    }

    public List<BytecodeOutputObject> getOutputObjectList() {
        return Collections.unmodifiableList(outputObjectList);
    }
}
