package com.github.minecraft_ta.totalDebugCompanion.session;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class CompanionLaunchConfiguration {
    public static final String TOKEN_ENVIRONMENT_VARIABLE = "TOTALDEBUG_SESSION_TOKEN";

    private static final String DATA_DIRECTORY = "--data-directory";
    private static final String INDEX_FILE = "--index-file";
    private static final String WORKSPACE_DIRECTORY = "--workspace-directory";
    private static final String SESSION_DESCRIPTOR = "--session-descriptor";

    private final Path dataDirectory;
    private final Path indexFile;
    private final Path workspaceDirectory;
    private final Path sessionDescriptor;
    private String sessionToken;

    private CompanionLaunchConfiguration(
            Path dataDirectory,
            Path indexFile,
            Path workspaceDirectory,
            Path sessionDescriptor,
            String sessionToken
    ) {
        this.dataDirectory = normalize(dataDirectory);
        this.indexFile = normalize(indexFile);
        this.workspaceDirectory = normalize(workspaceDirectory);
        this.sessionDescriptor = normalize(sessionDescriptor);
        this.sessionToken = Objects.requireNonNull(sessionToken, "sessionToken");
    }

    public static CompanionLaunchConfiguration parse(String[] arguments, Map<String, String> environment) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(environment, "environment");
        if (arguments.length != 8) {
            throw new IllegalArgumentException(
                    "Expected explicit data, index, workspace, and session descriptor arguments"
            );
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            String name = arguments[index];
            if (!isKnownArgument(name)) {
                throw new IllegalArgumentException("Unknown Companion argument: " + name);
            }
            if (values.putIfAbsent(name, arguments[index + 1]) != null) {
                throw new IllegalArgumentException("Duplicate Companion argument: " + name);
            }
        }
        if (values.size() != 4) {
            throw new IllegalArgumentException("All Companion session paths must be provided exactly once");
        }

        String token = environment.get(TOKEN_ENVIRONMENT_VARIABLE);
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException(
                    "Missing or invalid " + TOKEN_ENVIRONMENT_VARIABLE + " environment variable"
            );
        }

        return new CompanionLaunchConfiguration(
                Path.of(values.get(DATA_DIRECTORY)),
                Path.of(values.get(INDEX_FILE)),
                Path.of(values.get(WORKSPACE_DIRECTORY)),
                Path.of(values.get(SESSION_DESCRIPTOR)),
                token
        );
    }

    private static boolean isKnownArgument(String argument) {
        return argument.equals(DATA_DIRECTORY)
                || argument.equals(INDEX_FILE)
                || argument.equals(WORKSPACE_DIRECTORY)
                || argument.equals(SESSION_DESCRIPTOR);
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public Path dataDirectory() {
        return this.dataDirectory;
    }

    public Path indexFile() {
        return this.indexFile;
    }

    public Path workspaceDirectory() {
        return this.workspaceDirectory;
    }

    public Path sessionDescriptor() {
        return this.sessionDescriptor;
    }

    public synchronized String consumeSessionToken() {
        if (this.sessionToken == null) {
            throw new IllegalStateException("Companion session token was already consumed");
        }
        String token = this.sessionToken;
        this.sessionToken = null;
        return token;
    }
}
