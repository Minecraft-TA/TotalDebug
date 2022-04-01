package com.github.minecraft_ta.totaldebug.util;

import com.github.minecraft_ta.totaldebug.util.mappings.RuntimeMappingsTransformer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class ObjectToJsonHelper {

    private static final HashMap<Class<?>, ITypeSerializer<?>> SERIALIZERS = new HashMap<>();
    private static final List<Pair<Class<?>, ITypeSerializer<?>>> INHERITANCE_SERIALIZERS = new ArrayList<>();
    private static final ITypeSerializer<Object> STRING_SERIALIZER = object -> new JsonPrimitive(String.valueOf(object));
    private static final ITypeSerializer<Number> NUMBER_SERIALIZER = JsonPrimitive::new;
    private static final ITypeSerializer<Boolean> BOOLEAN_SERIALIZER = JsonPrimitive::new;
    private static final ArraySerializer ARRAY_SERIALIZER = new ArraySerializer();

    static {
        Arrays.asList(Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class).forEach(clazz -> SERIALIZERS.put(clazz, NUMBER_SERIALIZER));
        Arrays.asList(String.class, Character.class).forEach(clazz -> SERIALIZERS.put(clazz, STRING_SERIALIZER));
        SERIALIZERS.put(Boolean.class, BOOLEAN_SERIALIZER);
        SERIALIZERS.put(ItemStack.class, new ItemStackSerializer());
        SERIALIZERS.put(NBTTagCompound.class, new NBTTagCompoundSerializer());

        INHERITANCE_SERIALIZERS.add(Pair.of(Map.class, new MapSerializer()));
        INHERITANCE_SERIALIZERS.add(Pair.of(Iterable.class, new IterableSerializer()));
    }

    /**
     * Recursively converts an object to a JsonObject.
     *
     * @param object The object to convert
     * @return The converted JsonObject
     */
    public static JsonElement objectToJson(Object object) throws StackOverflowError {
        ITypeSerializer<Object> serializer = getSerializer(object);
        if (serializer != null) {
            return serializer.serialize(object);
        } else {
            return objectToJson(object, new HashSet<>());
        }
    }

    /**
     * Helper method to convert an object to a JsonObject.
     *
     * @param object      The object to convert
     * @param seenObjects A set of already seen objects to prevent infinite recursion
     * @return The converted JsonObject
     * @throws StackOverflowError If the object has too many nested objects
     */
    private static JsonObject objectToJson(Object object, Set<Object> seenObjects) throws StackOverflowError {
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
                    ITypeSerializer<Object> serializer = getSerializer(value);
                    String fieldName = declaredField.getName();
                    if (clazz.getName().startsWith("net.minecraft")) {
                        fieldName = RuntimeMappingsTransformer.FORGE_MAPPINGS.getOrDefault(fieldName, fieldName);
                    }
                    if (serializer != null) {
                        json.add(fieldName, serializer.serialize(value));
                    } else if (!seenObjects.contains(value)) {
                        seenObjects.add(value);
                        json.add(fieldName, objectToJson(value, seenObjects));
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            clazz = clazz.getSuperclass();
        }
        return json;
    }

    /**
     * Gets the serializer for an object if one exists.
     *
     * @param value The object to get the serializer for
     * @return The serializer for the object or null if none exists
     */
    public static ITypeSerializer<Object> getSerializer(Object value) {
        if (value == null) {
            return STRING_SERIALIZER;
        }

        Class<?> clazz = value.getClass();
        if (clazz.isArray()) {
            return ARRAY_SERIALIZER;
        }

        ITypeSerializer<?> iTypeSerializer = SERIALIZERS.get(clazz);
        if (iTypeSerializer != null) {
            //noinspection unchecked
            return (ITypeSerializer<Object>) iTypeSerializer;
        }

        for (Pair<Class<?>, ITypeSerializer<?>> pair : INHERITANCE_SERIALIZERS) {
            if (pair.getLeft().isAssignableFrom(clazz)) {
                //noinspection unchecked
                return (ITypeSerializer<Object>) pair.getRight();
            }
        }
        return null;
    }

    interface ITypeSerializer<T> {

        /**
         * Serializes an object to a JsonElement.
         *
         * @param object The object to serialize
         * @return The object serialized to a JsonElement
         */
        JsonElement serialize(T object);

    }


    static class ArraySerializer implements ITypeSerializer<Object> {

        /**
         * {@inheritDoc}
         * <p>
         * This implementation serializes an array to a JsonArray.
         *
         * @param array The array to serialize
         * @return The array serialized to a JsonArray
         */
        @Override
        public JsonElement serialize(Object array) {
            JsonArray jsonArray = new JsonArray();
            int length = Array.getLength(array);
            for (int i = 0; i < length; i++) {
                Object value = Array.get(array, i);
                ITypeSerializer<Object> serializer = getSerializer(value);
                if (serializer != null) {
                    jsonArray.add(serializer.serialize(value));
                } else {
                    jsonArray.add(objectToJson(value));
                }
            }
            return jsonArray;
        }

    }

    static class IterableSerializer implements ITypeSerializer<Object> {

        /**
         * {@inheritDoc}
         * <p>
         * This implementation serializes an iterable to a JsonArray.
         *
         * @param iterable The iterable to serialize
         * @return The iterable serialized to a JsonArray
         */
        @Override
        public JsonElement serialize(Object iterable) {
            JsonArray jsonArray = new JsonArray();
            for (Object value : (Iterable<?>) iterable) {
                ITypeSerializer<Object> serializer = getSerializer(value);
                if (serializer != null) {
                    jsonArray.add(serializer.serialize(value));
                } else {
                    jsonArray.add(objectToJson(value));
                }
            }
            return jsonArray;
        }

    }

    static class MapSerializer implements ITypeSerializer<Object> {

        /**
         * {@inheritDoc}
         * <p>
         * This implementation serializes a map to a JsonObject.
         *
         * @param map The map to serialize
         * @return The map serialized to a JsonObject
         */
        @Override
        public JsonElement serialize(Object map) {
            JsonObject jsonObject = new JsonObject();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) map).entrySet()) {
                ITypeSerializer<Object> serializer = getSerializer(entry.getValue());
                if (serializer != null) {
                    jsonObject.add(entry.getKey().toString(), serializer.serialize(entry.getValue()));
                } else {
                    jsonObject.add(entry.getKey().toString(), objectToJson(entry.getValue()));
                }
            }
            return jsonObject;
        }

    }

    static class ItemStackSerializer implements ITypeSerializer<ItemStack> {

        /**
         * {@inheritDoc}
         * <p>
         * This implementation serializes an item stack to a JsonObject.
         *
         * @param itemStack The item stack to serialize
         * @return The item stack serialized to a JsonObject
         */
        @Override
        public JsonElement serialize(ItemStack itemStack) {
            JsonObject json = new JsonObject();
            json.addProperty("stackSize", itemStack.getCount());
            json.addProperty("displayName", itemStack.getDisplayName());
            json.addProperty("item", itemStack.getItem().getClass().getName());
            json.add("stackTagCompound", getSerializer(itemStack.getTagCompound()).serialize(itemStack.getTagCompound()));
            json.addProperty("isEmpty", itemStack.isEmpty());
            json.addProperty("itemDamage", itemStack.getItemDamage());


            try {
                Field capNBT = itemStack.getClass().getDeclaredField("capNBT");
                capNBT.setAccessible(true);
                Object capNBTObject = capNBT.get(itemStack);
                json.add("capNBT", getSerializer(capNBTObject).serialize(capNBTObject));
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
            }

            return json;
        }

    }

    static class NBTTagCompoundSerializer implements ITypeSerializer<NBTTagCompound> {

        /**
         * {@inheritDoc}
         * <p>
         * This implementation serializes an NBTTagCompound to a JsonObject.
         *
         * @param nbtTagCompound The NBTTagCompound to serialize
         * @return The NBTTagCompound serialized to a JsonObject
         */
        @Override
        public JsonElement serialize(NBTTagCompound nbtTagCompound) {
            return nbtToJson(nbtTagCompound);
        }

        /**
         * Converts an NBTTagCompound to a JsonObject using recursion.
         *
         * @param nbtTagCompound The NBTTagCompound to convert
         * @return The NBTTagCompound converted to a JsonObject
         */
        private JsonElement nbtToJson(NBTTagCompound nbtTagCompound) {
            JsonObject json = new JsonObject();
            for (String key : nbtTagCompound.getKeySet()) {
                NBTBase tag = nbtTagCompound.getTag(key);
                if (tag instanceof NBTTagCompound) {
                    json.add(key, nbtToJson((NBTTagCompound) tag));
                } else if (tag instanceof NBTTagList) {
                    JsonArray jsonArray = new JsonArray();
                    for (NBTBase nbtBase : ((NBTTagList) tag)) {
                        if (nbtBase instanceof NBTTagCompound) {
                            jsonArray.add(nbtToJson((NBTTagCompound) nbtBase));
                        } else {
                            jsonArray.add(nbtBase.toString());
                        }
                    }
                    json.add(key, jsonArray);
                } else if (tag instanceof NBTTagByteArray) {
                    json.add(key, ARRAY_SERIALIZER.serialize(((NBTTagByteArray) tag).getByteArray()));
                } else if (tag instanceof NBTTagIntArray) {
                    json.add(key, ARRAY_SERIALIZER.serialize(((NBTTagIntArray) tag).getIntArray()));
                } else {
                    json.addProperty(key, tag.toString());
                }
            }
            return json;
        }

    }

}
