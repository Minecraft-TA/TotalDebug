package com.github.minecraft_ta.totaldebug.util;

import com.github.minecraft_ta.totaldebug.TotalDebug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ClassUtil {

    private ClassUtil() {}

    public static byte[] getBytecode(Class<?> clazz) {
        String name = clazz.getName();

        String codeSource = clazz.getProtectionDomain().getCodeSource().getLocation().toString();
        if (codeSource.startsWith("jar"))
            codeSource = codeSource.substring(codeSource.lastIndexOf('!') + 2);
        else
            codeSource = clazz.getName().replace(".", "/") + ".class";

        try (InputStream inputStream = clazz.getClassLoader().getResourceAsStream(codeSource)) {
            if (inputStream == null)
                throw new RuntimeException("Class " + name + " not found");

            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int r;
            while ((r = inputStream.read(buffer, 0, buffer.length)) != -1) {
                out.write(buffer, 0, r);
            }

            return out.toByteArray();
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Unable to load resource " + codeSource, e);
        }

        return null;
    }
}
