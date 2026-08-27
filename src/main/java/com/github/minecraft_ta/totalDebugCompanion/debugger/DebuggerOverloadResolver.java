package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassType;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.isAssignableName;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.isPrimitive;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.methods;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerJdiMembers.resolveType;

/** Java overload applicability, specificity, and argument conversion for debugger calls. */
final class DebuggerOverloadResolver {
    private DebuggerOverloadResolver() {
    }

    static Method selectMethod(ReferenceType type, String name, List<Value> args, boolean staticOnly) {
        Map<String, ScoredMethod> uniqueMethods = new LinkedHashMap<>();
        methods(type).stream()
                .filter(method -> method.name().equals(name))
                .filter(method -> !staticOnly || method.isStatic())
                .map(method -> compatibility(method, args))
                .filter(scored -> scored.score() >= 0)
                .forEach(scored -> uniqueMethods.putIfAbsent(scored.method().signature(), scored));
        List<ScoredMethod> compatible = uniqueMethods.values().stream()
                .sorted(Comparator.comparingInt(ScoredMethod::score)
                        .thenComparing(scored -> scored.method().isVarArgs())
                        .thenComparing(scored -> scored.method().signature()))
                .toList();
        if (compatible.isEmpty()) return null;
        int bestScore = compatible.getFirst().score();
        List<ScoredMethod> best = compatible.stream()
                .takeWhile(candidate -> candidate.score() == bestScore)
                .toList();
        List<ScoredMethod> mostSpecific = best.stream()
                .filter(candidate -> best.stream().noneMatch(other ->
                        other != candidate && moreSpecific(other.method(), other.directArray(),
                                candidate.method(), candidate.directArray(), type.virtualMachine())))
                .toList();
        if (mostSpecific.size() != 1) {
            throw new IllegalArgumentException("Ambiguous overload for " + type.name() + "." + name);
        }
        return mostSpecific.getFirst().method();
    }

    static boolean moreSpecific(Method first, Method second, VirtualMachine vm) {
        return moreSpecific(first, false, second, false, vm);
    }

    private static boolean moreSpecific(Method first, boolean firstDirectArray,
                                        Method second, boolean secondDirectArray, VirtualMachine vm) {
        List<String> firstParameters = effectiveParameterTypes(first, firstDirectArray);
        List<String> secondParameters = effectiveParameterTypes(second, secondDirectArray);
        if (firstParameters.size() != secondParameters.size()) return false;
        boolean strictlyMoreSpecific = false;
        for (int i = 0; i < firstParameters.size(); i++) {
            String firstType = firstParameters.get(i);
            String secondType = secondParameters.get(i);
            if (firstType.equals(secondType)) continue;
            if (!formalAssignable(firstType, secondType, vm)) return false;
            strictlyMoreSpecific = true;
        }
        return strictlyMoreSpecific;
    }

    private static List<String> effectiveParameterTypes(Method method, boolean directArray) {
        List<String> parameters = method.argumentTypeNames();
        if (!method.isVarArgs() || directArray) return parameters;
        List<String> result = new ArrayList<>(parameters);
        result.set(result.size() - 1, result.getLast().substring(0, result.getLast().length() - 2));
        return result;
    }

    private static boolean formalAssignable(String source, String target, VirtualMachine vm) {
        DebuggerPrimitiveKind sourcePrimitive = DebuggerPrimitiveKind.fromPrimitiveName(source);
        DebuggerPrimitiveKind targetPrimitive = DebuggerPrimitiveKind.fromPrimitiveName(target);
        if (sourcePrimitive != null || targetPrimitive != null) {
            return sourcePrimitive != null && targetPrimitive != null
                    && sourcePrimitive.wideningCostTo(targetPrimitive) >= 0;
        }
        return isAssignableName(source, target, vm);
    }

