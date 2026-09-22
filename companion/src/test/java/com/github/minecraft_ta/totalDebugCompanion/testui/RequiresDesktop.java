package com.github.minecraft_ta.totalDebugCompanion.testui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Native focus or monitor-placement coverage. Opt in only on an isolated desktop. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@UiTest
@Tag("desktop")
@EnabledIfSystemProperty(named = "totaldebug.desktopTests", matches = "true")
public @interface RequiresDesktop { }
