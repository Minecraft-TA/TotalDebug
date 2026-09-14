package com.github.minecraft_ta.totalDebugCompanion.source;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceLocation;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaAst;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.JavaSymbolResolver;
import com.github.minecraft_ta.totalDebugCompanion.navigation.RuntimeMember;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** One immutable source snapshot. All consumers share its symbol identities, scopes and line mappings. */
public final class SourceDocument {
    private final String binaryName;
    private final String contents;
    private final SourceLineMap lineMap;
    private final SourceVariableNames variableNames;
    private final List<SymbolSpan> symbols;
    private final int[] lineStarts;
    // JDT nodes/bindings are confined to synchronized queries; they never escape this document.
    private CompilationUnit unit;
    private final Map<CodeSymbol, Entry> declarations = new LinkedHashMap<>();

    public SourceDocument(String binaryName, String contents, SourceLineMap lineMap,
                          SourceVariableNames variableNames, List<SymbolSpan> symbols) {
        if (Objects.requireNonNull(binaryName).isBlank()) throw new IllegalArgumentException("Source binary name is blank");
        this.binaryName = binaryName;
        this.contents = Objects.requireNonNull(contents);
        this.lineMap = Objects.requireNonNull(lineMap);
        this.variableNames = Objects.requireNonNull(variableNames);
        this.symbols = symbols.stream().sorted(Comparator.comparingInt(SymbolSpan::offset)).toList();
        this.lineStarts = new int[(int) contents.chars().filter(c -> c == '\n').count() + 1];
        for (int offset = 0, line = 1; offset < contents.length(); offset++) {
            if (contents.charAt(offset) == '\n') this.lineStarts[line++] = offset + 1;
        }
        for (SymbolSpan symbol : this.symbols) {
            if (symbol.offset() > contents.length() - symbol.length()) {
                throw new IllegalArgumentException("Symbol lies outside its source snapshot");
            }
        }
    }

    public SourceDocument(String binaryName, String contents) {
        this(binaryName, contents, SourceLineMap.empty(), SourceVariableNames.empty(), List.of());
    }

    public String binaryName() { return this.binaryName; }
    public String contents() { return this.contents; }
    public SourceLineMap lineMap() { return this.lineMap; }
    public SourceVariableNames variableNames() { return this.variableNames; }
    public List<SymbolSpan> symbols() { return this.symbols; }

    public enum SymbolRole { DECLARATION, REFERENCE, METHOD_PARAMETER, METHOD_LOCAL }

    public record SymbolSpan(CodeSymbol symbol, SymbolRole role, int offset, int length) {
        public SymbolSpan {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(role);
            if ((role == SymbolRole.METHOD_PARAMETER || role == SymbolRole.METHOD_LOCAL)
                    && !(symbol instanceof CodeSymbol.MethodSymbol)) {
                throw new IllegalArgumentException("A variable declaration must identify its owning method");
            }
            if (offset < 0 || length < 1) throw new IllegalArgumentException("Invalid symbol range");
        }
    }

    public enum Kind { OCCURRENCE, DECLARATION, CONSTRUCT, CLASS }

    public record Resolution(int start, int length, int caret, Kind kind) {
        public Resolution {
            Objects.requireNonNull(kind);
            if (start < 0 || length < 1 || caret < start || caret >= start + length) {
                throw new IllegalArgumentException("Invalid source resolution");
            }
        }
    }

    public record MethodScope(CodeSymbol.MethodSymbol method, int firstLine, int lastLine) {}

    /** Exact visible declarations only. Callers requesting a method body must not receive a class fallback. */
    public synchronized Optional<Resolution> declaration(ReferenceLocation location) {
        initialize();
        Entry entry = entry(location);
        return entry == null || entry.kind != Kind.DECLARATION ? Optional.empty() : Optional.of(entry.resolution());
    }

