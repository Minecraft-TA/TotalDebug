package com.github.minecraft_ta.totalDebugCompanion.navigation;

import java.util.concurrent.atomic.AtomicBoolean;

/** History belongs to a project, including while its runtime is temporarily unavailable. */
public final class NavigationState {
    final NavigationHistory history = new NavigationHistory(100);
    final AtomicBoolean traversingHistory = new AtomicBoolean();
    volatile NavigationEntry currentEntry;
}
