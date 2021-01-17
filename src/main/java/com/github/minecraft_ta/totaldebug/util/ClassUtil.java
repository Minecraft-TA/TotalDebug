package com.github.minecraft_ta.totaldebug.util;

import com.github.minecraft_ta.totaldebug.TotalDebug;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSource;
import java.security.ProtectionDomain;

public class ClassUtil {


    private ClassUtil() {
    }

    @Nullable
    public static byte[] getBytecode(Class<?> clazz) {
        String codeSource = getClassCodeSource(clazz);
        if (codeSource == null)
            return null;

        try (InputStream inputStream = clazz.getClassLoader().getResourceAsStream(codeSource)) {
            if (inputStream == null)
                throw new RuntimeException("Class " + clazz.getName() + " not found");

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
        }
    }

    @Nullable
    public static String getClassCodeSource(Class<?> clazz) {
        ProtectionDomain protectionDomain = clazz.getProtectionDomain();
        if (protectionDomain == null)
            return null;

        CodeSource codeSourceObj = protectionDomain.getCodeSource();
        if (codeSourceObj == null)
            return null;

        String codeSource = codeSourceObj.getLocation().toString();
        if (codeSource.startsWith("jar"))
            codeSource = codeSource.substring(codeSource.lastIndexOf('!') + 2);
        else
            codeSource = clazz.getName().replace(".", "/") + ".class";
        return codeSource;
    }
}
