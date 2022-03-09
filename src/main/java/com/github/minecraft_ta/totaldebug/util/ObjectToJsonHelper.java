package com.github.minecraft_ta.totaldebug.util;

import com.google.gson.JsonObject;
import net.minecraft.nbt.NBTTagCompound;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

public class ObjectToJsonHelper {

    /**
     * Recursively converts an object to a JsonObject.
     *
     * @param object The object to convert
     * @return The converted JsonObject
     */
    public static JsonObject objectToJson(Object object) throws StackOverflowError {
        return objectToJson(object, new HashSet<>());
    }

    /**
     * Helper method to convert an object to a JsonObject.
     *
     * @param object      The object to convert
     * @param seenObjects A set of already seen objects to prevent infinite recursion
     * @return The converted JsonObject
     * @throws StackOverflowError If the object has too many nested objects
     */
    private static JsonObject objectToJson(Object object, Set<Object> seenObjects) throws StackOverflowError{
        JsonObject json = new JsonObject();
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
                        json.addProperty(declaredField.getName(), String.valueOf(value));
                    } else if (!seenObjects.contains(value)) {
                        seenObjects.add(value);
                        if (value instanceof Iterable) {
                            JsonObject iterableJson = new JsonObject();
                            int i = 0;
                            for (Object arrayObject : ((Iterable<?>) value)) {
                                if (arrayObject == null || isWrapper(iterableJson)) {
                                    iterableJson.addProperty(String.valueOf(i), String.valueOf(arrayObject));
                                } else {
                                    iterableJson.add(String.valueOf(i), objectToJson(arrayObject, seenObjects));
                                }
                                i++;
                            }
                            json.add(declaredField.getName(), iterableJson);
                        } else if (value.getClass().isArray()) {
                            JsonObject arrayJson = new JsonObject();
                            int length = Array.getLength(value);
                            for (int i = 0; i < length; i++) {
                                Object arrayElement = Array.get(value, i);
                                if (arrayElement == null || isWrapper(arrayElement)) {
                                    arrayJson.addProperty(String.valueOf(i), String.valueOf(arrayElement));
                                } else {
                                    arrayJson.add(String.valueOf(i), objectToJson(arrayElement, seenObjects));
                                }
                            }
                            json.add(declaredField.getName(), arrayJson);
                        } else {
                            json.add(declaredField.getName(), objectToJson(value, seenObjects));
                        }
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            clazz = clazz.getSuperclass();
        }
        return json;
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
}
