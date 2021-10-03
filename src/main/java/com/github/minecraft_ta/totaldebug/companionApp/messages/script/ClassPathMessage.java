package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.util.mappings.ClassUtil;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ClassPathMessage extends AbstractMessage {

    public static final Path MINECRAFT_CLASS_LIB_PATH = TotalDebug.PROXY.getDecompilationManager().getDataDir()
            .resolve(CompanionApp.DATA_FOLDER).resolve("minecraft-class-dump.jar").toAbsolutePath().normalize();

    @Override
    public void read(ByteBufferInputStream messageStream) {
        //Acts as a request when received
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(Stream.concat(
                        ((LaunchClassLoader) InMemoryJavaCompiler.class.getClassLoader()).getSources()
                                .stream()
                                .map(url -> {
                                    try {
                                        return Paths.get(url.toURI()).toAbsolutePath().normalize().toString();
                                    } catch (URISyntaxException | FileSystemNotFoundException e) {
                                        return null;
                                    }
                                })
                                .filter(Objects::nonNull)
                                .filter(s -> !s.contains("akka") && !s.contains("jre\\lib") && !s.contains("jdk") && !s.contains("forge-")),
                        Stream.of(MINECRAFT_CLASS_LIB_PATH.toString())
                ).collect(Collectors.joining(";"))
        );
    }

    public static void handle(ClassPathMessage m) {
        CompletableFuture.runAsync(() -> {
            if (!Files.exists(MINECRAFT_CLASS_LIB_PATH)) {
                TotalDebug.LOGGER.info("Creating minecraft class file dump jar");
                long time = System.nanoTime();
                ClassUtil.dumpMinecraftClasses(MINECRAFT_CLASS_LIB_PATH);
                TotalDebug.LOGGER.info("Completed dumping minecraft classes in {}ms", (System.nanoTime() - time) / 1_000_000);
            }
            TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new ClassPathMessage());
        });
    }
}
