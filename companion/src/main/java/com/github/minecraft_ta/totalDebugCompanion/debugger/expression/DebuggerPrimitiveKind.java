package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ShortValue;
import com.sun.jdi.Value;

import java.util.List;

/** Java primitive and wrapper conversion rules used by debugger overload resolution. */
enum DebuggerPrimitiveKind {
    BOOLEAN("boolean", "java.lang.Boolean"),
    BYTE("byte", "java.lang.Byte"),
    SHORT("short", "java.lang.Short"),
    CHAR("char", "java.lang.Character"),
    INT("int", "java.lang.Integer"),
    LONG("long", "java.lang.Long"),
    FLOAT("float", "java.lang.Float"),
    DOUBLE("double", "java.lang.Double");

    private static final List<DebuggerPrimitiveKind> NUMERIC_WIDENING =
            List.of(BYTE, SHORT, INT, LONG, FLOAT, DOUBLE);

    private final String primitiveName;
    private final String boxedName;

    DebuggerPrimitiveKind(String primitiveName, String boxedName) {
        this.primitiveName = primitiveName;
        this.boxedName = boxedName;
    }

    static DebuggerPrimitiveKind fromPrimitiveName(String name) {
        for (DebuggerPrimitiveKind kind : values()) {
            if (kind.primitiveName.equals(name)) {
                return kind;
            }
        }
        return null;
    }

    static DebuggerPrimitiveKind fromTypeName(String name) {
        for (DebuggerPrimitiveKind kind : values()) {
            if (kind.primitiveName.equals(name) || kind.boxedName.equals(name)) {
                return kind;
            }
        }
        return null;
    }

    static DebuggerPrimitiveKind fromValue(Value value) {
        if (value instanceof BooleanValue) return BOOLEAN;
        if (value instanceof ByteValue) return BYTE;
        if (value instanceof ShortValue) return SHORT;
        if (value instanceof CharValue) return CHAR;
        if (value instanceof IntegerValue) return INT;
        if (value instanceof LongValue) return LONG;
        if (value instanceof FloatValue) return FLOAT;
        if (value instanceof DoubleValue) return DOUBLE;
        return value == null ? null : fromTypeName(value.type().name());
    }

    String primitiveName() {
        return this.primitiveName;
    }

    String boxedName() {
        return this.boxedName;
    }

    int wideningCostTo(DebuggerPrimitiveKind target) {
        if (this == target) return 0;
        if (this == BOOLEAN || target == BOOLEAN) return -1;
        if (this == CHAR) {
            return switch (target) {
                case INT -> 1;
                case LONG -> 2;
                case FLOAT -> 3;
                case DOUBLE -> 4;
                default -> -1;
            };
        }
        if (target == CHAR) return -1;
        int source = NUMERIC_WIDENING.indexOf(this);
        int destination = NUMERIC_WIDENING.indexOf(target);
        return source >= 0 && destination >= source ? destination - source : -1;
    }
}
