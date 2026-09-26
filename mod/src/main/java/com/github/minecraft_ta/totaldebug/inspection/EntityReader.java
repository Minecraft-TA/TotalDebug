package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;

/** Built-in reader for where an entity is and, for a living one, its health. */
public final class EntityReader {
    private EntityReader() {
    }

    public static void read(ScriptTarget target, ScriptFacts facts) {
        if (!(target instanceof ScriptTarget.LiveEntity live)) return;
        Entity entity = live.entity();
        ScriptFacts.Section section = facts.section("Entity state");
        section.text("Position", String.format(Locale.ROOT, "%.1f, %.1f, %.1f", entity.getX(), entity.getY(), entity.getZ()));
        section.text("Dimension", entity.level().dimension().location());
        if (entity instanceof LivingEntity living) {
            section.bar("Health", Math.round(living.getHealth()), Math.round(living.getMaxHealth()), "");
        }
    }
}
