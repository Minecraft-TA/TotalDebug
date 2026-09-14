package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerTypeScope;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.SourceType;
import com.microsoft.java.debug.core.protocol.Types;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Source lookup and exact source-line ownership for the Microsoft Java debug adapter. */
final class MicrosoftSourceRegistry implements ISourceLookUpProvider {
    private static final System.Logger LOGGER = System.getLogger(MicrosoftSourceRegistry.class.getName());

    private final DebuggerSessionController.SourceLoader sourceLoader;
    private final Map<URI, RegisteredSource> sourcesByUri = new ConcurrentHashMap<>();
    private final Map<String, RegisteredSource> sourcesByBinaryName = new ConcurrentHashMap<>();
    private final Map<URI, Map<Integer, DebugEngine.MethodTarget>> methodTargetsByUri = new ConcurrentHashMap<>();

    MicrosoftSourceRegistry(DebuggerSessionController.SourceLoader sourceLoader) {
        this.sourceLoader = Objects.requireNonNull(sourceLoader, "sourceLoader");
    }

    void register(DebugEngine.Source source) {
        DebugEngine.Source checked = Objects.requireNonNull(source, "source");
        RegisteredSource registered = RegisteredSource.parse(checked);
        URI normalizedUri = checked.uri().normalize();
        RegisteredSource previous = this.sourcesByUri.put(normalizedUri, registered);
        if (previous != null) {
            previous.binaryNames().forEach(binaryName -> this.sourcesByBinaryName.remove(binaryName, previous));
        }
        for (String binaryName : registered.binaryNames()) {
            this.sourcesByBinaryName.put(binaryName, registered);
        }
    }

    void prepareBreakpoints(URI sourceUri, List<DebugEngine.SourceBreakpoint> breakpoints) {
        URI normalizedUri = Objects.requireNonNull(sourceUri, "sourceUri").normalize();
        Map<Integer, DebugEngine.MethodTarget> targets = new HashMap<>();
        Map<Integer, Integer> requestsPerLine = new HashMap<>();
        for (DebugEngine.SourceBreakpoint breakpoint : List.copyOf(breakpoints)) {
            requestsPerLine.merge(breakpoint.debuggerLine(), 1, Integer::sum);
            if (breakpoint.method() != null) {
                DebugEngine.MethodTarget previous = targets.put(breakpoint.debuggerLine(), breakpoint.method());
                if (previous != null && !previous.equals(breakpoint.method())) {
                    throw new IllegalArgumentException(
                            "Conflicting method breakpoint targets at " + sourceUri + ":" + breakpoint.debuggerLine()
                    );
                }
            }
        }
        for (int debuggerLine : targets.keySet()) {
            if (requestsPerLine.getOrDefault(debuggerLine, 0) > 1) {
                throw new IllegalArgumentException(
                        "A method breakpoint cannot share debugger line " + debuggerLine + " in " + sourceUri
                );
            }
        }
        if (targets.isEmpty()) {
            this.methodTargetsByUri.remove(normalizedUri);
        } else {
            this.methodTargetsByUri.put(normalizedUri, Map.copyOf(targets));
        }
    }

    void requireRegistered(URI sourceUri) {
        requireRegisteredSource(sourceUri);
    }

    DebuggerTypeScope typeScope(String binaryName) {
        RegisteredSource source = sourceForClass(binaryName);
        return source == null ? null : source.typeScope();
    }

    String binaryName(Types.Source protocolSource, int line) {
        URI uri = sourceUri(protocolSource);
        if (uri == null) {
            return "";
        }
        RegisteredSource source = this.sourcesByUri.get(uri.normalize());
        return source == null ? "" : source.binaryNameAt(line);
    }

    String displayedVariableName(
            String binaryName,
            String methodName,
            String methodDescriptor,
            String runtimeName
    ) {
        RegisteredSource source = sourceForClass(binaryName);
        return source == null
                ? runtimeName
                : source.source().variableNames().displayedName(methodName, methodDescriptor, runtimeName);
    }

    @Override
    public boolean supportsRealtimeBreakpointVerification() {
        return false;
    }

    SourceVariableNames variableNames(URI sourceUri) {
        if (sourceUri == null) {
            return SourceVariableNames.empty();
        }
        RegisteredSource source = this.sourcesByUri.get(sourceUri.normalize());
        return source == null ? SourceVariableNames.empty() : source.source().variableNames();
    }

    @Override
    @Deprecated
    public String[] getFullyQualifiedName(String uri, int[] lines, int[] columns) {
        RegisteredSource source = requireRegisteredSource(URI.create(uri));
        String[] result = new String[lines.length];
        for (int index = 0; index < lines.length; index++) {
            result[index] = breakpointOwner(source, lines[index]);
        }
        return result;
    }

