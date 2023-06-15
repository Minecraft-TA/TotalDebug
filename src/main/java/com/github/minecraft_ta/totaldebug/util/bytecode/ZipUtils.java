package com.github.minecraft_ta.totaldebug.util.bytecode;

import com.github.minecraft_ta.totaldebug.util.unchecked.Unchecked;
import com.github.minecraft_ta.totaldebug.util.unchecked.UncheckedBiConsumer;
import com.github.minecraft_ta.totaldebug.util.unchecked.UncheckedSupplier;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    public static void readAllFiles(Path path, Predicate<String> entryFilter, UncheckedBiConsumer<String, UncheckedSupplier<byte[]>> consumer) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipInputStream inputStream = new ZipInputStream(new BufferedInputStream(Files.newInputStream(path), 64 * 1024))) {
            ZipEntry entry;
            while ((entry = inputStream.getNextEntry()) != null) {
                if (entry.isDirectory() || !entryFilter.test(entry.getName()))
                    continue;

                consumer.accept(entry.getName(), () -> {
                    buffer.reset();
                    IOUtils.copy(inputStream, buffer);
                    return buffer.toByteArray();
                });
            }
        } catch (Throwable t) {
            Unchecked.propagate(t);
        }
    }

    public static void openForWriting(Path path, UncheckedBiConsumer<ZipOutputStream, CRC32> writer) {
        try (ZipOutputStream outputStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 64 * 1024))) {
            CRC32 crc32 = new CRC32();
            writer.accept(outputStream, crc32);
        } catch (Throwable t) {
            Unchecked.propagate(t);
        }
    }

    public static void writeStoredEntry(ZipOutputStream outputStream, CRC32 crc, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        crc.reset();
        crc.update(data);
        entry.setCrc(crc.getValue());
        outputStream.putNextEntry(entry);
        outputStream.write(data);
        outputStream.closeEntry();
    }
}
