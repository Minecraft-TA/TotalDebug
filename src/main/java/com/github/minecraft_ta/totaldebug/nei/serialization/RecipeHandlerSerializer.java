package com.github.minecraft_ta.totaldebug.nei.serialization;

import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.ShapedRecipeHandler;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;


public class RecipeHandlerSerializer {

    private static final Map<Class<? extends ICraftingHandler>, AbstractRecipeHandlerSerializer> RECIPEHANDLER_SERIALIZER = new Object2ObjectOpenHashMap<>();
    private static final Map<Class<? extends ICraftingHandler>, AbstractRecipeHandlerSerializer> INHERITANCE_SERIALIZERS = new Object2ObjectOpenHashMap<>();

    static {
        RECIPEHANDLER_SERIALIZER.put(ShapedRecipeHandler.class, new ShapedCraftingSerializer());

    }


    public static AbstractRecipeHandlerSerializer getRecipeHandlerSerializer(Class<? extends ICraftingHandler> handlerClass) {
        AbstractRecipeHandlerSerializer iRecipeHandlerSerializer = RECIPEHANDLER_SERIALIZER.get(handlerClass);
        if (iRecipeHandlerSerializer != null)
            return iRecipeHandlerSerializer;

        for (Map.Entry<Class<? extends ICraftingHandler>, AbstractRecipeHandlerSerializer> entry : INHERITANCE_SERIALIZERS.entrySet()) {
            if (entry.getKey().isAssignableFrom(handlerClass))
                return entry.getValue();
        }

        //Return default serializer
        return null;
    }


}