    @Override
    public JavaBreakpointLocation[] getBreakpointLocations(
            String sourceUri,
            Types.SourceBreakpoint[] sourceBreakpoints
    ) {
        URI uri = URI.create(sourceUri);
        RegisteredSource source = requireRegisteredSource(uri);
        JavaBreakpointLocation[] locations = new JavaBreakpointLocation[sourceBreakpoints.length];
        for (int index = 0; index < sourceBreakpoints.length; index++) {
            Types.SourceBreakpoint breakpoint = sourceBreakpoints[index];
            JavaBreakpointLocation location = new JavaBreakpointLocation(breakpoint.line, breakpoint.column);
            DebugEngine.MethodTarget method = methodTarget(source, breakpoint.line);
            location.setClassName(method == null ? source.binaryNameAt(breakpoint.line) : method.ownerClassName());
            if (method != null) {
                location.setMethodName(method.name());
                location.setMethodSignature(method.descriptor());
            }
            locations[index] = location;
        }
        return locations;
    }

    @Override
    @Deprecated
    public String getSourceFileURI(String fullyQualifiedName, String sourcePath) {
        RegisteredSource source = sourceForClass(fullyQualifiedName);
        return source == null ? null : source.source().uri().toString();
    }

    @Override
    public com.microsoft.java.debug.core.adapter.Source getSource(
            String fullyQualifiedName,
            String sourcePath
    ) {
        if (fullyQualifiedName.indexOf('/') >= 0) {
            // Hidden runtime classes have no binary name or independently loadable source.
            return null;
        }
        RegisteredSource source = sourceForClass(fullyQualifiedName);
        if (source == null) {
            try {
                DebugEngine.Source loaded = this.sourceLoader.load(fullyQualifiedName);
                if (loaded != null) {
                    register(loaded);
                    source = sourceForClass(fullyQualifiedName);
                }
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Source is optional. A failed decompilation must not abort the adapter's entire stack trace.
                LOGGER.log(System.Logger.Level.WARNING,
                        "Unable to resolve debugger source for " + fullyQualifiedName, exception);
                return null;
            }
        }
        return source == null
                ? null
                : new com.microsoft.java.debug.core.adapter.Source(
                        source.source().uri().toString(),
                        SourceType.LOCAL
                );
    }

    @Override
    public String getSourceContents(String uri) {
        RegisteredSource source = this.sourcesByUri.get(URI.create(uri).normalize());
        return source == null ? null : source.source().contents();
    }

    @Override
    public int[] getOriginalLineMappings(String uri) {
        RegisteredSource source = this.sourcesByUri.get(URI.create(uri).normalize());
        return source == null ? null : source.source().lineMap().originalToDisplayed();
    }

    @Override
    public int[] getDecompiledLineMappings(String uri) {
        RegisteredSource source = this.sourcesByUri.get(URI.create(uri).normalize());
        return source == null ? null : source.source().lineMap().displayedToOriginal();
    }

    @Override
    public List<MethodInvocation> findMethodInvocations(String uri, int line) {
        return List.of();
    }

    private String breakpointOwner(RegisteredSource source, int line) {
        DebugEngine.MethodTarget method = methodTarget(source, line);
        return method == null ? source.binaryNameAt(line) : method.ownerClassName();
    }

    private DebugEngine.MethodTarget methodTarget(RegisteredSource source, int line) {
        return this.methodTargetsByUri
                .getOrDefault(source.source().uri().normalize(), Map.of())
                .get(line);
    }

    private RegisteredSource requireRegisteredSource(URI sourceUri) {
        RegisteredSource source = this.sourcesByUri.get(sourceUri.normalize());
        if (source == null) {
            throw new IllegalArgumentException("No debug source is registered for " + sourceUri);
        }
        return source;
    }

    private RegisteredSource sourceForClass(String binaryName) {
        if (binaryName.indexOf('/') >= 0) {
            return null;
        }
        RegisteredSource source = this.sourcesByBinaryName.get(binaryName);
        if (source != null) {
            return source;
        }
        int nestedSeparator = binaryName.indexOf('$');
        return nestedSeparator < 0
                ? null
                : this.sourcesByBinaryName.get(binaryName.substring(0, nestedSeparator));
    }

    static URI sourceUri(Types.Source source) {
        if (source == null || source.path == null || source.path.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(source.path);
            return uri.isAbsolute() ? uri : Path.of(source.path).toUri();
        } catch (IllegalArgumentException ignored) {
            return Path.of(source.path).toUri();
        }
    }

    private record RegisteredSource(DebugEngine.Source source, DebuggerTypeScope typeScope) {
        static RegisteredSource parse(DebugEngine.Source source) {
            source.document().binaryNames(); // Validate the source before publishing a registration.
            return new RegisteredSource(source, DebuggerTypeScope.from(source.document()));
        }
        String binaryNameAt(int line) { return source.document().ownerAtLine(line); }
        List<String> binaryNames() { return source.document().binaryNames(); }
    }
}
