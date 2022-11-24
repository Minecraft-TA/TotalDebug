package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.ForkJoinHelper;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import com.github.tth05.jindex.ClassIndex;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer;
import org.apache.commons.io.IOUtils;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
                    .filter(t -> t instanceof DeobfuscationTransformer)
                    .collect(Collectors.toList());
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
            try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(outputPath));
                 ScanResult scanResult = new ClassGraph().enableClassInfo()
                         .disableNestedJarScanning()
                         .disableRuntimeInvisibleAnnotations()
                         .ignoreClassVisibility()
                         .acceptJars("1.12.2*.jar", "forge*.jar", "minecraft*.jar").scan()) {
                //Get minecraft classes using classpath scan
                for (ClassInfo classInfo : scanResult.getAllClasses()) {
                    String name = getTransformedName(classInfo.getName());
                    if (!name.startsWith("net.minecraft"))
                        continue;

                    writeTransformedClassToZip(outputStream, classInfo.getName(), name, () -> {
                        try (InputStream stream = classInfo.getResource().open()) {
                            return IOUtils.toByteArray(stream);
                        }
                    });
                }
            }

            TotalDebug.LOGGER.info("Completed dumping minecraft classes in {}ms", (System.nanoTime() - time) / 1_000_000);
        } catch (
                Throwable e) {
            TotalDebug.LOGGER.error("Unable to dump minecraft classes", e);
        }
    }

    public static void createClassIndex(Path indexPath) {
        long time = System.nanoTime();

        //TODO: Prevent running this twice at the same time

        //TODO: Show progress to player because this takes a while
        Path tmpDirPath = TotalDebug.PROXY.getDecompilationManager().getDataDir().resolve("tmp");
        try {
            if (!Files.exists(tmpDirPath))
                Files.createDirectories(tmpDirPath);

            List<Path> classLoaderPaths = ((LaunchClassLoader) InMemoryJavaCompiler.class.getClassLoader()).getSources().stream()
                    .map(url -> {
                        try {
                            Path path = Paths.get(url.toURI()).toAbsolutePath().normalize();
                            return !Files.exists(path) ? null : path;
                        } catch (InvalidPathException | URISyntaxException |
                                 FileSystemNotFoundException exception) {
                            exception.printStackTrace();
                            return null;
                        }
                    }).filter(Objects::nonNull).collect(Collectors.toList());
            List<Path> libraryPaths = classLoaderPaths.stream().filter(path -> !path.toString().contains("mods")).collect(Collectors.toList());
            List<Path> modPaths = classLoaderPaths.stream().filter(path -> path.toString().contains("mods")).collect(Collectors.toList());
            modPaths = ForkJoinHelper.splitWork(
                    modPaths,
                    (input) -> {
                        List<Path> results = new ArrayList<>();

                        for (Path path : input) {
                            // We deobfuscate all mods into temporary jars here
                            Path newPath = tmpDirPath.resolve(path.getFileName());
                            try (ZipInputStream inputStream = new ZipInputStream(new BufferedInputStream(Files.newInputStream(path)));
                                 ZipOutputStream outputStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(newPath)))) {
                                ZipEntry entry;
                                while ((entry = inputStream.getNextEntry()) != null) {
                                    if (entry.isDirectory() || !entry.getName().endsWith(".class"))
                                        continue;

                                    String className = entry.getName().substring(0, entry.getName().length() - 6);
                                    long entrySize = entry.getSize();
                                    writeTransformedClassToZip(outputStream, className, className, () -> IOUtils.toByteArray(inputStream, entrySize));
                                }
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }

                            results.add(newPath);
                        }

                        return results;
                    }
            );

            List<String> jarPaths;
            // We get these separately because they might not be included in the sources of the class loader
            try (Stream<Path> jreFiles = Files.list(Paths.get(System.getProperty("java.home")).resolve("lib"))) {
                jarPaths = Stream.concat(
                                Stream.concat(
                                        libraryPaths.stream(),
                                        modPaths.stream()
                                ).map(Path::toString),
                                Stream.concat(
                                        Stream.of(TotalDebug.PROXY.getMinecraftClassDumpPath().toString()),
                                        jreFiles.map(p -> p.toAbsolutePath().toString())
                                )
                        )
                        .map(s -> s.replace('\\', '/'))
                        .filter(str -> {
                            return !str.contains("forge-") && !str.endsWith("/1.12.2.jar") && //Filter unneeded forge jars
                                   str.endsWith(".jar") && //Filter for only jars
                                   (!str.contains("jre") || str.endsWith("rt.jar") || str.endsWith("jce.jar")) && //Filter everything from JDK except [rt, jce]
                                   !str.contains("IDEA"); //Filter IDEA in dev environment
                        })
                        .distinct()
                        .collect(Collectors.toList());
            }

            ClassIndex classIndex = ClassIndex.fromJars(jarPaths);
            classIndex.saveToFile(indexPath.toAbsolutePath().normalize().toString());
            classIndex.destroy();

            TotalDebug.LOGGER.info("Completed indexing classes in {}ms", (System.nanoTime() - time) / 1_000_000);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // Delete temp dir
            try (Stream<Path> walk = Files.walk(tmpDirPath)) {
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete); // Avoids exception handling
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void writeTransformedClassToZip(ZipOutputStream outputStream, String untransformedName, String name, UncheckedSupplier<byte[]> fallbackResourceSupplier) {
        ZipEntry entry = new ZipEntry(name.replace('.', '/') + ".class");
        try {
            byte[] bytes = getBytecodeFromLaunchClassLoader(name, false);
            if (bytes == null) {
                bytes = runTransformers(untransformedName, name, fallbackResourceSupplier.get());

                if (bytes == null)
                    return;
            }

            outputStream.putNextEntry(entry);
            outputStream.write(bytes);
            outputStream.closeEntry();
        } catch (IOException ignored) {}
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
            if (!loadIfAbsent) {
                if (bytes == null)
                    return null;
                return runTransformers(untransformedName, transformedName, bytes);
            }

            try {
                if (bytes == null)
                    bytes = LAUNCH_CLASS_LOADER.getClassBytes(untransformedName);
            } catch (IOException ignored) {}
            try {
                if (bytes == null)
                    bytes = getBytecode(Class.forName(name.replace('/', '.'), false, LAUNCH_CLASS_LOADER));
            } catch (ClassNotFoundException ignored) {}

            if (bytes == null)
                return null;

            bytes = runTransformers(untransformedName, transformedName, bytes);
            return bytes;
        } catch (Throwable e) {
            return null;
        }
    }

    public static byte[] runTransformers(String untransformedName, String transformedName, byte[] bytes) {
        for (IClassTransformer transformer : LAUNCH_CLASS_LOADER_TRANSFORMERS) {
            try {
                bytes = transformer.transform(untransformedName, transformedName, bytes);
            } catch (Throwable e) {
                TotalDebug.LOGGER.error(String.format("Transformer %s failed for class %s, %s", transformer, untransformedName, transformedName), e);
            }
        }
        return bytes;
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            IOUtils.copy(inputStream, out);
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

    private interface UncheckedSupplier<T> extends Supplier<T> {

        T uncheckedGet() throws Throwable;

        @Override
        default T get() {
            try {
                return uncheckedGet();
            } catch (Throwable e) {
                propagate(e);
                throw new IllegalStateException("unreachable");
            }
        }

        static <T extends Throwable> void propagate(Throwable e) throws T {
            throw (T) e;
        }
    }
}
