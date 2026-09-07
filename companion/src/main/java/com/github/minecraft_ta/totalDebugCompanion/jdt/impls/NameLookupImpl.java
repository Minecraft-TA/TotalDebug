package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.ScriptProgramSource;
import com.github.minecraft_ta.totalDebugCompanion.jdt.CompanionClassIndex;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JIndexResolvedBinaryType;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedPackage;
import com.github.tth05.jindex.SearchOptions;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.internal.core.IJavaElementRequestor;
import org.eclipse.jdt.internal.core.NameLookup;
import org.eclipse.jdt.internal.core.util.HashtableOfArrayToObject;
import org.eclipse.jdt.internal.core.util.Util;

import java.util.Arrays;
import java.util.HashMap;

public class NameLookupImpl extends NameLookup {
    private static final String SCRIPT_PROGRAM_PACKAGE = "com.github.minecraft_ta.totaldebug.script";

    public NameLookupImpl(JavaProjectImpl javaProject) {
        super(
                javaProject,
                new IPackageFragmentRoot[]{JDTHacks.getSyntheticPackageFragmentRoot()},
                new HashtableOfArrayToObject(),
                null,
                new HashMap<>()
        );
    }

    @Override
    public boolean isPackage(String[] pkgName) {
        String packageName = String.join(".", pkgName);
        return SCRIPT_PROGRAM_PACKAGE.equals(packageName)
                || CompanionClassIndex.get().findPackage(Util.concatWith(pkgName, '/')) != null;
    }

    @Override
    public boolean isPackage(String[] pkgName, IPackageFragmentRoot[] moduleContext) {
        if (moduleContext == null) {
            return isPackage(pkgName);
        }
        for (IPackageFragmentRoot root : moduleContext) {
            if (root.equals(JDTHacks.getSyntheticPackageFragmentRoot())) {
                return isPackage(pkgName);
            }
        }
        return false;
    }

    @Override
    public IPackageFragmentRoot[] findPackageFragementRoots(String[] pkgName) {
        if (!isPackage(pkgName)) {
            return null;
        }
        return new IPackageFragmentRoot[]{JDTHacks.getSyntheticPackageFragmentRoot()};
    }

    @Override
    public Answer findType(String typeName, String packageName, boolean partialMatch, int acceptFlags, boolean considerSecondaryTypes, boolean waitForIndexes, boolean checkRestrictions, IProgressMonitor monitor, IPackageFragmentRoot[] moduleContext, int release) {
        var foundClass = CompanionClassIndex.get().findClass(packageName, typeName.replace('.', '$'));
        if (foundClass != null) {
            return JDTHacks.createNameLookupAnswer(new JIndexResolvedBinaryType(foundClass), null, null);
        }

        // ScriptProgram exists in the connected game, but snippets also need its API while
        // the runtime index is still coming online.
        if (packageName.equals(SCRIPT_PROGRAM_PACKAGE)
                && typeName.equals("ScriptProgram")) {
            return JDTHacks.createNameLookupAnswer(
                    new CompilationUnitImpl("ScriptProgram", ScriptProgramSource.text()).getType("ScriptProgram"),
                    null,
                    null
            );
        }
        return null;
    }

    @Override
    public IPackageFragment[] findPackageFragments(String name, boolean partialMatch, boolean patternMatch) {
        if (patternMatch || partialMatch)
            throw new IllegalArgumentException();
        var pkg = CompanionClassIndex.get().findPackage(name);
        if (pkg == null)
            return null;
        return new IPackageFragment[]{JDTHacks.createPackageFragment(pkg.getNameWithParentsDot())};
    }

    @Override
    public void seekTypes(String name, IPackageFragment pkg, boolean partialMatch, int acceptFlags, IJavaElementRequestor requestor, boolean considerSecondaryTypes) {
        if (name != null)
            name = name.replace('.', '$');

        IndexedClass[] classes;
        if (pkg != null) {
            var packageName = pkg.getElementName();
            if (packageName.isBlank())
                return;

            var indexedPackage = CompanionClassIndex.get().findPackage(packageName);
            if (indexedPackage == null)
                return;

            var finalName = name;
            if (name == null || name.isBlank())
                classes = indexedPackage.getClasses();
            else if (partialMatch)
                classes = Arrays.stream(indexedPackage.getClasses()).filter(c -> c.getName().contains(finalName)).toArray(IndexedClass[]::new);
            else
                classes = Arrays.stream(indexedPackage.getClasses()).filter(c -> c.getName().equals(finalName)).toArray(IndexedClass[]::new);
        } else {
            classes = CompanionClassIndex.get().findClasses(
                    name,
                    SearchOptions.with(
                            SearchOptions.SearchMode.CONTAINS,
                            SearchOptions.MatchMode.MATCH_CASE_FIRST_CHAR_ONLY,
                            5000
                    )
            ).results();
        }

        for (IndexedClass foundClass : classes) {
            if (!considerSecondaryTypes && foundClass.getInnerClassType() != null)
                continue;

            requestor.acceptType(new JIndexResolvedBinaryType(foundClass));
        }
    }

    @Override
    public void seekPackageFragments(String name, boolean partialMatch, IJavaElementRequestor requestor) {
        seekPackageFragments(name, partialMatch, requestor, null);
    }

    @Override
    public void seekPackageFragments(String name, boolean partialMatch, IJavaElementRequestor requestor, IPackageFragmentRoot[] moduleContext) {
        for (IndexedPackage pkg : CompanionClassIndex.get().findPackages(name)) {
            requestor.acceptPackageFragment(JDTHacks.createPackageFragment(pkg.getNameWithParentsDot()));
        }
    }
}
