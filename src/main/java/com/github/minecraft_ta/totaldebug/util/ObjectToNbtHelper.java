package com.github.minecraft_ta.totaldebug.util;

import net.minecraft.nbt.NBTTagCompound;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class ObjectToNbtHelper {

    /**
     * Recursively converts an object to an NBTTagCompound
     *
     * @param object The object to convert
     * @return The converted NBTTagCompound
     */
    public static NBTTagCompound objectToNbt(Object object) {
        return objectToNbt(object, new HashSet<>());
    }

    /**
     * Helper method to convert an object to an NBTTagCompound
     *
     * @param object      The object to convert
     * @param seenObjects A set of already seen objects to prevent infinite recursion
     * @return The converted NBTTagCompound
     */
    private static NBTTagCompound objectToNbt(Object object, Set<Object> seenObjects) {
        NBTTagCompound nbt = new NBTTagCompound();
        Class<?> clazz = object.getClass();
        while (clazz != null) {
            for (Field declaredField : clazz.getDeclaredFields()) {
                int modifiers = declaredField.getModifiers();
                if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
                    continue;
                }
                declaredField.setAccessible(true);
                try {
                    Object value = declaredField.get(object);
                    if (value == null || declaredField.getType().isPrimitive() || isWrapper(value)) {
                        nbt.setString(declaredField.getName(), String.valueOf(value));
                    } else if (!seenObjects.contains(value)) {
                        seenObjects.add(value);
                        if (value instanceof Iterable) {
                            NBTTagCompound iterableNbt = new NBTTagCompound();
                            int i = 0;
                            for (Object arrayObject : ((Iterable<?>) value)) {
                                if (arrayObject == null || isWrapper(iterableNbt)) {
                                    iterableNbt.setString(String.valueOf(i), String.valueOf(arrayObject));
                                } else {
                                    iterableNbt.setTag(String.valueOf(i), objectToNbt(arrayObject, seenObjects));
                                }
                                i++;
                            }
                            nbt.setTag(declaredField.getName(), iterableNbt);
                        } else if (value.getClass().isArray()) {
                            NBTTagCompound arrayNbt = new NBTTagCompound();
                            int length = Array.getLength(value);
                            for (int i = 0; i < length; i++) {
                                Object arrayElement = Array.get(value, i);
                                if (arrayElement == null || isWrapper(arrayElement)) {
                                    arrayNbt.setString(String.valueOf(i), String.valueOf(arrayElement));
                                } else {
                                    arrayNbt.setTag(String.valueOf(i), objectToNbt(arrayElement, seenObjects));
                                }
                            }
                            nbt.setTag(declaredField.getName(), arrayNbt);
                        } else {
                            nbt.setTag(declaredField.getName(), objectToNbt(value, seenObjects));
                        }
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            clazz = clazz.getSuperclass();
        }
        return nbt;
    }

    private static boolean isWrapper(Object object) {
        return object instanceof Integer ||
                object instanceof Double ||
                object instanceof Float ||
                object instanceof Long ||
                object instanceof Boolean ||
                object instanceof Character ||
                object instanceof Short ||
                object instanceof Byte ||
                object instanceof String;
    }

    public static void main(String[] args) {
        List<String> stringList = Arrays.asList("a", "b", "c", "d", "e");
        ArrayList<String> listOfStrings = new ArrayList<>();
        listOfStrings.add("a");
        listOfStrings.add("b");
        listOfStrings.add("c");
        listOfStrings.add("d");
        listOfStrings.add("e");
        Test t = new Test("lol", new int[]{1, 2, 3, 4}, listOfStrings);
        System.out.println(objectToNbt(t));
        //System.out.println(objectToNbt(stringList));
    }

    private static class Test {
        private String a;
        private int[] b;
        private ArrayList<String> listOfStrings;

        public Test(String a, int[] b, ArrayList<String> listOfStrings) {
            this.a = a;
            this.b = b;
            this.listOfStrings = listOfStrings;
        }
    }

}
