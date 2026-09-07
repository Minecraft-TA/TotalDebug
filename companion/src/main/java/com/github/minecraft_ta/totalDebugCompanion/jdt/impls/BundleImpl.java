package com.github.minecraft_ta.totalDebugCompanion.jdt.impls;

import com.github.minecraft_ta.totalDebugCompanion.jdt.stubs.BundleStub;

public class BundleImpl implements BundleStub {

    @Override
    public int getState() {
        return BundleImpl.INSTALLED;
    }

    @Override
    public String getSymbolicName() {
        return "dummyBundle";
    }
}
