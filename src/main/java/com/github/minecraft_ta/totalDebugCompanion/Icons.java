package com.github.minecraft_ta.totalDebugCompanion;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.extras.FlatSVGUtils;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;

import java.awt.Image;
import java.util.List;

public class Icons {

    private static final String WINDOW_ICON = "/icons/javaFile";

    /** Window icons are raster images, so they must be rebuilt when the theme changes. */
    public static List<Image> createWindowIconImages(CompanionTheme theme) {
        String suffix = theme.dark() ? "_dark.svg" : ".svg";
        return FlatSVGUtils.createWindowIconImages(WINDOW_ICON + suffix);
    }

    public static final FlatSVGIcon JAVA_METHOD = new FlatSVGIcon("icons/method.svg");
    public static final FlatSVGIcon JAVA_CLASS = new FlatSVGIcon("icons/class.svg");
    public static final FlatSVGIcon JAVA_CONSTANT = new FlatSVGIcon("icons/constant.svg");
    public static final FlatSVGIcon JAVA_PROPERTY = new FlatSVGIcon("icons/property.svg");
    public static final FlatSVGIcon JAVA_VARIABLE = new FlatSVGIcon("icons/variable.svg");
    public static final FlatSVGIcon JAVA_INTERFACE = new FlatSVGIcon("icons/interface.svg");
    public static final FlatSVGIcon JAVA_ENUM = new FlatSVGIcon("icons/enum.svg");
    public static final FlatSVGIcon JAVA_CONSTRUCTOR = new FlatSVGIcon("icons/constructor.svg");

    public static final FlatSVGIcon INFORMATION = new FlatSVGIcon("icons/information.svg");
    public static final FlatSVGIcon SUCCESS = new FlatSVGIcon("icons/success.svg");
    public static final FlatSVGIcon WARNING = new FlatSVGIcon("icons/warning.svg");
    public static final FlatSVGIcon ERROR = new FlatSVGIcon("icons/error.svg");

    public static final FlatSVGIcon JAVA_FILE = new FlatSVGIcon("icons/javaFile.svg");
    public static final FlatSVGIcon TEXT_FILE = new FlatSVGIcon("icons/text.svg");
    public static final FlatSVGIcon CLASS_FILE = new FlatSVGIcon("icons/classFile.svg");
    public static final FlatSVGIcon JAR_FILE = new FlatSVGIcon("icons/jar.svg");

    public static final FlatSVGIcon CLOSE_ICON = new FlatSVGIcon("icons/close.svg");
    public static final FlatSVGIcon CLOSE_HOVERED_ICON = new FlatSVGIcon("icons/closeHovered.svg");
    public static final FlatSVGIcon DOWNLOAD = new FlatSVGIcon("icons/download.svg");
    public static final FlatSVGIcon DELETE = new FlatSVGIcon("icons/delete.svg");

    public static final FlatSVGIcon RUN = new FlatSVGIcon("icons/run.svg");
    public static final FlatSVGIcon RUN_SERVER = new FlatSVGIcon("icons/runServer.svg");
    public static final FlatSVGIcon STOP = new FlatSVGIcon("icons/stop.svg");

    public static final FlatSVGIcon OVERLAY_MODE = new FlatSVGIcon("icons/overlayMode.svg");
    public static final FlatSVGIcon TARGET = new FlatSVGIcon("icons/target.svg");

    public static final FlatSVGIcon SEARCH_ICON = new FlatSVGIcon("icons/search.svg");
    public static final FlatSVGIcon MATCH_CASE = new FlatSVGIcon("icons/matchCase.svg");
    public static final FlatSVGIcon REGEX = new FlatSVGIcon("icons/regex.svg");
    public static final FlatSVGIcon PREVIOUS_OCCURRENCE = new FlatSVGIcon("icons/previousOccurrence.svg");
    public static final FlatSVGIcon NEXT_OCCURRENCE = new FlatSVGIcon("icons/nextOccurrence.svg");

    public static final FlatSVGIcon PAUSE = new FlatSVGIcon("icons/pause.svg");
    public static final FlatSVGIcon CLEAR = new FlatSVGIcon("icons/clear.svg");
    public static final FlatSVGIcon CLOCK = new FlatSVGIcon("icons/clock.svg");
    public static final FlatSVGIcon DECOMPILE = new FlatSVGIcon("icons/decompile.svg");
    public static final FlatSVGIcon COPY = new FlatSVGIcon("icons/copy.svg");
    public static final FlatSVGIcon BLOCK = new FlatSVGIcon("icons/block.svg");
    public static final FlatSVGIcon FIELD = new FlatSVGIcon("icons/field.svg");

    public static final FlatSVGIcon UP_DOWN = new FlatSVGIcon("icons/upDown.svg");
    public static final FlatSVGIcon PRIMITIVE = new FlatSVGIcon("icons/primitive.svg");
    public static final FlatSVGIcon VALUE = new FlatSVGIcon("icons/value.svg");
    public static final FlatSVGIcon ARRAY = new FlatSVGIcon("icons/array.svg");
    public static final FlatSVGIcon RIGHT_ARROW = new FlatSVGIcon("icons/arrow_right.svg");
    public static final FlatSVGIcon DOWN_ARROW = new FlatSVGIcon("icons/arrow_down.svg");
}
