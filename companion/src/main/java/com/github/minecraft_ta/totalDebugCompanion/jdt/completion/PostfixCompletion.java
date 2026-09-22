package com.github.minecraft_ta.totalDebugCompanion.jdt.completion;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.CompilationUnitImpl;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import com.github.minecraft_ta.totalDebugCompanion.naming.JadLikeNameGenerator;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnMemberAccess;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnQualifiedNameReference;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.Literal;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.lookup.ArrayBinding;
import org.eclipse.jdt.internal.compiler.lookup.CaptureBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.eclipse.jdt.internal.compiler.lookup.VariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.WildcardBinding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Type-aware expression transformations. Uses the same bindings, imports and edits as ordinary completion. */
final class PostfixCompletion {
    private static final List<String> KEYS = List.of("var", "for", "fori", "forr", "if", "else", "nn", "null", "not", "logln", "sout");
    record Result(String token, List<CompletionItem> items) { }
    private record Receiver(int start, int end, TypeBinding type, boolean stable) { }

    static Result find(ICompilationUnit unit, CustomCompletionRequestor requestor, CompletionEdits imports) {
        String token = CompletionLabels.text(requestor.getContext().getToken());
        if (!(requestor.getContext() instanceof InternalCompletionContext context) || context.isInJavadoc()) return new Result(token, List.of());
        try {
            String source = unit.getSource();
            int tokenStart = context.getOffset();
            while (tokenStart > 0 && Character.isJavaIdentifierPart(source.charAt(tokenStart - 1))) tokenStart--;
            String prefix = source.substring(tokenStart, context.getOffset());
            if (prefix.isEmpty() || KEYS.stream().noneMatch(key -> key.startsWith(prefix))) return new Result(token, List.of());
            if (context.getCompletionNode() instanceof CompletionOnQualifiedTypeReference) {
                var recovered = withTerminator(unit, source, context.getOffset(), requestor);
                if (recovered != null) context = recovered;
            }
            var receiver = receiver(context);
            if (receiver == null && tokenStart > 0 && source.charAt(tokenStart - 1) == '.')
                receiver = numericReceiver(source, tokenStart - 1);
            if (receiver == null || receiver.type == null || !receiver.type.isValidBinding() || receiver.type.id == TypeIds.T_void)
                return new Result(token, List.of());
            token = source.substring(tokenStart, context.getOffset());
            if (token.isEmpty()) return new Result(token, List.of());
            int end = context.getOffset();
            while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
            var syntax = syntax(source, receiver.start, end);
            boolean statement = context.getCompletionNodeParent() == null
                    && (syntax.before == ITerminalSymbols.TokenNameLBRACE || syntax.before == ITerminalSymbols.TokenNameSEMICOLON
                    || syntax.before == ITerminalSymbols.TokenNameRBRACE)
                    && (syntax.after == ITerminalSymbols.TokenNameSEMICOLON || syntax.after == ITerminalSymbols.TokenNameRBRACE
                    || syntax.after == ITerminalSymbols.TokenNameEOF
                    || source.substring(end, syntax.afterStart).contains("\n") && startsStatement(syntax.after));
            int statementEnd = syntax.after == ITerminalSymbols.TokenNameSEMICOLON ? syntax.afterEnd : end;
            String expression = SnippetCompletionAdapter.escapeLiteral(expression(source, receiver, tokenStart));
            String indent = SnippetCompletionProposalProvider.indentationAt(unit, receiver.start);
            var type = receiver.type;
            boolean bool = type.id == TypeIds.T_boolean || type.id == TypeIds.T_JavaLangBoolean;
            boolean integral = switch (type.id) {
                case TypeIds.T_byte, TypeIds.T_short, TypeIds.T_int, TypeIds.T_long, TypeIds.T_char,
                        TypeIds.T_JavaLangByte, TypeIds.T_JavaLangShort, TypeIds.T_JavaLangInteger,
                        TypeIds.T_JavaLangLong, TypeIds.T_JavaLangCharacter -> true;
                default -> false;
            };
            var iterable = type.findSuperTypeOriginatingFrom(TypeIds.T_JavaLangIterable, false);
            boolean indexed = type.isArrayType() || integral || type.findSuperTypeOriginatingFrom(TypeIds.T_JavaUtilList, false) != null;
            var items = new ArrayList<CompletionItem>();
            for (String key : KEYS) {
                if (!key.startsWith(token)) continue;
                if (key.equals("not") ? !bool : !key.equals("var") && !statement) continue;
                if ((key.equals("if") || key.equals("else")) && !bool) continue;
                if ((key.equals("nn") || key.equals("null")) && type.isBaseType()) continue;
                if (key.equals("for") && !type.isArrayType() && iterable == null) continue;
                if ((key.equals("fori") || key.equals("forr")) && !indexed) continue;
                var item = new CompletionItem(requestor);
                var names = new JadLikeNameGenerator();
                names.reserve(syntax.names);
                String body = " {\n" + indent + "\t${0}\n" + indent + "}";
                String replacement;
                String description;
                switch (key) {
                    case "var" -> {
                        String variable = "${1:" + names.next("name") + "}";
                        String declaration = importType(type, imports, item) + " " + variable + " = " + expression + ";";
                        if (statement) replacement = declaration;
                        else {
                            int statementStart = imports.localDeclarationStart(receiver.start);
                            if (statementStart < 0) continue;
                            item.addTextEdit(new CustomTextEdit(new Range(statementStart, 0), declaration + "\n" + indent));
                            replacement = variable;
                        }
                        description = "introduce variable";
                    }
                    case "for" -> {
                        TypeBinding element = type instanceof ArrayBinding array ? array.elementsType()
                                : iterable instanceof ParameterizedTypeBinding parameterized && parameterized.arguments != null
                                ? parameterized.arguments[0] : null;
                        String elementType = importType(element, imports, item);
                        String name = names.next(elementType);
                        replacement = "for (" + elementType + " ${1:" + name + "} : " + expression + ")" + body;
                        description = "iterate elements";
                    }
                    case "fori", "forr" -> {
                        String index = names.next("int");
                        String variable = "${1:" + index + "}";
                        String bound = integral ? expression : expression + (type.isArrayType() ? ".length" : ".size()");
                        String indexType = type.id == TypeIds.T_long || type.id == TypeIds.T_JavaLangLong ? "long" : "int";
                        if (key.equals("forr")) {
                            replacement = "for (" + indexType + " " + variable + " = " + bound + (integral ? "" : " - 1")
                                    + "; " + variable + (integral ? " > 0; " : " >= 0; ") + variable + "--)" + body;
                        } else {
                            // A method call or other computed receiver must not be reevaluated by every loop condition.
                            String limit = receiver.stable ? bound : names.next("limit");
                            replacement = "for (" + indexType + " " + variable + " = 0"
                                    + (receiver.stable ? "" : ", " + limit + " = " + bound)
                                    + "; " + variable + " < " + limit + "; " + variable + "++)" + body;
                        }
                        description = key.equals("fori") ? "iterate indices" : "iterate in reverse";
                    }
                    case "if", "else" -> {
                        replacement = "if (" + (key.equals("else") ? "!" + expression : expression) + ")" + body;
                        description = key.equals("if") ? "if true" : "if false";
                    }
                    case "nn", "null" -> {
                        replacement = "if (" + expression + (key.equals("nn") ? " != null)" : " == null)") + body;
                        description = key.equals("nn") ? "if not null" : "if null";
                    }
                    case "not" -> {
                        replacement = "!" + expression + "${0}";
                        description = "negate";
                    }
                    default -> {
                        replacement = "logln(" + expression + ");${0}";
                        description = "log value";
                    }
                }
                if (statement && !key.equals("not") && syntax.after == ITerminalSymbols.TokenNameSEMICOLON)
                    replacement += SnippetCompletionAdapter.escapeLiteral(source.substring(end, syntax.afterStart));
                item.setPresentation(key, "  " + description, "");
                item.setKind(CompletionItemKind.SNIPPET);
                item.addTextEdit(new CustomTextEdit(new Range(receiver.start,
                        (statement && !key.equals("not") ? statementEnd : end) - receiver.start), replacement));
                items.add(item);
            }
            return new Result(token, items);
        } catch (JavaModelException failure) { throw new IllegalStateException("Cannot read postfix completion source", failure); }
    }

