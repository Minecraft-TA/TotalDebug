package com.github.minecraft_ta.totaldebug.util.bytecode;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.ForkJoinUtils;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.util.unchecked.Unchecked;
import com.github.minecraft_ta.totaldebug.util.unchecked.UncheckedSupplier;
import com.github.tth05.jindex.ClassIndex;
import cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.objectweb.asm.ClassReader;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipOutputStream;

public class ClassUtil {

    private static final boolean LOG_TRANSFORMER_ERRORS = Boolean.getBoolean("totaldebug.logTransformerErrors");
    private static final List<String> MINIMAL_TRANSFORMER_WHITELIST = Arrays.asList(
            "net.minecraftforge.transformers.ForgeAccessTransformer",
            "cpw.mods.fml.common.asm.transformers.AccessTransformer",
            "cpw.mods.fml.common.asm.transformers.ItemStackTransformer",
            "cpw.mods.fml.common.asm.transformers.MarkerTransformer",
            "cpw.mods.fml.common.asm.transformers.PatchingTransformer",
            "com.github.minecraft_ta.totaldebug.util.bytecode.RuntimeMappingsTransformer"
    );
    private static final List<IClassTransformer> ALL_TRANSFORMERS;
    private static final List<IClassTransformer> MINIMAL_TRANSFORMERS;
    private static final Method UNTRANSFORM_NAME_METHOD;
    private static final Method TRANSFORM_NAME_METHOD;
    private static final Map<String, byte[]> LAUNCH_CLASS_LOADER_RESOURCE_CACHE;
    static {
        try {
            ALL_TRANSFORMERS = Launch.classLoader.getTransformers().stream()
                    .filter(t -> !(t instanceof DeobfuscationTransformer))
                    .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
            ALL_TRANSFORMERS.add(new RuntimeMappingsTransformer());
            MINIMAL_TRANSFORMERS = ALL_TRANSFORMERS.stream()
                    .filter(t -> MINIMAL_TRANSFORMER_WHITELIST.contains(t.getClass().getName()))
                    .collect(Collectors.toList());

            UNTRANSFORM_NAME_METHOD = Launch.classLoader.getClass().getDeclaredMethod("untransformName", String.class);
            UNTRANSFORM_NAME_METHOD.setAccessible(true);
            TRANSFORM_NAME_METHOD = Launch.classLoader.getClass().getDeclaredMethod("transformName", String.class);
            TRANSFORM_NAME_METHOD.setAccessible(true);

            Field resourceCacheField = Launch.classLoader.getClass().getDeclaredField("resourceCache");
            resourceCacheField.setAccessible(true);
            LAUNCH_CLASS_LOADER_RESOURCE_CACHE = (Map<String, byte[]>) resourceCacheField.get(Launch.classLoader);
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
            try (ScanResult scanResult = new ClassGraph().enableClassInfo()
                    .disableNestedJarScanning()
                    .disableRuntimeInvisibleAnnotations()
                    .ignoreClassVisibility()
                    .acceptJars("1.7.10*.jar", "forge*.jar", "*minecraft*.jar")
                    .scan()) {
                ZipUtils.openForWriting(outputPath, (outputStream, crc) -> {
                    // Get minecraft classes using classpath scan
                    for (ClassInfo classInfo : scanResult.getAllClasses()) {
                        String name = getTransformedName(classInfo.getName());
                        if (!name.startsWith("net.minecraft") && !name.startsWith("cpw.mods"))
                            continue;

                        writeTransformedClassToZip(outputStream, classInfo.getName(), name, crc, () -> {
                            try (InputStream stream = classInfo.getResource().open()) {
                                return IOUtils.toByteArray(stream);
                            }
                        });
                    }
                });
            }

            TotalDebug.LOGGER.info("Completed dumping minecraft classes in {}ms", (System.nanoTime() - time) / 1_000_000);
        } catch (Throwable e) {
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
                            URI uri = url.toURI();
                            if (uri.getScheme().equals("asmgen"))
                                return null;

                            Path path = Paths.get(uri).toAbsolutePath().normalize();
                            return !Files.exists(path) ? null : path;
                        } catch (InvalidPathException | URISyntaxException exception) {
                            exception.printStackTrace();
                            return null;
                        }
                    }).filter(Objects::nonNull).collect(Collectors.toList());
            List<Path> libraryPaths = classLoaderPaths.stream().filter(path -> !path.toString().contains("mods")).collect(Collectors.toList());
            List<Path> modPaths = classLoaderPaths.stream().filter(path -> path.toString().contains("mods")).collect(Collectors.toList());
            modPaths = ForkJoinUtils.parallelMap(
                    modPaths,
                    (input) -> {
                        return input.stream().map(path -> {
                            // We deobfuscate all mods into temporary jars here
                            Path newPath = tmpDirPath.resolve(path.getFileName());
                            ZipUtils.openForWriting(newPath, ((outputStream, crc) -> {
                                ZipUtils.readAllFiles(path, (name) -> name.endsWith(".class") && !name.startsWith("META-INF"), (name, data) -> {
                                    String className = name.substring(0, name.length() - 6);
                                    writeTransformedClassToZip(outputStream, className, className, crc, data);
                                });
                            }));

                            return newPath;
                        }).collect(Collectors.toList());
                    }
            );

            // Add jdk classes
            Path jdkClassesPath = tmpDirPath.resolve("jdk_classes.jar");
            libraryPaths.add(jdkClassesPath);
            ZipUtils.openForWriting(jdkClassesPath, (outputStream, crc) -> {
                Files.walk(Paths.get(URI.create("jrt:/")).resolve("/modules"))
                        .filter(path -> !Files.isDirectory(path))
                        .forEach(p -> {
                            String path = p.toString();
                            if (!path.endsWith(".class") || path.endsWith("module-info.class"))
                                return;

                            String name = path.substring(StringUtils.indexOf(path, '/', 9 /* Skips '/modules/' */));
                            Unchecked.propagate(() -> ZipUtils.writeStoredEntry(outputStream, crc, name, Files.readAllBytes(p)));
                        });
            });

            List<String> jarPaths = concatStreams(
                    libraryPaths.stream(),
                    modPaths.stream(),
                    Stream.of(TotalDebug.PROXY.getMinecraftClassDumpPath())
            )
                    .filter(str -> {
                        String fileName = str.getFileName().toString();
                        return !fileName.contains("forge") &&
                               (!fileName.contains("minecraft") || fileName.equals("minecraft-class-dump.jar"))
                               && !fileName.endsWith("/1.7.10.jar") && //Filter unneeded forge jars
                               fileName.endsWith(".jar") && //Filter for only jars
                               !fileName.contains("IDEA"); //Filter IDEA in dev environment
                    })
                    .map(Path::toString)
                    .distinct()
                    .collect(Collectors.toList());

            ClassIndex classIndex = ClassIndex.fromJars(jarPaths);
            classIndex.saveToFile(indexPath.toAbsolutePath().normalize().toString());
            classIndex.destroy();

            TotalDebug.LOGGER.info("Completed indexing classes in {}ms", (System.nanoTime() - time) / 1_000_000);

        } catch (Exception e) {
            Unchecked.propagate(e);
        } finally {
            // Delete temp dir
            try (Stream<Path> walk = Files.walk(tmpDirPath)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> Unchecked.propagate(() -> Files.delete(p)));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void writeTransformedClassToZip(ZipOutputStream outputStream, String untransformedName, String name, CRC32 crc32, UncheckedSupplier<byte[]> fallbackResourceSupplier) throws IOException {
        byte[] bytes = getBytecodeFromLaunchClassLoader(name, false);
        if (bytes == null) {
            bytes = runTransformers(untransformedName, name, fallbackResourceSupplier.get(), false);

            if (bytes == null)
                return;
        }

        // Removes broken classes, that are valid. Currently, this filters two classes from lwjgl3ify that have themselves
        // as their super class. These are org/lwjglx/PointerBuffer (org/lwjglx/PointerBuffer.class) and
        // org/lwjglx/openal/OpenALException (org/lwjgl/openal/OpenALException.class).
        ClassReader reader = new ClassReader(bytes);
        if (reader.getClassName().equals(reader.getSuperName()))
            return;

        ZipUtils.writeStoredEntry(outputStream, crc32, name.replace('.', '/') + ".class", bytes);
    }

    public static Map<String, Class<?>> getCachedClassesFromLaunchClassLoader() {
        try {
            Field f = LaunchClassLoader.class.getDeclaredField("cachedClasses");
            f.setAccessible(true);
            return (Map<String, Class<?>>) f.get(Launch.classLoader);
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
        return getBytecodeFromLaunchClassLoader(name, loadIfAbsent, false);
    }

    @Nullable
    public static byte[] getBytecodeFromLaunchClassLoader(String name, boolean loadIfAbsent, boolean minimalTransformers) {
        try {
            String untransformedName = (String) UNTRANSFORM_NAME_METHOD.invoke(Launch.classLoader, name);
            String transformedName = getTransformedName(name);
            byte[] bytes = LAUNCH_CLASS_LOADER_RESOURCE_CACHE.get(untransformedName);
            if (!loadIfAbsent) {
                if (bytes == null)
                    return null;
                return runTransformers(untransformedName, transformedName, bytes, minimalTransformers);
            }

            try {
                if (bytes == null)
                    bytes = Launch.classLoader.getClassBytes(untransformedName);
            } catch (IOException ignored) {}
            try {
                if (bytes == null)
                    bytes = getBytecode(Class.forName(name.replace('/', '.'), false, Launch.classLoader));
            } catch (ClassNotFoundException ignored) {}

            if (bytes == null)
                return null;

            bytes = runTransformers(untransformedName, transformedName, bytes, minimalTransformers);
            return bytes;
        } catch (Throwable e) {
            return null;
        }
    }

    public static byte[] runTransformers(String untransformedName, String transformedName, byte[] bytes, boolean minimalTransformers) {
        if (untransformedName.startsWith("java"))
            return bytes;

        List<IClassTransformer> transformers = minimalTransformers ? MINIMAL_TRANSFORMERS : ALL_TRANSFORMERS;
        for (IClassTransformer transformer : transformers) {
            try {
                bytes = transformer.transform(untransformedName, transformedName, bytes);
            } catch (Throwable e) {
                // Mixin issues we can safely ignore
                if (e.getClass().getName().equals("org.spongepowered.asm.mixin.transformer.throwables.IllegalClassLoadError"))
                    continue;

                if (LOG_TRANSFORMER_ERRORS) {
                    TotalDebug.LOGGER.error(String.format(
                                    "Transformer %s (%s) failed for class %s, %s. Disabling...",
                                    transformer, MINIMAL_TRANSFORMERS.contains(transformer) ? "minimal" : "other",
                                    untransformedName, transformedName),
                            e
                    );
                }
            }
        }
        return bytes;
    }

    private static String getTransformedName(String name) {
        try {
            return (String) TRANSFORM_NAME_METHOD.invoke(Launch.classLoader, name);
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
        if (resourcePath.startsWith("jar"))
            return resourcePath.substring(resourcePath.lastIndexOf('!') + 2);
        else if (resourcePath.startsWith("jrt"))
            return resourcePath.substring(StringUtils.ordinalIndexOf(resourcePath, "/", 2));
        else
            throw new RuntimeException("Invalid resource origin");
    }

    @SafeVarargs
    private static <T> Stream<T> concatStreams(Stream<T>... streams) {
        return Arrays.stream(streams).reduce(Stream::concat).orElseGet(Stream::empty);
    }
}
