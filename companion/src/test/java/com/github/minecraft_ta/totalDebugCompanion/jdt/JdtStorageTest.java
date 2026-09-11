package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.JdtTestEnvironment;
import org.eclipse.core.internal.runtime.InternalPlatform;
import org.eclipse.core.internal.runtime.MetaDataKeeper;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdtStorageTest {
    @Test
    void eclipseMetadataUsesTheApplicationCache() {
        assertNotNull(JDTHacks.DUMMY_JAVA_PROJECT);
        var cache = JdtTestEnvironment.PATHS.jdtCache();
        assertEquals(cache, MetaDataKeeper.getMetaArea().getMetadataLocation().toFile().toPath());
        assertEquals(cache, InternalPlatform.getDefault().getLocation().toFile().toPath());
        assertEquals(cache.resolve(".plugins/dummyBundle"), JavaCore.getPlugin().getStateLocation().toFile().toPath());
    }
}
