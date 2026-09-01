package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JdtConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.IClassPathEntryStub;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.internal.codeassist.impl.AssistOptions;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.core.NameLookup;
import org.eclipse.jdt.internal.core.SearchableEnvironment;

import java.util.HashMap;
import java.util.Map;

public class JavaProjectImpl extends JavaProject {

    private static final Map<String, String> OPTIONS;
    static {
        OPTIONS = new HashMap<>();
        JdtConfiguration.applyJavaCompilerOptions(OPTIONS);
        OPTIONS.put(DefaultCodeFormatterConstants.FORMATTER_BLANK_LINES_BETWEEN_IMPORT_GROUPS, "1");
        // Snippets deliberately expose application members through TotalDebug's privileged linker.
        OPTIONS.put(AssistOptions.OPTION_PerformVisibilityCheck, AssistOptions.DISABLED);
        OPTIONS.put(AssistOptions.OPTION_PerformForbiddenReferenceCheck, AssistOptions.ENABLED);
        OPTIONS.put(AssistOptions.OPTION_CamelCaseMatch, AssistOptions.DISABLED);
        OPTIONS.put(AssistOptions.OPTION_SubwordMatch, AssistOptions.DISABLED);
    }

    public JavaProjectImpl() {
        super(new ProjectImpl(), null);
    }

    @Override
    public NameLookup newNameLookup(ICompilationUnit[] workingCopies, boolean excludeTestCode) throws JavaModelException {
        return new NameLookupImpl(this);
    }

    @Override
    public SearchableEnvironment newSearchableNameEnvironment(WorkingCopyOwner owner, boolean excludeTestCode) throws JavaModelException {
        return new SearchableEnvironmentImpl(this);
    }

    @Override
    public IModuleDescription getModuleDescription() throws JavaModelException {
        return null;
    }

    @Override
    public String getOption(String optionName, boolean inheritJavaCoreOptions) {
        return OPTIONS.get(optionName);
    }

    @Override
    public Map<String, String> getOptions(boolean inheritJavaCoreOptions) {
        return OPTIONS;
    }

    @Override
    public IClasspathEntry getClasspathEntryFor(IPath path) {
        return new IClassPathEntryStub() {};
    }

    @Override
    public IClasspathEntry[] getResolvedClasspath() throws JavaModelException {
        return new IClasspathEntry[0];
    }

    @Override
    public IClasspathEntry[] getRawClasspath() throws JavaModelException {
        return new IClasspathEntry[0];
    }

    @Override
    public IEclipsePreferences getEclipsePreferences() {
        return null;
    }
}
