package com.github.minecraft_ta.totalDebugCompanion.decompiler.fixture;
import java.lang.annotation.*;
import java.util.*;
import java.util.function.*;
import java.io.*;

@NavigationFixture.Marker(type=String.class)
public class NavigationFixture<T> {
 @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.TYPE_USE})
 public @interface Marker { Class<?> type() default Object.class; }
 public record Data(String text, int number) {}
 public record Checked(String text) { public Checked { Objects.requireNonNull(text); } }
 public enum Kind { FIRST, SECOND { @Override public String label() { return "second"; } }; public String label() { return name(); } }
 public interface Contract<A> { A get(); default int size() { return get().toString().length(); } }
 public static class Implementation implements Contract<String> { public String get() { return "impl"; } }
 public sealed interface Shape permits Circle {}
 public record Circle(int radius) implements Shape {}
 public interface Nested { String apply(String value); }
 public class Inner { public Inner(String value) { field = value; } public String read() { return field; } }
 public static class StaticNested { public String apply(String value) { return value.trim(); } }
 public static final Supplier<String> A = () -> "A"; public static final Supplier<String> B = () -> "B";
 // Same-line and nested lambdas must remain distinguishable after line-number stripping.
 public static final Function<String, String> TRIM = value -> value.trim(); public static final Function<String, String> UPPER = value -> value.toUpperCase();
 public static final Function<String, Function<String, String>> NESTED = outer -> inner -> outer + inner;
 public static final Supplier<Integer> COUNTER = () -> { int sum = 0; for (int i = 0; i < 3; i++) sum += i; return sum; };
 public static final IntFunction<String[]> ARRAY = String[]::new;
 public static final Function<String, String> METHOD = String::trim;
 public static final Supplier<ArrayList<String>> CONSTRUCTOR = ArrayList::new;
 public static String FIRST = "first".trim();
 public static String SECOND = "second".trim();
 public static String mutable = "mutable";
 public static final String copied = mutable;
 public static int[] numbers = {1, 2};
 public static final int size = numbers.length;
 public static final int CONSTANT = 7;
 public static String outerMarker = "anon-initializer";
 static { FIRST = FIRST + SECOND; }
 public String field = "field".trim();
 { field += "instance"; }
 public NavigationFixture() {}
 public NavigationFixture(int ignored) { this(); }
 public String overloaded(String value) { return value.trim(); }
 public String overloaded(Integer value) { return value.toString(); }
 public String lambdas(String input) {
   Supplier<String> one = () -> input.trim(); Supplier<String> two = () -> input.toUpperCase();
   Supplier<Supplier<String>> nested = () -> () -> input.toLowerCase();
   return one.get() + two.get() + nested.get().get();
 }
 public String localAndAnonymous(String input) {
   class Local { String read() { return input.trim(); } }
   Supplier<String> anonymous = new Supplier<>() { public String get() { return input.toUpperCase(); } };
   return new Local().read() + anonymous.get();
 }
 // A non-constant field preserves the anonymous class initializer tested by source navigation.
 public static Object fieldOnlyAnonymous() { return new Object() { static String marker = "anon-initializer"; }; }
 public String pattern(Object value) {
   return switch(value) {
     case String text when !text.isEmpty() -> text.trim();
     case Data(String text, int number) -> text + number;
     default -> value.toString();
   };
 }
 public String resource(Object value) throws IOException {
   try (var reader = new StringReader(value.toString())) { return Integer.toString(reader.read()); }
   catch (IllegalArgumentException ex) { return ex.getMessage(); }
 }
 public synchronized String sync(Object value) {
   synchronized(value) { assert value != null : "missing"; return value.toString(); }
 }
 public int arrayAndLoop(String... values) { int count=0; for (String v : values) { count+=v.length(); } return count; }
 public List<? extends T> generic(List<? extends T> values) { return values; }
 public native void nativeMethod();
}
