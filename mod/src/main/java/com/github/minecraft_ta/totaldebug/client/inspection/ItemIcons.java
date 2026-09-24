package com.github.minecraft_ta.totaldebug.client.inspection;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.RegistryAwareItemModelShaper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Finds the inventory model and tint colors Minecraft uses to draw a stack as an icon. Client thread only. */
public final class ItemIcons {
    private static final int MAX_TINTS = 32;

    private ItemIcons() {
    }

    public record Icon(String model, Map<Integer, Integer> tints) {
    }

    /** The inventory model Minecraft draws for the stack, without reading its quads. */
    public static Optional<String> model(ItemStack stack) {
        if (stack.isEmpty()
                || !(Minecraft.getInstance().getItemRenderer().getItemModelShaper() instanceof RegistryAwareItemModelShaper shaper)) {
            return Optional.empty();
        }
        ModelResourceLocation location = shaper.getLocation(stack);
        if (location == null || !location.variant().equals("inventory")) {
            return Optional.empty();
        }
        return Optional.of(location.id().getNamespace() + ":item/" + location.id().getPath());
    }

    public static Optional<Icon> of(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (!(minecraft.getItemRenderer().getItemModelShaper() instanceof RegistryAwareItemModelShaper shaper)) {
                return Optional.empty();
            }
            ModelResourceLocation location = shaper.getLocation(stack);
            if (location == null || !location.variant().equals("inventory")) {
                return Optional.empty();
            }
            Map<Integer, Integer> tints = new LinkedHashMap<>();
            RandomSource random = RandomSource.create(42);
            BakedModel model = shaper.getItemModel(stack);
            for (BakedModel pass : model.getRenderPasses(stack, true)) {
                for (int side = 0; side <= Direction.values().length; side++) {
                    random.setSeed(42);
                    Direction direction = side == Direction.values().length ? null : Direction.values()[side];
                    for (BakedQuad quad : pass.getQuads(null, direction, random)) {
                        if (quad.isTinted() && tints.size() < MAX_TINTS) {
                            tints.putIfAbsent(quad.getTintIndex(),
                                    minecraft.getItemColors().getColor(stack, quad.getTintIndex()));
                        }
                    }
                }
            }
            String id = location.id().getNamespace() + ":item/" + location.id().getPath();
            return Optional.of(new Icon(id, tints));
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.debug("No icon model for {}", stack, exception);
            return Optional.empty();
        }
    }
}
