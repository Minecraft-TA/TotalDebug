package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

public final class LocalJvmDebugTargetResolver {
    static final String JDWP_LISTENER_ADDRESS = "sun.jdwp.listenerAddress";

    @FunctionalInterface
    interface AgentPropertiesReader {
        Properties read(long processId) throws IOException, AttachNotSupportedException;
    }

    private final AgentPropertiesReader propertiesReader;

    public LocalJvmDebugTargetResolver() {
        this(LocalJvmDebugTargetResolver::readAgentProperties);
    }

    LocalJvmDebugTargetResolver(AgentPropertiesReader propertiesReader) {
        this.propertiesReader = Objects.requireNonNull(propertiesReader, "propertiesReader");
    }

    public DebugEngine.Target resolve(DebugTargetDescriptor target, Duration timeout) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeout");
        ProcessHandle process = ProcessHandle.of(target.processId())
                .filter(ProcessHandle::isAlive)
                .orElseThrow(() -> new IOException(
                        target.displayName() + " process " + target.processId() + " is not running"
                ));
        if (process.pid() == ProcessHandle.current().pid()) {
            throw new IOException("Companion cannot debug its own JVM");
        }

        Properties properties;
        try {
            properties = this.propertiesReader.read(target.processId());
        } catch (AttachNotSupportedException exception) {
            throw new IOException("Unable to inspect " + target.displayName() + " process " + target.processId(), exception);
        }
        String listenerAddress = properties.getProperty(JDWP_LISTENER_ADDRESS);
        if (listenerAddress == null || listenerAddress.isBlank()) {
            throw new IOException(
                    target.displayName() + " was not started with the JDWP agent; add "
                            + "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0"
            );
        }
        return DebugEngine.Target.local(parsePort(listenerAddress), timeout);
    }

    static int parsePort(String listenerAddress) throws IOException {
        String value = Objects.requireNonNull(listenerAddress, "listenerAddress").trim();
        String prefix = "dt_socket:";
        if (!value.startsWith(prefix)) {
            throw new IOException("Unsupported JDWP listener address: " + value);
        }
        String address = value.substring(prefix.length());
        int separator = address.lastIndexOf(':');
        String portText = separator < 0 ? address : address.substring(separator + 1);
        try {
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65_535) {
                throw new IOException("Invalid JDWP listener port: " + port);
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid JDWP listener address: " + value, exception);
        }
    }

    private static Properties readAgentProperties(long processId)
            throws IOException, AttachNotSupportedException {
        VirtualMachine virtualMachine = VirtualMachine.attach(Long.toString(processId));
        try {
            return virtualMachine.getAgentProperties();
        } finally {
            virtualMachine.detach();
        }
    }
}
