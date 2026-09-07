package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import org.eclipse.core.internal.content.ContentType;
import org.eclipse.core.internal.content.ContentTypeCatalog;
import org.eclipse.core.internal.content.ContentTypeManager;
import org.eclipse.core.runtime.content.IContentType;

import java.util.HashMap;

public class ContentTypeManagerImpl extends ContentTypeManager {

    private ContentType javaContentType;

    @Override
    public IContentType[] getAllContentTypes() {
        return new IContentType[]{getJava()};
    }

    @Override
    public IContentType getContentType(String contentTypeIdentifier) {
        return getJava();
    }

    private ContentType getJava() {
        if (javaContentType == null)
            javaContentType = ContentType.createContentType(new ContentTypeCatalog(this, 0), "1", "java", (byte) 1, new String[]{"java"}
                    , new String[0], new String[0], "java", "java", new HashMap<>(), null);
        return javaContentType;
    }
}
