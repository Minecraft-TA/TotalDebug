package com.github.minecraft_ta.totaldebug.util.mappings;

import com.github.minecraft_ta.totaldebug.TotalDebug;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

public class ClassUtil {

    private ClassUtil() {
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

    /**
     * @return a type signature for <<<<<the given {@code method} which looks javaparsers signature when it has no type
     * information
     */
    @Nonnull
    public static String getSimplifiedSignatureForMethod(@Nonnull Method method) {
        StringBuilder signatureBuilder = new StringBuilder(method.getName()).append('(');
        Type[] parameters = method.getGenericParameterTypes().length != 0 ? method.getGenericParameterTypes() : method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            Type parameter = parameters[i];
            String typeName = parameter.getTypeName();
            if (typeName.endsWith(">"))
                typeName = typeName.substring(0, typeName.indexOf('<'));
            if (typeName.contains("."))
                typeName = typeName.substring(typeName.lastIndexOf('.') + 1);

            signatureBuilder.append(typeName);

            if (i != parameters.length - 1)
                signatureBuilder.append(", ");
        }

        return signatureBuilder.append(')').toString();
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
