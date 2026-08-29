package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerTypeScope;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.SourceType;
import com.microsoft.java.debug.core.protocol.Types;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Source lookup and exact source-line ownership for the Microsoft Java debug adapter. */
final class MicrosoftSourceRegistry implements ISourceLookUpProvider {
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
        RegisteredSource source = sourceForClass(fullyQualifiedName);
        if (source == null) {
            DebugEngine.Source loaded;
            try {
                loaded = this.sourceLoader.load(fullyQualifiedName);
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to resolve debugger source for " + fullyQualifiedName, exception);
            }
            if (loaded != null) {
                register(loaded);
                source = sourceForClass(fullyQualifiedName);
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

    private record RegisteredSource(
            DebugEngine.Source source,
            DebuggerTypeScope typeScope,
            List<TypeRegion> typeRegions
    ) {
        static RegisteredSource parse(DebugEngine.Source source) {
            ASTParser parser = JdtConfiguration.createParser();
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setSource(source.contents().toCharArray());
            parser.setStatementsRecovery(true);
            CompilationUnit unit = (CompilationUnit) parser.createAST(null);
            String parseErrors = Arrays.stream(unit.getProblems())
                    .filter(org.eclipse.jdt.core.compiler.IProblem::isError)
                    .map(problem -> "line " + problem.getSourceLineNumber() + ": " + problem.getMessage())
                    .collect(Collectors.joining("; "));
            if (!parseErrors.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unable to parse debugger source " + source.uri() + ": " + parseErrors
                );
            }

            String packageName = unit.getPackage() == null
                    ? packageName(source.binaryName())
                    : unit.getPackage().getName().getFullyQualifiedName();
            Map<AbstractTypeDeclaration, String> names = new HashMap<>();
            List<TypeRegion> regions = new ArrayList<>();
            unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
                @Override
                public void preVisit(ASTNode node) {
                    if (!(node instanceof AbstractTypeDeclaration declaration) || isLocalType(declaration)) {
                        return;
                    }
                    AbstractTypeDeclaration parent = enclosingNamedType(declaration.getParent());
                    String simpleName = declaration.getName().getIdentifier();
                    String binaryName;
                    if (parent != null) {
                        String parentName = names.get(parent);
                        if (parentName == null) {
                            return;
                        }
                        binaryName = parentName + "$" + simpleName;
                    } else if (simpleBinaryName(source.binaryName()).equals(simpleName)) {
                        binaryName = source.binaryName();
                    } else {
                        binaryName = packageName.isBlank() ? simpleName : packageName + "." + simpleName;
                    }
                    names.put(declaration, binaryName);
                    int firstLine = unit.getLineNumber(declaration.getStartPosition());
                    int lastLine = unit.getLineNumber(
                            declaration.getStartPosition() + Math.max(0, declaration.getLength() - 1)
                    );
                    if (firstLine > 0 && lastLine >= firstLine) {
                        regions.add(new TypeRegion(firstLine, lastLine, binaryName));
                    }
                }
            });
            regions.sort(Comparator.comparingInt(TypeRegion::span));
            return new RegisteredSource(
                    source,
                    DebuggerTypeScope.parse(source),
                    List.copyOf(regions)
            );
        }

        String binaryNameAt(int line) {
            return this.typeRegions.stream()
                    .filter(region -> region.contains(line))
                    .map(TypeRegion::binaryName)
                    .findFirst()
                    .orElse(this.source.binaryName());
        }

        List<String> binaryNames() {
            List<String> result = new ArrayList<>(this.typeRegions.size() + 1);
            result.add(this.source.binaryName());
            this.typeRegions.stream()
                    .map(TypeRegion::binaryName)
                    .filter(name -> !result.contains(name))
                    .forEach(result::add);
            return result;
        }

        private static boolean isLocalType(AbstractTypeDeclaration declaration) {
            for (ASTNode current = declaration.getParent(); current != null; current = current.getParent()) {
                if (current instanceof AbstractTypeDeclaration) {
                    return false;
                }
                if (current instanceof MethodDeclaration
                        || current instanceof Initializer
                        || current instanceof LambdaExpression
                        || current instanceof AnonymousClassDeclaration) {
                    return true;
                }
            }
            return false;
        }

        private static AbstractTypeDeclaration enclosingNamedType(ASTNode node) {
            for (ASTNode current = node; current != null; current = current.getParent()) {
                if (current instanceof AbstractTypeDeclaration declaration) {
                    return declaration;
                }
            }
            return null;
        }

        private static String packageName(String binaryName) {
            String topLevelName = binaryName.substring(0, binaryName.indexOf('$') < 0
                    ? binaryName.length()
                    : binaryName.indexOf('$'));
            int separator = topLevelName.lastIndexOf('.');
            return separator < 0 ? "" : topLevelName.substring(0, separator);
        }

        private static String simpleBinaryName(String binaryName) {
            int packageSeparator = binaryName.lastIndexOf('.');
            int nestedSeparator = binaryName.lastIndexOf('$');
            return binaryName.substring(Math.max(packageSeparator, nestedSeparator) + 1);
        }
    }

    private record TypeRegion(int firstLine, int lastLine, String binaryName) {
        boolean contains(int line) {
            return line >= this.firstLine && line <= this.lastLine;
        }

        int span() {
            return this.lastLine - this.firstLine;
        }
    }
}
