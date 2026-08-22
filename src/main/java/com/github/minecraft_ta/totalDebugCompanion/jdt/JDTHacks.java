package com.github.minecraft_ta.totalDebugCompanion.jdt;

import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.BundleContextImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.ContentTypeManagerImpl;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.DummyJarPackageFragmentRoot;
import com.github.minecraft_ta.totalDebugCompanion.jdt.impls.JavaProjectImpl;
import org.eclipse.core.internal.resources.Workspace;
import org.eclipse.core.internal.runtime.DataArea;
import org.eclipse.core.internal.runtime.InternalPlatform;
import org.eclipse.core.internal.runtime.MetaDataKeeper;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.codeassist.CompletionEngine;
import org.eclipse.jdt.internal.compiler.env.AccessRestriction;
import org.eclipse.jdt.internal.core.*;
import org.eclipse.jdt.internal.core.search.indexing.IndexManager;
import org.eclipse.jdt.internal.core.util.Util;
import org.osgi.util.tracker.ServiceTracker;
import sun.misc.Unsafe;

public class JDTHacks {

    public static final JavaProject DUMMY_JAVA_PROJECT;
    private static final PackageFragmentRoot PACKAGE_FRAGMENT_ROOT;
    private static final Unsafe UNSAFE;
    static {
        try {
            long t = System.nanoTime();
            var theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);

            System.out.println("Starting init hack");
            init();
            System.out.println("Init hack too: " + (System.nanoTime() - t) / 1_000_000.0);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }

        DUMMY_JAVA_PROJECT = new JavaProjectImpl();
        PACKAGE_FRAGMENT_ROOT = new DummyJarPackageFragmentRoot();
    }

    public static PackageFragment createPackageFragment(String name) {
        try {
            var instance = (PackageFragment) UNSAFE.allocateInstance(PackageFragment.class);
            setField(instance, "names", Util.getTrimmedSimpleNames(name));
            setField(instance, "isValidPackageName", true);
            setField(JavaElement.class, instance, "project", DUMMY_JAVA_PROJECT);
            setField(JavaElement.class, instance, "parent", PACKAGE_FRAGMENT_ROOT);
            return instance;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static PackageFragmentRoot getSyntheticPackageFragmentRoot() {
        return PACKAGE_FRAGMENT_ROOT;
    }

    public static NameLookup.Answer createNameLookupAnswer(IType type, AccessRestriction res, IClasspathEntry entry) {
        return createInstance(NameLookup.Answer.class, new Class[]{IType.class, AccessRestriction.class, IClasspathEntry.class}, type, res, entry);
    }

    public static <T> T createInstance(Class<T> clazz, Class<?>[] argClasses, Object... args) {
        try {
            var ctor = clazz.getDeclaredConstructor(argClasses);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T invokeMethod(Object obj, String methodName, Class<?>[] argClasses, Object... args) {
        try {
            var method = obj.getClass().getDeclaredMethod(methodName, argClasses);
            method.setAccessible(true);
            return (T) method.invoke(obj, args);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void setField(Object o, String fieldName, Object value) {
        setField(o.getClass(), o, fieldName, value);
    }

    public static void setField(Class<?> clazz, Object o, String fieldName, Object value) {
        try {
            var field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(o, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void setStaticField(Class<?> c, String fieldName, Object value) {
        try {
            var field = c.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void init() throws Throwable {
        //Set global instance
        new JavaCore();

        //bundleContext
        var field = InternalPlatform.class.getDeclaredField("context");
        field.setAccessible(true);
        field.set(InternalPlatform.getDefault(), new BundleContextImpl());

        // plugin
        field = ResourcesPlugin.class.getDeclaredField("plugin");
        field.setAccessible(true);
        field.set(null, new ResourcesPlugin());

        //bundle
        field = ResourcesPlugin.class.getSuperclass().getDeclaredField("bundle");
        field.setAccessible(true);
        field.set(ResourcesPlugin.getPlugin(), InternalPlatform.getDefault().getBundleContext().getBundle());

        //bundle
        field = JavaCore.class.getSuperclass().getDeclaredField("bundle");
        field.setAccessible(true);
        field.set(JavaCore.getPlugin(), InternalPlatform.getDefault().getBundleContext().getBundle());

        //contentTracker
        field = InternalPlatform.class.getDeclaredField("contentTracker");
        field.setAccessible(true);
        var value = new ServiceTracker<IContentTypeManager, IContentTypeManager>(InternalPlatform.getDefault().getBundleContext(), ContentTypeManagerImpl.class.getName(), null);
        value.open(true);
        field.set(InternalPlatform.getDefault(), value);

        //cachedInstanceLocation
        field = InternalPlatform.class.getDeclaredField("cachedInstanceLocation");
        field.setAccessible(true);
        field.set(InternalPlatform.getDefault(), new org.eclipse.core.runtime.Path(""));

        //initialized
        field = InternalPlatform.class.getDeclaredField("initialized");
        field.setAccessible(true);
        field.set(InternalPlatform.getDefault(), true);

        //cache
        field = JavaModelManager.class.getDeclaredField("cache");
        field.setAccessible(true);
        field.set(JavaModelManager.getJavaModelManager(), new JavaModelCache());

        //dataArea
        field = MetaDataKeeper.class.getDeclaredField("metaArea");
        field.setAccessible(true);
        field.set(null, new DataArea() {
            @Override
            protected synchronized void assertLocationInitialized() throws IllegalStateException {
            }

            @Override
            public IPath getMetadataLocation() throws IllegalStateException {
                return new Path("");
            }
        });

        //indexManager
        field = JavaModelManager.class.getDeclaredField("indexManager");
        field.setAccessible(true);
        field.set(JavaModelManager.getJavaModelManager(), new IndexManager() {
            {
                super.reset();
            }
        });

        //workspace
        var instance = UNSAFE.allocateInstance(Class.forName("org.eclipse.core.resources.ResourcesPlugin$WorkspaceInitCustomizer"));
        field = instance.getClass().getDeclaredField("workspace");
        field.setAccessible(true);
        field.set(instance, new Workspace());
        field = ResourcesPlugin.class.getDeclaredField("workspaceInitCustomizer");
        field.setAccessible(true);
        field.set(ResourcesPlugin.getPlugin(), instance);

        CompletionEngine.DEBUG = false;
    }
}
