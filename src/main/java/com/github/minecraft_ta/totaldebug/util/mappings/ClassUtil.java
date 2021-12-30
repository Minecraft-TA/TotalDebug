package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.jindex.ClassIndex;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer;
import net.minecraftforge.fml.common.asm.transformers.ItemBlockSpecialTransformer;
import net.minecraftforge.fml.common.asm.transformers.ItemBlockTransformer;
import net.minecraftforge.fml.common.asm.transformers.ItemStackTransformer;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.IOUtils;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClassUtil {

    private static final LaunchClassLoader LAUNCH_CLASS_LOADER = ((LaunchClassLoader) ClassUtil.class.getClassLoader());
    private static final List<IClassTransformer> LAUNCH_CLASS_LOADER_TRANSFORMERS;
    private static final Method UNTRANSFORM_NAME_METHOD;
    private static final Method TRANSFORM_NAME_METHOD;
    private static final Map<String, byte[]> LAUNCH_CLASS_LOADER_RESOURCE_CACHE;
    static {
        try {
            Field transformersField = LAUNCH_CLASS_LOADER.getClass().getDeclaredField("transformers");
            transformersField.setAccessible(true);
            LAUNCH_CLASS_LOADER_TRANSFORMERS = ((List<IClassTransformer>) transformersField.get(LAUNCH_CLASS_LOADER)).stream()
                    .filter(t -> t instanceof DeobfuscationTransformer || t instanceof ItemStackTransformer ||
                                 t instanceof ItemBlockTransformer || t instanceof ItemBlockSpecialTransformer).collect(Collectors.toList());
            LAUNCH_CLASS_LOADER_TRANSFORMERS.add(new RuntimeMappingsTransformer());
            UNTRANSFORM_NAME_METHOD = LAUNCH_CLASS_LOADER.getClass().getDeclaredMethod("untransformName", String.class);
            UNTRANSFORM_NAME_METHOD.setAccessible(true);
            TRANSFORM_NAME_METHOD = LAUNCH_CLASS_LOADER.getClass().getDeclaredMethod("transformName", String.class);
            TRANSFORM_NAME_METHOD.setAccessible(true);

            Field resourceCacheField = LAUNCH_CLASS_LOADER.getClass().getDeclaredField("resourceCache");
            resourceCacheField.setAccessible(true);
            LAUNCH_CLASS_LOADER_RESOURCE_CACHE = (Map<String, byte[]>) resourceCacheField.get(LAUNCH_CLASS_LOADER);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ClassUtil() {
    }

    public static void dumpMinecraftClasses() {
        Path outputPath = TotalDebug.PROXY.getMinecraftClassDumpPath();
        if (Files.exists(outputPath))
            return;

        TotalDebug.LOGGER.info("Creating minecraft class file dump jar");
        try {
            long time = System.nanoTime();

            Files.createDirectories(outputPath.getParent());
            Files.createFile(outputPath);
            try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(outputPath))) {
                ClassUtil.getCachedClassesFromLaunchClassLoader().keySet().stream()
                        .map(ClassUtil::getTransformedName)
                        .filter(k -> k.contains("net.minecraft") && !k.startsWith("$"))
                        .forEach(k -> {
                            ZipEntry entry = new ZipEntry(k.replace('.', '/') + ".class");
                            try {
                                byte[] bytes = getBytecodeFromLaunchClassLoader(k);
                                if (bytes == null)
                                    return;
                                outputStream.putNextEntry(entry);
                                outputStream.write(bytes);
                                outputStream.closeEntry();
                            } catch (IOException ignored) {}
                        });
            }

            TotalDebug.LOGGER.info("Completed dumping minecraft classes in {}ms", (System.nanoTime() - time) / 1_000_000);
        } catch (Exception e) {
            TotalDebug.LOGGER.error("Unable to dump minecraft classes", e);
        }
    }

    public static void createClassIndex(Path indexPath) {
        long time = System.nanoTime();

        //TODO: Show progress to player because this takes a while

        Map<String, byte[]> cachedClasses = new ConcurrentHashMap<>();
        Stream.concat(
                //All mod jars
                Loader.instance().getModList().stream().map(ModContainer::getSource).distinct().filter(f -> !f.toString().equals("minecraft.jar")),
                //Minecraft classes and jdk
                Stream.of(TotalDebug.PROXY.getMinecraftClassDumpPath().toFile(), new File(System.getProperty("java.home"), "lib" + File.separator + "rt.jar"))
        ).filter(file -> file.getName().endsWith(".jar")).parallel().forEach(file -> {
            //The JDK doesn't need any transformation and the class dump already went through it
            boolean ignoreLaunchClassLoader = file.getName().equals("rt.jar") ||
                                              file.getName().equals("minecraft-class-dump.jar");

            try (ZipFile zipFile = new ZipFile(file)) {
                Enumeration<ZipArchiveEntry> iterator = zipFile.getEntriesInPhysicalOrder();
                for (ZipArchiveEntry entry = iterator.nextElement(); iterator.hasMoreElements(); entry = iterator.nextElement()) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".class"))
                        continue;

                    String className = getTransformedName(entry.getName().substring(0, entry.getName().length() - 6));

                    ZipArchiveEntry finalEntry = entry;
                    cachedClasses.computeIfAbsent(className, (s) -> {
                        byte[] buf = ignoreLaunchClassLoader ? null : getBytecodeFromLaunchClassLoader(className, false);
                        if (buf == null) {
                            buf = new byte[(int) finalEntry.getSize()];
                            try (InputStream inputStream = zipFile.getInputStream(finalEntry)) {
                                IOUtils.readFully(inputStream, buf);
                            } catch (Throwable ignored) {
                                return null;
                            }

                            if (!ignoreLaunchClassLoader) {
                                for (IClassTransformer transformer : LAUNCH_CLASS_LOADER_TRANSFORMERS) {
                                    buf = transformer.transform(className, className, buf);
                                }
                            }
                        }

                        return buf;
                    });
                }
            } catch (Throwable throwable) {
                TotalDebug.LOGGER.error("Exception while reading mod jar " + file, throwable);
            }
        });

        new ClassIndex(new ArrayList<>(cachedClasses.values())).saveToFile(indexPath.toAbsolutePath().normalize().toString());

        TotalDebug.LOGGER.info("Completed indexing {} classes in {}ms", cachedClasses.size(), (System.nanoTime() - time) / 1_000_000);
    }

    public static Map<String, Class<?>> getCachedClassesFromLaunchClassLoader() {
        try {
            Field f = LaunchClassLoader.class.getDeclaredField("cachedClasses");
            f.setAccessible(true);
            return (Map<String, Class<?>>) f.get(LAUNCH_CLASS_LOADER);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    public static byte[] getBytecodeFromLaunchClassLoader(String name) {
        return getBytecodeFromLaunchClassLoader(name, true);
    }

    @Nullable
    public static byte[] getBytecodeFromLaunchClassLoader(String name, boolean loadIfAbsent) {
        try {
            String untransformedName = (String) UNTRANSFORM_NAME_METHOD.invoke(LAUNCH_CLASS_LOADER, name);
            String transformedName = getTransformedName(name);
            byte[] bytes = LAUNCH_CLASS_LOADER_RESOURCE_CACHE.get(untransformedName);
            if (bytes != null) {
                for (IClassTransformer transformer : LAUNCH_CLASS_LOADER_TRANSFORMERS) {
                    bytes = transformer.transform(untransformedName, transformedName, bytes);
                }
            } else if (loadIfAbsent) {
                try {
                    bytes = getBytecode(Class.forName(name.replace('/', '.'), false, LAUNCH_CLASS_LOADER));
                } catch (ClassNotFoundException ignored) {}
            }

            return bytes;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String getTransformedName(String name) {
        try {
            return (String) TRANSFORM_NAME_METHOD.invoke(LAUNCH_CLASS_LOADER, name);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public static byte[] getBytecode(Class<?> clazz) {
        String codeSource = getClassCodeSourceName(clazz);
        if (codeSource == null)
            return null;

        InputStream inputStream = null;

        if (clazz.getClassLoader() != null)
            inputStream = clazz.getClassLoader().getResourceAsStream(codeSource);

        if (inputStream == null) {
            inputStream = ClassLoader.getSystemResourceAsStream(clazz.getName().replace('.', '/') + ".class");
            if (inputStream == null)
                throw new RuntimeException("Class " + clazz.getName() + " not found");
        }

        try {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int r;
            while ((r = inputStream.read(buffer, 0, buffer.length)) != -1) {
                out.write(buffer, 0, r);
            }

            return out.toByteArray();
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to close stream " + clazz.getName(), e);
            return null;
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
            }
        }
    }

    @Nullable
    public static String getClassCodeSourceName(Class<?> clazz) {
        ProtectionDomain protectionDomain = clazz.getProtectionDomain();
        if (protectionDomain == null)
            return getClassCodeSourceNameUsingClassLoader(clazz);

        CodeSource codeSourceObj = protectionDomain.getCodeSource();
        if (codeSourceObj == null || codeSourceObj.getLocation() == null)
            return getClassCodeSourceNameUsingClassLoader(clazz);

        String codeSource = codeSourceObj.getLocation().toString();
        if (codeSource.startsWith("jar"))
            codeSource = codeSource.substring(codeSource.lastIndexOf('!') + 2);
        else
            codeSource = clazz.getName().replace(".", "/") + ".class";
        return codeSource;
    }

    /**
     * Usually only called for jdk classes which don't have code source.
     */
    @Nullable
    private static String getClassCodeSourceNameUsingClassLoader(Class<?> clazz) {
        URL resource = ClassLoader.getSystemResource(clazz.getName().replace('.', '/') + ".class");
        if (resource == null)
            return null;

        String resourcePath = resource.toString();
        if (!resourcePath.startsWith("jar"))
            throw new IllegalStateException("Resource not from jar");

        return resourcePath.substring(resourcePath.lastIndexOf('!') + 2);
    }
}
