package com.github.minecraft_ta.totaldebug.util;

import net.minecraft.nbt.NBTTagCompound;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

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
        for (Field declaredField : object.getClass().getDeclaredFields()) {
            declaredField.setAccessible(true);
            try {
                Object value = declaredField.get(object);
                if (value == null || declaredField.getType().isPrimitive() || isWrapper(value)) {
                    nbt.setString(declaredField.getName(), String.valueOf(value));
                } else if (!seenObjects.contains(value)) {
                    seenObjects.add(value);
                    nbt.setTag(declaredField.getName(), objectToNbt(value, seenObjects));
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
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

}
