package com.github.minecraft_ta.totaldebug.util.compiler;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class InMemoryJavaFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    private final List<BytecodeOutputObject> outputObjectList = new ArrayList<>();
    private final ClassLoader classLoader;

    public InMemoryJavaFileManager(JavaFileManager fileManager, ClassLoader classLoader) {
        super(fileManager);
        this.classLoader = classLoader;
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        System.out.println("location = " + location + ", packageName = " + packageName + ", relativeName = " + relativeName);
        return super.getFileForInput(location, packageName, relativeName);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
        System.out.println("location = " + location + ", className = " + className + ", kind = " + kind);
        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
        System.out.println("location = " + location + ", className = " + className + ", kind = " + kind + ", sibling = " + sibling);
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

    //    @Override
//    public ClassLoader getClassLoader(Location location) {
//        return this.classLoader;
//    }

    public List<BytecodeOutputObject> getOutputObjectList() {
        return Collections.unmodifiableList(outputObjectList);
    }
}
