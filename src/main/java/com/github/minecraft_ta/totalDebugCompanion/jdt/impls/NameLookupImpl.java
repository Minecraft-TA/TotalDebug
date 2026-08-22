package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.BaseScript;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JDTHacks;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JIndexResolvedBinaryType;
import com.github.minecraft_ta.totalDebugCompanion.ui.views.SearchEverywherePopup;
import com.github.tth05.jindex.IndexedClass;
import com.github.tth05.jindex.IndexedPackage;
import com.github.tth05.jindex.SearchOptions;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.internal.core.IJavaElementRequestor;
import org.eclipse.jdt.internal.core.NameLookup;
import org.eclipse.jdt.internal.core.util.Util;

import java.util.Arrays;
import java.util.HashMap;

public class NameLookupImpl extends NameLookup {

    public NameLookupImpl() {
        super(null, null, null, null, new HashMap<>());
    }

    @Override
    public boolean isPackage(String[] pkgName) {
        return SearchEverywherePopup.CLASS_INDEX.findPackage(Util.concatWith(pkgName, '/')) != null;
    }

    @Override
    public Answer findType(String typeName, String packageName, boolean partialMatch, int acceptFlags, boolean considerSecondaryTypes, boolean waitForIndexes, boolean checkRestrictions, IProgressMonitor monitor, IPackageFragmentRoot[] moduleContext, int release) {
        //Special case for BaseScript resolution
        if (packageName.equals("") && typeName.equals("BaseScript"))
            return JDTHacks.createNameLookupAnswer(new CompilationUnitImpl("BaseScript", BaseScript.getText()).getType("BaseScript"), null, null);

        var foundClass = SearchEverywherePopup.CLASS_INDEX.findClass(packageName, typeName.replace('.', '$'));
        if (foundClass != null) {
            return JDTHacks.createNameLookupAnswer(new JIndexResolvedBinaryType(foundClass), null, null);
        }
        return null;
    }

    @Override
    public IPackageFragment[] findPackageFragments(String name, boolean partialMatch, boolean patternMatch) {
        if (patternMatch || partialMatch)
            throw new IllegalArgumentException();
        var pkg = SearchEverywherePopup.CLASS_INDEX.findPackage(name);
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

            var indexedPackage = SearchEverywherePopup.CLASS_INDEX.findPackage(packageName);
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
            classes = SearchEverywherePopup.CLASS_INDEX.findClasses(name, SearchOptions.with(SearchOptions.SearchMode.CONTAINS, SearchOptions.MatchMode.MATCH_CASE_FIRST_CHAR_ONLY, 5000));
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
        for (IndexedPackage pkg : SearchEverywherePopup.CLASS_INDEX.findPackages(name)) {
            requestor.acceptPackageFragment(JDTHacks.createPackageFragment(pkg.getNameWithParentsDot()));
        }
    }
}