    public synchronized Resolution navigate(RuntimeMember member) {
        initialize();
        Entry entry = switch (member) {
            case RuntimeMember.Method method -> this.declarations.get(
                    new CodeSymbol.MethodSymbol(method.ownerClassName(), method.name(), method.descriptor()));
            case RuntimeMember.Field field -> this.declarations.entrySet().stream()
                    .filter(e -> e.getKey() instanceof CodeSymbol.FieldSymbol symbol
                            && symbol.ownerClassName().equals(field.ownerClassName()) && symbol.name().equals(field.name()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
        };
        return entry == null ? classFallback(member.ownerClassName()) : entry.resolution();
    }

    public synchronized Resolution usage(ReferenceLocation location, ReferenceQuery query) {
        initialize();
        Objects.requireNonNull(query);
        if (location.site() instanceof ReferenceLocation.Method method && method.name().equals("<clinit>")) {
            Entry owner = owner(location.className());
            for (ASTNode scope : initializers(owner.node, true)) {
                OptionalInt offset = occurrence(scope, query);
                if (offset.isPresent()) return occurrenceResolution(offset.getAsInt());
            }
            return classFallback(location.className());
        }
        Entry entry = entry(location);
        if (entry == null) return classFallback(location.className());
        if (entry.node instanceof MethodDeclaration constructor && constructor.isConstructor() && constructor.getBody() != null) {
            var body = constructor.getBody();
            OptionalInt bodyOffset = occurrence(body, query);
            if (bodyOffset.isPresent()) return occurrenceResolution(bodyOffset.getAsInt());
            // A this(...) constructor delegates initialization to the target constructor.
            if (body.statements().isEmpty() || !(body.statements().getFirst() instanceof ConstructorInvocation)) {
                for (ASTNode scope : initializers(owner(location.className()).node, false)) {
                    OptionalInt initializerOffset = occurrence(scope, query);
                    if (initializerOffset.isPresent()) return occurrenceResolution(initializerOffset.getAsInt());
                }
            }
        }
        OptionalInt offset = occurrence(entry.node, query);
        if (offset.isPresent()) return occurrenceResolution(offset.getAsInt());
        // Usage navigation traditionally anchors a containing method at its declaration start.
        return new Resolution(entry.node.getStartPosition(), entry.node.getLength(),
                entry.node instanceof MethodDeclaration ? entry.node.getStartPosition() : entry.caret, entry.kind);
    }

    public synchronized Resolution classFallback(String owner) {
        initialize();
        Entry entry = owner(owner);
        return new Resolution(entry.node.getStartPosition(), entry.node.getLength(), entry.caret, Kind.CLASS);
    }

    public synchronized String ownerAtLine(int line) {
        initialize();
        lineOffset(line);
        return this.declarations.entrySet().stream()
                .filter(e -> e.getKey() instanceof CodeSymbol.ClassSymbol
                        && line >= lineAt(e.getValue().node.getStartPosition())
                        && line <= lineAt(e.getValue().node.getStartPosition() + e.getValue().node.getLength() - 1))
                .min(Comparator.comparingInt(e -> e.getValue().node.getLength()))
                .map(e -> e.getKey().ownerClassName()).orElse(this.binaryName);
    }

    public synchronized List<String> binaryNames() {
        initialize();
        var names = new LinkedHashSet<String>();
        names.add(this.binaryName);
        this.declarations.keySet().stream().filter(CodeSymbol.ClassSymbol.class::isInstance)
                .map(CodeSymbol::ownerClassName).forEach(names::add);
        return List.copyOf(names);
    }

    public synchronized Optional<MethodScope> methodAtLine(int line) {
        initialize();
        return this.declarations.entrySet().stream()
                .filter(e -> e.getKey() instanceof CodeSymbol.MethodSymbol && e.getValue().kind == Kind.DECLARATION
                        && e.getValue().node instanceof MethodDeclaration && lineAt(e.getValue().caret) == line)
                .map(e -> new MethodScope((CodeSymbol.MethodSymbol) e.getKey(), line,
                        lineAt(e.getValue().node.getStartPosition() + e.getValue().node.getLength() - 1)))
                .findFirst();
    }

    public int lineOffset(int line) {
        if (line < 1 || line > this.lineStarts.length) throw new IllegalArgumentException("Source has no line " + line);
        return this.lineStarts[line - 1];
    }

    public int lineAt(int offset) {
        if (offset < 0 || offset > this.contents.length()) throw new IllegalArgumentException("Invalid source offset");
        int index = Arrays.binarySearch(this.lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    public synchronized String packageName() {
        initialize();
        return this.unit.getPackage() == null ? "" : this.unit.getPackage().getName().getFullyQualifiedName();
    }

    public synchronized List<String> imports() {
        initialize();
        var imports = new ArrayList<String>();
        for (Object value : this.unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) value;
            imports.add((declaration.isStatic() ? "static " : "") + declaration.getName().getFullyQualifiedName()
                    + (declaration.isOnDemand() ? ".*" : ""));
        }
        return List.copyOf(imports);
    }

    private void initialize() {
        if (this.unit != null) return;
        CompilationUnit parsed;
        if (this.symbols.isEmpty() && CompanionClassIndex.isOpen()) {
            parsed = JavaAst.parse(this.binaryName, this.contents);
        } else {
            ASTParser parser = JdtConfiguration.createParser();
            parser.setSource(this.contents.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setStatementsRecovery(true);
            parsed = (CompilationUnit) parser.createAST(null);
        }
        // Syntax errors are distinct from a valid class whose synthetic methods were omitted.
        for (var problem : parsed.getProblems()) {
            if (problem.isError() && (problem.getID() & org.eclipse.jdt.core.compiler.IProblem.Syntax) != 0) {
                throw new IllegalArgumentException("Unable to parse source " + this.binaryName + ": " + problem.getMessage());
            }
        }
        if (parsed.types().isEmpty()) throw new IllegalArgumentException("Source has no Java type declaration");
        this.unit = parsed;
        for (SymbolSpan span : this.symbols) {
            if (span.role() != SymbolRole.DECLARATION) continue;
            ASTNode node = NodeFinder.perform(parsed, span.offset(), span.length());
            while (node != null && !declarationNode(node, span.symbol())) node = node.getParent();
            if (node != null) add(span.symbol(), node, span.offset());
        }
        for (Object declaration : parsed.types()) {
            if (declaration instanceof AbstractTypeDeclaration type) {
                String simple = type.getName().getIdentifier();
                String rootSimple = this.binaryName.substring(Math.max(this.binaryName.lastIndexOf('.'), this.binaryName.lastIndexOf('$')) + 1);
                String name = simple.equals(rootSimple) || simple.equals(this.binaryName.substring(this.binaryName.lastIndexOf('.') + 1))
                        ? this.binaryName : (parsed.getPackage() == null ? "" : parsed.getPackage().getName().getFullyQualifiedName() + '.') + simple;
                indexType(type, name);
            }
        }
        indexLambdas();
    }

    private void indexLambdas() {
        Map<CodeSymbol, LinkedHashSet<LambdaExpression>> candidates = new LinkedHashMap<>();
        for (SymbolSpan span : this.symbols) {
            if (span.role() != SymbolRole.METHOD_PARAMETER && span.role() != SymbolRole.METHOD_LOCAL) continue;
            ASTNode variable = NodeFinder.perform(this.unit, span.offset(), span.length());
            while (variable instanceof SimpleName) variable = variable.getParent();
            if (!(variable instanceof VariableDeclaration)) continue;
            ASTNode parent = variable.getParent();
            if (span.role() == SymbolRole.METHOD_LOCAL) {
                while (parent != null && !(parent instanceof LambdaExpression) && !(parent instanceof MethodDeclaration)
                        && !(parent instanceof AbstractTypeDeclaration) && !(parent instanceof AnonymousClassDeclaration)) {
                    parent = parent.getParent();
                }
            }
            if (parent instanceof LambdaExpression lambda && (span.role() == SymbolRole.METHOD_LOCAL
                    || lambda.parameters().contains(variable))) {
                candidates.computeIfAbsent(span.symbol(), ignored -> new LinkedHashSet<>()).add(lambda);
            }
        }
        candidates.forEach((method, lambdas) -> {
            if (lambdas.size() == 1) {
                LambdaExpression lambda = lambdas.iterator().next();
                this.declarations.putIfAbsent(method, new Entry(lambda, lambda.getStartPosition(), Kind.CONSTRUCT));
            }
        });
    }

    private void indexType(AbstractTypeDeclaration type, String name) {
        CodeSymbol.ClassSymbol identity = this.declarations.entrySet().stream()
                .filter(e -> e.getValue().node == type && e.getKey() instanceof CodeSymbol.ClassSymbol)
                .map(e -> (CodeSymbol.ClassSymbol) e.getKey()).findFirst().orElse(new CodeSymbol.ClassSymbol(name));
        this.declarations.putIfAbsent(identity, new Entry(type, type.getName().getStartPosition(), Kind.DECLARATION));
        for (Object value : type.bodyDeclarations()) {
            if (value instanceof AbstractTypeDeclaration nested) {
                indexType(nested, identity.className() + '$' + nested.getName().getIdentifier());
            } else if (value instanceof MethodDeclaration method) {
                addBinding(method.resolveBinding(), method, method.getName().getStartPosition());
            } else if (value instanceof AnnotationTypeMemberDeclaration method) {
                addBinding(method.resolveBinding(), method, method.getName().getStartPosition());
            } else if (value instanceof FieldDeclaration field) {
                for (Object fragment : field.fragments()) {
                    VariableDeclarationFragment variable = (VariableDeclarationFragment) fragment;
                    addBinding(variable.resolveBinding(), field, variable.getName().getStartPosition());
                }
            }
        }
        if (type instanceof EnumDeclaration enumeration) {
            for (Object value : enumeration.enumConstants()) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) value;
                addBinding(constant.resolveVariable(), constant, constant.getName().getStartPosition());
            }
        }
        if (type instanceof RecordDeclaration record && record.resolveBinding() != null) {
            for (IVariableBinding field : record.resolveBinding().getDeclaredFields()) {
                for (Object value : record.recordComponents()) {
                    SingleVariableDeclaration component = (SingleVariableDeclaration) value;
                    if (component.getName().getIdentifier().equals(field.getName())) {
                        addBinding(field, component, component.getName().getStartPosition());
                    }
                }
            }
        }
    }

    private void addBinding(IBinding binding, ASTNode node, int caret) {
        if (binding == null) return;
        CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(binding);
        if (symbol != null && !this.declarations.containsKey(symbol)) add(symbol, node, caret);
    }

    private void add(CodeSymbol symbol, ASTNode node, int caret) {
        boolean component = node instanceof SingleVariableDeclaration && node.getParent() instanceof RecordDeclaration;
        this.declarations.put(symbol, new Entry(node, caret, component ? Kind.CONSTRUCT : Kind.DECLARATION));
        if (symbol instanceof CodeSymbol.MethodSymbol method && node.getParent() instanceof AnonymousClassDeclaration anonymous) {
            // The emitted method identifies this anonymous class without guessing compiler numbering.
            this.declarations.putIfAbsent(new CodeSymbol.ClassSymbol(method.ownerClassName()),
                    new Entry(anonymous, anonymous.getStartPosition(), Kind.DECLARATION));
        }
        if (component && symbol instanceof CodeSymbol.FieldSymbol field) {
            this.declarations.putIfAbsent(new CodeSymbol.MethodSymbol(field.ownerClassName(), field.name(), "()" + field.descriptor()),
                    new Entry(node, caret, Kind.CONSTRUCT));
        }
    }

    private static boolean declarationNode(ASTNode node, CodeSymbol symbol) {
        return switch (symbol) {
            case CodeSymbol.ClassSymbol ignored -> node instanceof AbstractTypeDeclaration || node instanceof AnonymousClassDeclaration;
            case CodeSymbol.MethodSymbol ignored -> node instanceof MethodDeclaration || node instanceof AnnotationTypeMemberDeclaration;
            case CodeSymbol.FieldSymbol ignored -> node instanceof FieldDeclaration || node instanceof EnumConstantDeclaration
                    || node instanceof SingleVariableDeclaration && node.getParent() instanceof RecordDeclaration;
        };
    }

    private Entry entry(ReferenceLocation location) {
        CodeSymbol symbol = switch (location.site()) {
            case ReferenceLocation.ClassDeclaration ignored -> new CodeSymbol.ClassSymbol(location.className());
            case ReferenceLocation.Method method -> new CodeSymbol.MethodSymbol(location.className(), method.name(), method.descriptor());
            case ReferenceLocation.Field field -> new CodeSymbol.FieldSymbol(location.className(), field.name(), field.descriptor());
            case ReferenceLocation.RecordComponent component -> new CodeSymbol.FieldSymbol(location.className(), component.name(), component.descriptor());
        };
        Entry entry = this.declarations.get(symbol);
        if (location.site() instanceof ReferenceLocation.RecordComponent) {
            return entry != null && entry.node instanceof SingleVariableDeclaration
                    ? new Entry(entry.node, entry.caret, Kind.DECLARATION) : null;
        }
        return entry;
    }

    private Entry owner(String name) {
        for (String candidate = name; candidate != null;) {
            Entry entry = this.declarations.get(new CodeSymbol.ClassSymbol(candidate));
            if (entry != null) return entry;
            int separator = candidate.lastIndexOf('$');
            candidate = separator < 0 ? null : candidate.substring(0, separator);
        }
        return this.declarations.entrySet().stream().filter(e -> e.getKey() instanceof CodeSymbol.ClassSymbol)
                .map(Map.Entry::getValue).findFirst().orElseThrow(() -> new IllegalStateException("Source has no navigable type"));
    }

    private OptionalInt occurrence(ASTNode scope, ReferenceQuery query) {
        int tokenOffset = -1;
        for (SymbolSpan span : this.symbols) {
            if (span.role() == SymbolRole.REFERENCE && span.symbol().referenceQuery().equals(query)
                    && belongsTo(scope, NodeFinder.perform(this.unit, span.offset(), span.length()))) {
                tokenOffset = span.offset();
                break;
            }
        }
        MatchVisitor visitor = new MatchVisitor(scope, query);
        scope.accept(visitor);
        int offset = visitor.offset < 0 ? tokenOffset : tokenOffset < 0 ? visitor.offset : Math.min(tokenOffset, visitor.offset);
        return offset < 0 ? OptionalInt.empty() : OptionalInt.of(offset);
    }

    private static boolean belongsTo(ASTNode scope, ASTNode node) {
        for (ASTNode current = node; current != null; current = current.getParent()) {
            if (current == scope) return true;
            if (current instanceof LambdaExpression) return false;
            if (current instanceof AbstractTypeDeclaration || current instanceof AnonymousClassDeclaration) return false;
            if ((scope instanceof AbstractTypeDeclaration || scope instanceof AnonymousClassDeclaration)
                    && current instanceof BodyDeclaration) return false;
        }
        return false;
    }

    private static List<ASTNode> initializers(ASTNode node, boolean staticScope) {
        if (!(node instanceof AbstractTypeDeclaration type)) return List.of();
        var scopes = new ArrayList<ASTNode>();
        if (staticScope && type instanceof EnumDeclaration enumeration) {
            for (Object constant : enumeration.enumConstants()) scopes.add((ASTNode) constant);
        }
        for (Object value : type.bodyDeclarations()) {
            if (value instanceof Initializer initializer && Modifier.isStatic(initializer.getModifiers()) == staticScope) {
                scopes.add(initializer.getBody());
            } else if (value instanceof FieldDeclaration field && (Modifier.isStatic(field.getModifiers())
                    || type instanceof TypeDeclaration owner && owner.isInterface() || type instanceof AnnotationTypeDeclaration) == staticScope) {
                for (Object fragment : field.fragments()) {
                    var initializer = ((VariableDeclarationFragment) fragment).getInitializer();
                    if (initializer != null && !(initializer instanceof LambdaExpression)
                            && (!staticScope || executableInitializer(field, initializer))) scopes.add(initializer);
                }
            }
        }
        return scopes;
    }

    private static boolean executableInitializer(FieldDeclaration field, Expression initializer) {
        // ConstantValue fields have no <clinit> instructions. With an unbound AST, avoid
        // claiming a constant-capable final field unless its initializer visibly executes code.
        boolean finalField = Modifier.isFinal(field.getModifiers())
                || field.getParent() instanceof AnnotationTypeDeclaration
                || field.getParent() instanceof TypeDeclaration type && type.isInterface();
        String typeName = field.getType().toString();
        if (!finalField || !(field.getType() instanceof PrimitiveType
                || typeName.equals("String") || typeName.equals("java.lang.String"))) return true;
        boolean[] executes = {false};
        initializer.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) { executes[0] = true; return false; }
            @Override public boolean visit(ClassInstanceCreation node) { executes[0] = true; return false; }
        });
        return executes[0];
    }

    private Resolution occurrenceResolution(int offset) {
        int length = this.symbols.stream().filter(span -> span.offset() == offset)
                .mapToInt(SymbolSpan::length).findFirst().orElseGet(() -> {
                    ASTNode node = NodeFinder.perform(this.unit, offset, 1);
                    return node != null && node.getStartPosition() == offset ? node.getLength() : 1;
                });
        return new Resolution(offset, length, offset, Kind.OCCURRENCE);
    }

    private record Entry(ASTNode node, int caret, Kind kind) {
        Resolution resolution() { return new Resolution(node.getStartPosition(), node.getLength(), caret, kind); }
    }

    private static final class MatchVisitor extends ASTVisitor {
        private final ASTNode scope;
        private final ReferenceQuery query;
        private int offset = -1;
        MatchVisitor(ASTNode scope, ReferenceQuery query) { this.scope = scope; this.query = query; }
        @Override public boolean preVisit2(ASTNode node) { return this.offset < 0 && belongsTo(this.scope, node); }
        @Override public boolean visit(SimpleName name) {
            if (!isDeclarationName(name) && matches(name.resolveBinding())) this.offset = name.getStartPosition();
            return this.offset < 0;
        }
        @Override public boolean visit(ClassInstanceCreation creation) {
            if (matches(creation.resolveConstructorBinding())) this.offset = creation.getType().getStartPosition();
            return this.offset < 0;
        }
        @Override public boolean visit(ConstructorInvocation invocation) {
            if (matches(invocation.resolveConstructorBinding())) this.offset = invocation.getStartPosition();
            return this.offset < 0;
        }
        @Override public boolean visit(SuperConstructorInvocation invocation) {
            if (matches(invocation.resolveConstructorBinding())) this.offset = invocation.getStartPosition();
            return this.offset < 0;
        }
        @Override public boolean visit(EnumConstantDeclaration declaration) {
            if (matches(declaration.resolveConstructorBinding())) this.offset = declaration.getName().getStartPosition();
            return this.offset < 0;
        }
        @Override public boolean visit(StringLiteral literal) {
            if (this.query instanceof ReferenceQuery.StringLiteralReference target && literal.getLiteralValue().equals(target.value())) this.offset = literal.getStartPosition();
            return this.offset < 0;
        }
        @Override public boolean visit(TextBlock literal) {
            if (this.query instanceof ReferenceQuery.StringLiteralReference target && literal.getLiteralValue().equals(target.value())) this.offset = literal.getStartPosition();
            return this.offset < 0;
        }
        private boolean matches(IBinding binding) {
            if (binding == null) return false;
            CodeSymbol symbol = JavaSymbolResolver.trySymbolForBinding(binding);
            return symbol != null && symbol.referenceQuery().equals(this.query);
        }
        private static boolean isDeclarationName(SimpleName name) {
            return switch (name.getParent()) {
                case AbstractTypeDeclaration declaration -> declaration.getName() == name;
                case MethodDeclaration declaration -> declaration.getName() == name;
                case VariableDeclaration declaration -> declaration.getName() == name;
                case EnumConstantDeclaration declaration -> declaration.getName() == name;
                case AnnotationTypeMemberDeclaration declaration -> declaration.getName() == name;
                default -> false;
            };
        }
    }
}
