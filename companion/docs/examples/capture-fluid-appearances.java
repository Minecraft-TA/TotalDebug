// Code-mode body for the matching Minecraft 1.21.1 / NeoForge client, not a standalone class.
// Run after client extensions are registered. This reads registry data and returns JSON; it writes no files.
import com.google.gson.GsonBuilder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

Map<String, Object> fluids = new TreeMap<>();
Map<String, String> errors = new TreeMap<>();
for (var fluid : BuiltInRegistries.FLUID) {
    if (fluid == Fluids.EMPTY) continue;
    String id = BuiltInRegistries.FLUID.getKey(fluid).toString();
    try {
        var extension = IClientFluidTypeExtensions.of(fluid);
        var texture = extension.getStillTexture();
        if (texture == null) throw new IllegalStateException("No still texture");
        Map<String, Object> appearance = new LinkedHashMap<>();
        appearance.put("stillTexture", texture.toString());
        appearance.put("tint", String.format(Locale.ROOT, "%08X",
                extension.getTintColor(new FluidStack(fluid, FluidType.BUCKET_VOLUME))));
        appearance.put("lightLevel", fluid.getFluidType().getLightLevel());
        appearance.put("lighterThanAir", fluid.getFluidType().isLighterThanAir());
        fluids.put(id, appearance);
    } catch (Exception exception) {
        errors.put(id, exception.toString());
    }
}
Map<String, Object> capture = new LinkedHashMap<>();
capture.put("schemaVersion", 1);
capture.put("fluids", fluids);
capture.put("errors", errors);
return new GsonBuilder().setPrettyPrinting().create().toJson(capture);