    private static ScoredMethod compatibility(Method method, List<Value> args) {
        List<String> parameters = method.argumentTypeNames();
        if (!method.isVarArgs() && parameters.size() != args.size()) return new ScoredMethod(method, -1, false);
        if (method.isVarArgs() && args.size() < parameters.size() - 1) return new ScoredMethod(method, -1, false);
        boolean directArray = method.isVarArgs() && args.size() == parameters.size()
                && valueCompatibility(args.getLast(), parameters.getLast()) >= 0;
        int score = method.isVarArgs() && !directArray ? 100 : 0;
        for (int i = 0; i < args.size(); i++) {
            String parameter = parameters.get(Math.min(i, parameters.size() - 1));
            if (method.isVarArgs() && !directArray && i >= parameters.size() - 1) {
                parameter = parameter.substring(0, parameter.length() - 2);
            }
            int current = valueCompatibility(args.get(i), parameter);
            if (current < 0) return new ScoredMethod(method, -1, directArray);
            score += current;
        }
        return new ScoredMethod(method, score, directArray);
    }

    private static int valueCompatibility(Value value, String target) {
        if (value == null) return isPrimitive(target) ? -1 : 20;
        DebuggerPrimitiveKind sourceKind = DebuggerPrimitiveKind.fromValue(value);
        DebuggerPrimitiveKind targetKind = DebuggerPrimitiveKind.fromPrimitiveName(target);
        if (targetKind != null) {
            if (value instanceof PrimitiveValue) {
                return sourceKind == null ? -1 : sourceKind.wideningCostTo(targetKind);
            }
            if (sourceKind != null) {
                int widening = sourceKind.wideningCostTo(targetKind);
                return widening < 0 ? -1 : 10 + widening;
            }
            return -1;
        }
        if (sourceKind != null && value instanceof PrimitiveValue) {
            String boxed = sourceKind.boxedName();
            if (boxed.equals(target)) return 10;
            return isAssignableName(boxed, target, value.virtualMachine()) ? 12 : -1;
        }
        String source = value.type().name();
        return source.equals(target) ? 0 : isAssignableName(source, target, value.virtualMachine()) ? 2 : -1;
    }

    static List<Value> convertArguments(List<Value> args, Method method, RichJavaExpressionEngine.Context context) throws Exception {
        List<Value> converted = new ArrayList<>();
        List<String> parameters = method.argumentTypeNames();
        for (int i = 0; i < args.size(); i++) {
            if (method.isVarArgs() && i >= parameters.size() - 1) break;
            converted.add(convertValue(args.get(i), parameters.get(i), context));
        }
        if (method.isVarArgs()) {
            String arrayType = parameters.getLast();
            if (args.size() == parameters.size()
                    && (args.getLast() == null || valueCompatibility(args.getLast(), arrayType) >= 0)) {
                converted.add(convertValue(args.getLast(), arrayType, context));
            } else {
                converted.add(makeVarargs(args.subList(parameters.size() - 1, args.size()), arrayType, context));
            }
        }
        return converted;
    }

    private static Value makeVarargs(List<Value> args, String arrayTypeName,
                                     RichJavaExpressionEngine.Context context) throws Exception {
        ArrayType arrayType = (ArrayType) resolveType(arrayTypeName, context.vm());
        if (arrayType == null) throw new IllegalArgumentException("Unknown varargs type " + arrayTypeName);
        String component = arrayTypeName.substring(0, arrayTypeName.length() - 2);
        ArrayReference result = arrayType.newInstance(args.size());
        List<Value> values = new ArrayList<>();
        for (Value arg : args) values.add(convertValue(arg, component, context));
        result.setValues(values);
        return result;
    }

