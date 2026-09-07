package com.github.minecraft_ta.totalDebugCompanion.jdt.stubs;

import org.osgi.framework.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

public interface BundleStub extends Bundle {

    @Override
    default int getState() {
        return 0;
    }

    @Override
    default void start(int options) {

    }

    @Override
    default void start() {

    }

    @Override
    default void stop(int options) {

    }

    @Override
    default void stop() {

    }

    @Override
    default void update(InputStream input) {

    }

    @Override
    default void update() {

    }

    @Override
    default void uninstall() {

    }

    @Override
    default Dictionary<String, String> getHeaders() {
        return null;
    }

    @Override
    default long getBundleId() {
        return 0;
    }

    @Override
    default String getLocation() {
        return null;
    }

    @Override
    default ServiceReference<?>[] getRegisteredServices() {
        return null;
    }

    @Override
    default ServiceReference<?>[] getServicesInUse() {
        return null;
    }

    @Override
    default boolean hasPermission(Object permission) {
        return false;
    }

    @Override
    default URL getResource(String name) {
        return null;
    }

    @Override
    default Dictionary<String, String> getHeaders(String locale) {
        return null;
    }

    @Override
    default String getSymbolicName() {
        return null;
    }

    @Override
    default Class<?> loadClass(String name) throws ClassNotFoundException {
        return null;
    }

    @Override
    default Enumeration<URL> getResources(String name) throws IOException {
        return null;
    }

    @Override
    default Enumeration<String> getEntryPaths(String path) {
        return null;
    }

    @Override
    default URL getEntry(String path) {
        return null;
    }

    @Override
    default long getLastModified() {
        return 0;
    }

    @Override
    default Enumeration<URL> findEntries(String path, String filePattern, boolean recurse) {
        return null;
    }

    @Override
    default BundleContext getBundleContext() {
        return null;
    }

    @Override
    default Map<X509Certificate, List<X509Certificate>> getSignerCertificates(int signersType) {
        return null;
    }

    @Override
    default Version getVersion() {
        return null;
    }

    @Override
    default <A> A adapt(Class<A> type) {
        return null;
    }

    @Override
    default File getDataFile(String filename) {
        return null;
    }

    @Override
    default int compareTo(Bundle o) {
        return 0;
    }
}
