package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.BundleContextStub;
import org.osgi.framework.ServiceReference;

public class BundleContextImpl implements BundleContextStub {

    private static final ServiceReference<?> CONTENT_MANAGER_SERVICE_REFERENCE = new ServiceReferenceImpl();

    @Override
    public BundleImpl getBundle() {
        return new BundleImpl();
    }

    @Override
    public ServiceReference<?>[] getAllServiceReferences(String clazz, String filter) {
        return new ServiceReference[]{CONTENT_MANAGER_SERVICE_REFERENCE};
    }

    @Override
    public <S> S getService(ServiceReference<S> reference) {
        if (reference == CONTENT_MANAGER_SERVICE_REFERENCE)
            return (S) new ContentTypeManagerImpl();

        throw new UnsupportedOperationException("Tried to get service for unknown reference");
    }
}
