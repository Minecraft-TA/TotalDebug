package com.github.minecraft_ta.totaldebug.nei.serializer;

import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.ShapedRecipeHandler;
import codechicken.nei.recipe.ShapelessRecipeHandler;
import com.github.minecraft_ta.totaldebug.integration.GregtechIntegration;
import gregtech.nei.GT_NEI_DefaultHandler;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.util.Map;


public class RecipeHandlerSerializerFactory {

    private static final Map<Class<? extends ICraftingHandler>, AbstractRecipeHandlerSerializer> RECIPEHANDLER_SERIALIZER = new Object2ObjectOpenHashMap<>();
    private static final Map<Class<? extends ICraftingHandler>, AbstractRecipeHandlerSerializer> INHERITANCE_SERIALIZERS = new Object2ObjectOpenHashMap<>();

    static {
        RECIPEHANDLER_SERIALIZER.put(ShapedRecipeHandler.class, new ShapedCraftingSerializer());
        RECIPEHANDLER_SERIALIZER.put(ShapelessRecipeHandler.class, new ShapelessCraftingSerializer());

        if (GregtechIntegration.getInstance().isEnabled()) {
            RECIPEHANDLER_SERIALIZER.put(GT_NEI_DefaultHandler.class, new GTRecipeSerializer());
        }
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

    public static void reset() {
        for (AbstractRecipeHandlerSerializer serializer : RECIPEHANDLER_SERIALIZER.values()) {
            serializer.reset();
        }
        for (AbstractRecipeHandlerSerializer serializer : INHERITANCE_SERIALIZERS.values()) {
            serializer.reset();
        }

    }


}

