package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

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
import com.github.minecraft_ta.totalDebugCompanion.debugger.expression.JavaExpressionEvaluator.EvalValue;

import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.isAssignableName;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.isPrimitive;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.methods;
import static com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerJdiMembers.resolveType;

/** Java overload applicability, specificity, and argument conversion for debugger calls. */
final class DebuggerOverloadResolver {
    private DebuggerOverloadResolver() {
    }

    static Method selectMethod(ReferenceType type, String name, List<EvalValue> args, boolean staticOnly) {
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

    private static ScoredMethod compatibility(Method method, List<EvalValue> args) {
        List<String> parameters = method.argumentTypeNames();
        if (!method.isVarArgs() && parameters.size() != args.size()) return new ScoredMethod(method, -1, false);
        if (method.isVarArgs() && args.size() < parameters.size() - 1) return new ScoredMethod(method, -1, false);
        boolean directArray = method.isVarArgs() && args.size() == parameters.size()
                && valueCompatibility(args.getLast(), parameters.getLast(), method.virtualMachine()) >= 0;
        int score = method.isVarArgs() && !directArray ? 100 : 0;
        for (int i = 0; i < args.size(); i++) {
            String parameter = parameters.get(Math.min(i, parameters.size() - 1));
            if (method.isVarArgs() && !directArray && i >= parameters.size() - 1) {
                parameter = parameter.substring(0, parameter.length() - 2);
            }
            int current = valueCompatibility(args.get(i), parameter, method.virtualMachine());
            if (current < 0) return new ScoredMethod(method, -1, directArray);
            score += current;
        }
        return new ScoredMethod(method, score, directArray);
    }

    private static int valueCompatibility(EvalValue argument, String target, VirtualMachine vm) {
        if (argument.typeLiteral()) return -1;
        String source = argument.typeName();
        if (source == null) return isPrimitive(target) ? -1 : 20;
        DebuggerPrimitiveKind sourceKind = DebuggerPrimitiveKind.fromTypeName(source);
        boolean primitive = isPrimitive(source);
        DebuggerPrimitiveKind targetKind = DebuggerPrimitiveKind.fromPrimitiveName(target);
        if (targetKind != null) {
            if (primitive) {
                return sourceKind == null ? -1 : sourceKind.wideningCostTo(targetKind);
            }
            if (sourceKind != null) {
                int widening = sourceKind.wideningCostTo(targetKind);
                return widening < 0 ? -1 : 10 + widening;
            }
            return -1;
        }
        if (sourceKind != null && primitive) {
            String boxed = sourceKind.boxedName();
            if (boxed.equals(target)) return 10;
            return isAssignableName(boxed, target, vm) ? 12 : -1;
        }
        return source.equals(target) ? 0 : isAssignableName(source, target, vm) ? 2 : -1;
    }

    static List<Value> convertArguments(List<EvalValue> args, Method method, JavaExpressionEvaluator.Context context) throws Exception {
        List<Value> converted = new ArrayList<>();
        List<String> parameters = method.argumentTypeNames();
        for (int i = 0; i < args.size(); i++) {
            if (method.isVarArgs() && i >= parameters.size() - 1) break;
            converted.add(convertValue(args.get(i).value(), parameters.get(i), context));
        }
        if (method.isVarArgs()) {
            String arrayType = parameters.getLast();
            if (args.size() == parameters.size()
                    && valueCompatibility(args.getLast(), arrayType, context.vm()) >= 0) {
                converted.add(convertValue(args.getLast().value(), arrayType, context));
            } else {
                converted.add(makeVarargs(args.subList(parameters.size() - 1, args.size()).stream()
                        .map(EvalValue::value).toList(), arrayType, context));
            }
        }
        return converted;
    }

    private static Value makeVarargs(List<Value> args, String arrayTypeName,
                                     JavaExpressionEvaluator.Context context) throws Exception {
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
                               JavaExpressionEvaluator.Context context) throws Exception {
        if (value == null) {
            if (isPrimitive(target)) throw new NullPointerException("Cannot unbox null as " + target);
            return null;
        }
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
                    throw JavaExpressionEvaluator.targetException(exception);
                } finally {
                    context.evaluator().refreshStackFrames(context.thread());
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
                throw JavaExpressionEvaluator.targetException(exception);
            } finally {
                context.evaluator().refreshStackFrames(context.thread());
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
        Number number = JavaExpressionEvaluator.toNumber(value);
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
