package com.github.minecraft_ta.totaldebug.command.decompile;

import io.github.classgraph.*;
import net.minecraft.command.ICommandSender;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class ClassSubCommand extends DecompileCommand.DecompileClassSubCommand {

    @Override
    public Class<?> getClassFromArg(@Nonnull String s) {
        try {
            return Class.forName(s);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length != 1 || args[0].isEmpty())
            return Collections.emptyList();

        String path = args[0];

        int dotCount = StringUtils.countMatches(path, ".");

        int lastIndexOfDot = path.lastIndexOf('.');
        if (lastIndexOfDot != -1)
            path = path.substring(0, lastIndexOfDot);

        try (ScanResult result = new ClassGraph()
                .acceptPackages(path + "*")
                .enableClassInfo().scan()) {

            ClassInfoList classInfo = result.getAllClasses();
            PackageInfoList packageInfo = result.getPackageInfo();

            List<String> options = new LinkedList<>();
            for (ClassInfo info : classInfo) {
                if (StringUtils.countMatches(info.getName(), ".") <= dotCount)
                    options.add(info.getName());
            }
            for (PackageInfo info : packageInfo) {
                if (StringUtils.countMatches(info.getName(), ".") <= dotCount)
                    options.add(info.getName());
            }

            return getListOfStringsMatchingLastWord(args, options.toArray(new String[0]));
        }
    }

    @Nonnull
    @Override
    public String getCommandName() {
        return "class";
    }
}
