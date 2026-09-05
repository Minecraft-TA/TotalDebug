package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.osgi.framework.*;

import java.io.File;
import java.io.InputStream;
import java.util.Collection;
import java.util.Dictionary;

public interface BundleContextStub extends BundleContext {

    @Override
    default String getProperty(String key) {
        return null;
    }

    @Override
    default Bundle getBundle() {
        return null;
    }

    @Override
    default Bundle installBundle(String location, InputStream input) {
        return null;
    }

    @Override
    default Bundle installBundle(String location) {
        return null;
    }

    @Override
    default Bundle getBundle(long id) {
        return null;
    }

    @Override
    default Bundle[] getBundles() {
        return new Bundle[0];
    }

    @Override
    default void addServiceListener(ServiceListener listener, String filter) {

    }

    @Override
    default void addServiceListener(ServiceListener listener) {

    }

    @Override
    default void removeServiceListener(ServiceListener listener) {

    }

    @Override
    default void addBundleListener(BundleListener listener) {

    }

    @Override
    default void removeBundleListener(BundleListener listener) {

    }

    @Override
    default void addFrameworkListener(FrameworkListener listener) {

    }

    @Override
    default void removeFrameworkListener(FrameworkListener listener) {

    }

    @Override
    default ServiceRegistration<?> registerService(String[] clazzes, Object service, Dictionary<String, ?> properties) {
        return null;
    }

    @Override
    default ServiceRegistration<?> registerService(String clazz, Object service, Dictionary<String, ?> properties) {
        return null;
    }

    @Override
    default <S> ServiceRegistration<S> registerService(Class<S> clazz, S service, Dictionary<String, ?> properties) {
        return null;
    }

    @Override
    default <S> ServiceRegistration<S> registerService(Class<S> clazz, ServiceFactory<S> factory, Dictionary<String, ?> properties) {
        return null;
    }

    @Override
    default ServiceReference<?>[] getServiceReferences(String clazz, String filter) {
        return null;
    }

    @Override
    default ServiceReference<?>[] getAllServiceReferences(String clazz, String filter) {
        return null;
    }

    @Override
    default ServiceReference<?> getServiceReference(String clazz) {
        return null;
    }

    @Override
    default <S> ServiceReference<S> getServiceReference(Class<S> clazz) {
        return null;
    }

    @Override
    default <S> Collection<ServiceReference<S>> getServiceReferences(Class<S> clazz, String filter) {
        return null;
    }

    @Override
    default <S> S getService(ServiceReference<S> reference) {
        return null;
    }

    @Override
    default boolean ungetService(ServiceReference<?> reference) {
        return false;
    }

    @Override
    default <S> ServiceObjects<S> getServiceObjects(ServiceReference<S> reference) {
        return null;
    }

    @Override
    default File getDataFile(String filename) {
        return null;
    }

    @Override
    default Filter createFilter(String filter) {
        return null;
    }

    @Override
    default Bundle getBundle(String location) {
        return null;
    }
}