    private static String importType(TypeBinding type, CompletionEdits imports, CompletionItem item) {
        if (type != null && type.id == TypeIds.T_null) type = null;
        if (type instanceof CaptureBinding capture) type = capture.upperBound();
        if (type instanceof WildcardBinding wildcard) type = wildcard.boundKind == Wildcard.EXTENDS ? wildcard.bound : null;
        String signature = type == null ? "Ljava.lang.Object;" : new String(CompletionTypes.uncapture(type).genericTypeSignature()).replace('/', '.');
        return imports.importType(signature, item);
    }

    /** A following statement can make recovery mistake an unfinished member for a type declaration. */
    private static InternalCompletionContext withTerminator(ICompilationUnit unit, String source, int offset,
                                                            CustomCompletionRequestor parent) throws JavaModelException {
        var context = new InternalCompletionContext[1];
        var request = new CompletionRequestor() {
            @Override public void accept(CompletionProposal proposal) { }
            @Override public void acceptContext(CompletionContext found) {
                if (found instanceof InternalCompletionContext internal) context[0] = internal;
            }
        };
        request.setRequireExtendedContext(true);
        var copy = new CompilationUnitImpl(unit.getElementName(), source.substring(0, offset) + ";" + source.substring(offset));
        copy.codeComplete(offset, request, new NullProgressMonitor() {
            @Override public boolean isCanceled() { return parent.isCanceled(); }
        });
        return context[0];
    }

