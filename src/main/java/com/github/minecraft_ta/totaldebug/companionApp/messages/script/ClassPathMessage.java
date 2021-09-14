package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.util.compiler.InMemoryJavaCompiler;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Collectors;

public class ClassPathMessage extends AbstractMessage {

    @Override
    public void read(ByteBufferInputStream messageStream) {
        //Acts as a request when received
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(
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
                        .filter(s -> !s.contains("akka"))
                        .collect(Collectors.joining(";"))
        );
    }

    public static void handle(ClassPathMessage m) {
        TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new ClassPathMessage());
    }
}
