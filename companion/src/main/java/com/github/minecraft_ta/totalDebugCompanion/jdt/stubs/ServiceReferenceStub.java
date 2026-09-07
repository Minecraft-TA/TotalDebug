package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;

import java.util.Dictionary;

public interface ServiceReferenceStub extends ServiceReference {

    @Override
    default Object getProperty(String key) {
        return null;
    }

    @Override
    default String[] getPropertyKeys() {
        return new String[0];
    }

    @Override
    default Bundle getBundle() {
        return null;
    }

    @Override
    default Bundle[] getUsingBundles() {
        return new Bundle[0];
    }

    @Override
    default boolean isAssignableTo(Bundle bundle, String className) {
        return false;
    }

    @Override
    default int compareTo(Object reference) {
        return 0;
    }

    @Override
    default Dictionary<String, Object> getProperties() {
        return null;
    }

    @Override
    default Object adapt(Class type) {
        return null;
    }
}