    private static boolean startsStatement(int token) {
        return switch (token) {
            case ITerminalSymbols.TokenNameIdentifier, ITerminalSymbols.TokenNameif, ITerminalSymbols.TokenNamefor,
                    ITerminalSymbols.TokenNamewhile, ITerminalSymbols.TokenNamedo, ITerminalSymbols.TokenNamereturn,
                    ITerminalSymbols.TokenNamethrow, ITerminalSymbols.TokenNametry, ITerminalSymbols.TokenNameswitch,
                    ITerminalSymbols.TokenNameboolean, ITerminalSymbols.TokenNamebyte, ITerminalSymbols.TokenNamechar,
                    ITerminalSymbols.TokenNameshort, ITerminalSymbols.TokenNameint, ITerminalSymbols.TokenNamelong,
                    ITerminalSymbols.TokenNamefloat, ITerminalSymbols.TokenNamedouble, ITerminalSymbols.TokenNamefinal,
                    ITerminalSymbols.TokenNameclass, ITerminalSymbols.TokenNamenew -> true;
            default -> false;
        };
    }

    private static String expression(String source, Receiver receiver, int tokenStart) {
        var scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
        scanner.setSource(source.toCharArray());
        scanner.resetTo(receiver.end, tokenStart - 1);
        try {
            if (scanner.getNextToken() == ITerminalSymbols.TokenNameDOT) {
                int dot = scanner.getCurrentTokenStartPosition();
                return source.substring(receiver.start, dot) + source.substring(dot + 1, tokenStart);
            }
        } catch (InvalidInputException ignored) { }
        return source.substring(receiver.start, receiver.end);
    }

    /** Java lexes 10.fori as a floating literal followed by a name; recover the integer before its postfix dot. */
    private static Receiver numericReceiver(String source, int dot) {
        var scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
        scanner.setSource(source.toCharArray());
        scanner.resetTo(0, dot - 1);
        Receiver last = null;
        try {
            int token;
            while ((token = scanner.getNextToken()) != ITerminalSymbols.TokenNameEOF) {
                last = switch (token) {
                    case ITerminalSymbols.TokenNameIntegerLiteral, ITerminalSymbols.TokenNameLongLiteral ->
                            new Receiver(scanner.getCurrentTokenStartPosition(), scanner.getCurrentTokenEndPosition() + 1,
                                    token == ITerminalSymbols.TokenNameLongLiteral ? TypeBinding.LONG : TypeBinding.INT, true);
                    default -> null;
                };
            }
        } catch (InvalidInputException ignored) { return null; }
        return last != null && last.end == dot ? last : null;
    }

    private static Receiver receiver(InternalCompletionContext context) {
        if (context.getCompletionNode() instanceof CompletionOnMemberAccess member) {
            var expression = member.receiver;
            if (expression instanceof NameReference name && name.isTypeAccess()) return null;
            return new Receiver(expression.sourceStart, expression.sourceEnd + 1, expression.resolvedType, stable(expression));
        }
        if (context.getCompletionNode() instanceof CompletionOnQualifiedNameReference name && name.binding instanceof VariableBinding variable) {
            return new Receiver(name.sourceStart, (int) name.sourcePositions[name.tokens.length - 1] + 1, variable.type, true);
        }
        return null;
    }

    private static boolean stable(Expression expression) {
        return expression instanceof NameReference || expression instanceof ThisReference || expression instanceof Literal
                || expression instanceof FieldReference field && stable(field.receiver);
    }

    private record Syntax(int before, int after, int afterStart, int afterEnd, Set<String> names) { }

    private static Syntax syntax(String source, int start, int end) {
        var scanner = ToolFactory.createScanner(false, false, false, JdtConfiguration.JAVA_VERSION);
        scanner.setSource(source.toCharArray());
        int before = ITerminalSymbols.TokenNameEOF, after = ITerminalSymbols.TokenNameEOF, afterStart = source.length(), afterEnd = end;
        Set<String> names = new HashSet<>();
        try {
            int token;
            while ((token = scanner.getNextToken()) != ITerminalSymbols.TokenNameEOF) {
                if (token == ITerminalSymbols.TokenNameIdentifier) names.add(new String(scanner.getCurrentTokenSource()));
                if (scanner.getCurrentTokenEndPosition() < start) before = token;
                if (scanner.getCurrentTokenStartPosition() >= end && after == ITerminalSymbols.TokenNameEOF) {
                    after = token;
                    afterStart = scanner.getCurrentTokenStartPosition();
                    afterEnd = scanner.getCurrentTokenEndPosition() + 1;
                }
            }
        } catch (InvalidInputException ignored) { }
        return new Syntax(before, after, afterStart, afterEnd, names);
    }

}
