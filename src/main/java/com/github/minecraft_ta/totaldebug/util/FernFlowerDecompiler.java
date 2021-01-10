package com.github.minecraft_ta.totaldebug.util;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import org.jetbrains.java.decompiler.main.ClassesProcessor;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;
import org.jetbrains.java.decompiler.struct.IDecompiledData;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructContext;
import org.jetbrains.java.decompiler.struct.lazy.LazyLoader;
import org.jetbrains.java.decompiler.util.TextBuffer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Manifest;

/**
 * Custom FernFlower decompiler to allow decompiling without a file
 */
public class FernFlowerDecompiler implements IDecompiledData {
    private static final IResultSaver DUMMY_RESULT_SAVER = new DummyResultSaver();

    private final CustomStructContext structContext;
    private final ClassesProcessor classProcessor;

    private final Map<String, byte[]> classCache = new HashMap<>();

    public FernFlowerDecompiler() {
        structContext = new CustomStructContext(DUMMY_RESULT_SAVER, this, new LazyLoader((externalPath, internalPath) -> {
            if (internalPath != null) {
                byte[] code = classCache.computeIfAbsent(internalPath, k -> {
                    try {
                        return ClassUtil.getBytecode(Class.forName(internalPath.substring(0, internalPath.length() - 6)));
                    } catch (ClassNotFoundException e) {
                        return null;
                    }
                });
                if (code == null)
                    throw new IllegalStateException();
                return code;
            }
            throw new IllegalStateException("Provider should only receive internal names." +
                    "Got external name: " + externalPath);
        }));

        classProcessor = new ClassesProcessor(structContext);

        Map<String, Object> options = new HashMap<>(IFernflowerPreferences.getDefaults());
        options.put("ind", "    ");
        DecompilerContext context = new DecompilerContext(options, new IFernflowerLogger() {
            @Override
            public void writeMessage(String s, Severity severity) {
                System.out.println(s);
            }

            @Override
            public void writeMessage(String s, Severity severity, Throwable throwable) {
                System.out.println(s);
                TotalDebug.LOGGER.error(" ", throwable);
            }
        }, structContext, classProcessor, null);
        DecompilerContext.setCurrentContext(context);
    }

    public void addClass(Class<?> clazz) {
        this.structContext.addClass(clazz);
    }

    public void analyze() {
        this.classProcessor.loadClasses(null);
    }

    public String decompile(String name) {
        StructClass clazz = structContext.getClass(name.replace(".", "/"));
        if (clazz == null)
            throw new IllegalArgumentException("FernFlower could not find \"" + name + "\"");
        return getClassContent(clazz);
    }

    @Override
    public String getClassEntryName(StructClass structClass, String s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getClassContent(StructClass cl) {
        TextBuffer buffer = new TextBuffer(ClassesProcessor.AVERAGE_CLASS_SIZE);
        String name = cl.qualifiedName;
        try {
            classProcessor.writeClass(cl, buffer);
        } catch (Throwable t) {
            TotalDebug.LOGGER.error("Unable to decompile class " + name, t);
        }
        return buffer.toString();
    }

    private static final class CustomStructContext extends StructContext {

        public CustomStructContext(IResultSaver saver, IDecompiledData decompiledData, LazyLoader loader) {
            super(saver, decompiledData, loader);
        }

        private void addClass(Class<?> clazz) {
            String name = clazz.getName();
            byte[] code = ClassUtil.getBytecode(clazz);

            if (code == null)
                return;

            LazyLoader loader = getLoader();
            try {
                getClasses().put(name.replace(".", "/"), new StructClass(code, true, loader));
                loader.addClassLink(name.replace(".", "/"), new LazyLoader.Link(null, name + ".class"));
            } catch (IOException e) {
                TotalDebug.LOGGER.error("Error while reading bytecode " + name, e);
            }
        }

        private LazyLoader getLoader() {
            try {
                Field loaderField = StructContext.class.getDeclaredField("loader");
                loaderField.setAccessible(true);
                return (LazyLoader) loaderField.get(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException();
            }
        }
    }

    private static final class DummyResultSaver implements IResultSaver {

        @Override
        public void saveFolder(String s) {
        }

        @Override
        public void copyFile(String s, String s1, String s2) {
        }

        @Override
        public void saveClassFile(String s, String s1, String s2, String s3, int[] ints) {
        }

        @Override
        public void createArchive(String s, String s1, Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String s, String s1, String s2) {
        }

        @Override
        public void copyEntry(String s, String s1, String s2, String s3) {
        }

        @Override
        public void saveClassEntry(String s, String s1, String s2, String s3, String s4) {
        }

        @Override
        public void closeArchive(String s, String s1) {
        }
    }
}