    static Value convertValue(Value value, String target,
                               RichJavaExpressionEngine.Context context) throws Exception {
        if (value == null) return null;
        DebuggerPrimitiveKind targetPrimitive = DebuggerPrimitiveKind.fromPrimitiveName(target);
        DebuggerPrimitiveKind sourceKind = DebuggerPrimitiveKind.fromValue(value);
        if (targetPrimitive != null) {
            if (value instanceof PrimitiveValue) return mirrorPrimitive(context.vm(), value, targetPrimitive);
            if (sourceKind != null && value instanceof ObjectReference object) {
                Method unbox = methods(object.referenceType()).stream()
                        .filter(method -> method.name().equals(unboxMethod(sourceKind))
                                && method.argumentTypeNames().isEmpty())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "Unable to unbox " + object.referenceType().name()));
                Value primitive;
                try {
                    primitive = object.invokeMethod(context.thread(), unbox, List.of(),
                            ObjectReference.INVOKE_SINGLE_THREADED);
                } catch (InvocationException exception) {
                    throw RichJavaExpressionEngine.targetException(exception);
                } finally {
                    context.engine().refreshStackFrames(context.thread());
                }
                return mirrorPrimitive(context.vm(), primitive, targetPrimitive);
            }
            throw new IllegalArgumentException("Value of type " + value.type().name()
                    + " cannot be converted to " + target);
        }
        if (sourceKind != null && value instanceof PrimitiveValue) {
            String boxedName = sourceKind.boxedName();
            ClassType boxed = (ClassType) resolveType(boxedName, context.vm());
            if (boxed == null) throw new IllegalArgumentException("Wrapper type is not loaded: " + boxedName);
            Method valueOf = boxed.methodsByName("valueOf").stream()
                    .filter(method -> method.argumentTypeNames().size() == 1
                            && method.argumentTypeNames().getFirst().equals(sourceKind.primitiveName()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Unable to box " + value.type().name()));
            Value boxedValue;
            try {
                boxedValue = boxed.invokeMethod(context.thread(), valueOf, List.of(value),
                        ObjectReference.INVOKE_SINGLE_THREADED);
            } catch (InvocationException exception) {
                throw RichJavaExpressionEngine.targetException(exception);
            } finally {
                context.engine().refreshStackFrames(context.thread());
            }
            if (boxedName.equals(target) || isAssignableName(boxedName, target, context.vm())) return boxedValue;
            throw new IllegalArgumentException("Value of type " + value.type().name()
                    + " cannot be converted to " + target);
        }
        if (!isAssignableName(value.type().name(), target, context.vm()) && !value.type().name().equals(target)) {
            throw new IllegalArgumentException("Value of type " + value.type().name()
                    + " cannot be converted to " + target);
        }
        return value;
    }

    private static String unboxMethod(DebuggerPrimitiveKind kind) {
        return switch (kind) {
            case BOOLEAN -> "booleanValue";
            case BYTE -> "byteValue";
            case SHORT -> "shortValue";
            case CHAR -> "charValue";
            case INT -> "intValue";
            case LONG -> "longValue";
            case FLOAT -> "floatValue";
            case DOUBLE -> "doubleValue";
        };
    }

    static Value mirrorPrimitive(VirtualMachine vm, Value value, DebuggerPrimitiveKind target) {
        if (target == DebuggerPrimitiveKind.BOOLEAN) {
            if (!(value instanceof com.sun.jdi.BooleanValue booleanValue)) {
                throw new IllegalArgumentException("Boolean value required");
            }
            return vm.mirrorOf(booleanValue.booleanValue());
        }
        Number number = RichJavaExpressionEngine.toNumber(value);
        return switch (target) {
            case BYTE -> vm.mirrorOf(number.byteValue());
            case SHORT -> vm.mirrorOf(number.shortValue());
            case CHAR -> vm.mirrorOf((char) number.intValue());
            case INT -> vm.mirrorOf(number.intValue());
            case LONG -> vm.mirrorOf(number.longValue());
            case FLOAT -> vm.mirrorOf(number.floatValue());
            case DOUBLE -> vm.mirrorOf(number.doubleValue());
            case BOOLEAN -> throw new AssertionError(target);
        };
    }

    private record ScoredMethod(Method method, int score, boolean directArray) { }
}
