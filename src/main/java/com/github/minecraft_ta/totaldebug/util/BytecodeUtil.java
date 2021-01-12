package com.github.minecraft_ta.totaldebug.util;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class BytecodeUtil {

    /**
     * obfuscated name -> pair of real name and class member mappings (obfuscated name -> real name)
     */
    private static final Map<String, Pair<String, Map<String, String>>> mcpMappings = new HashMap<>();
    private static final Map<String, String> forgeMappings = new HashMap<>();

    private BytecodeUtil() {
    }

    public static void loadMappings() {
        try {
            InputStream forgeMappingsStream = BytecodeUtil.class.getClassLoader().getResourceAsStream("forge_mappings.csv");
            InputStream mcpMappingsStream = BytecodeUtil.class.getClassLoader().getResourceAsStream("mcp_mappings.tsrg");

            if (forgeMappingsStream == null || mcpMappingsStream == null)
                throw new IllegalStateException("Forge or mcp mappings not found");

            IOUtils.readLines(forgeMappingsStream, StandardCharsets.UTF_8).stream()
                    .map(s -> s.split(","))
                    .forEach((ar) -> forgeMappings.put(ar[0], ar[1]));

            //load all mappings
            Map<String, String> currentMap = null;
            for (String line : IOUtils.readLines(mcpMappingsStream, StandardCharsets.UTF_8)) {
                int indexOfFirstSpace = line.indexOf(' ');

                if (line.startsWith("\t")) { //class field or method
                    if (currentMap == null)
                        throw new IllegalStateException("Hit unexpected \t while parsing mcp mappings");

                    String name = line.substring(1, indexOfFirstSpace);
                    String other = line.substring(indexOfFirstSpace + 1);

                    if (!other.startsWith("(")) { //field
                        currentMap.put(name, forgeMappings.getOrDefault(other, other));
                    } else { //method
                        int indexOfLastSpace = other.lastIndexOf(' ');
                        String desc = other.substring(0, indexOfLastSpace);

                        String newName = other.substring(indexOfLastSpace + 1);
                        newName = forgeMappings.getOrDefault(newName, newName);

                        currentMap.put(name + desc, newName + desc);
                    }
                } else { // start of new class
                    mcpMappings.put(line.substring(0, indexOfFirstSpace),
                            Pair.of(
                                    line.substring(indexOfFirstSpace + 1),
                                    (currentMap = new HashMap<>())
                            )
                    );
                }
            }

            //second pass to remap return values and parameters
            mcpMappings.forEach((obfuscatedClassName, pair) -> {
                Map<String, String> classMemberMap = pair.getRight();

                classMemberMap.forEach((k, v) -> {
                    int closingBracketIndex = v.lastIndexOf(')');
                    int openingBracketIndex = v.indexOf('(');

                    //not a method
                    if (openingBracketIndex == -1 || closingBracketIndex == -1)
                        return;

                    classMemberMap.put(k, v.substring(0, openingBracketIndex) + remapMethodDesc(v.substring(openingBracketIndex + 1)));
                });
            });
        } catch (IOException e) {
            TotalDebug.LOGGER.error("Error while loading mappings", e);
        }
    }

    public static byte[] getBytecode(Class<?> clazz) {
        String name = clazz.getName();

        String codeSource = clazz.getProtectionDomain().getCodeSource().getLocation().toString();
        if (codeSource.startsWith("jar"))
            codeSource = codeSource.substring(codeSource.lastIndexOf('!') + 2);
        else
            codeSource = clazz.getName().replace(".", "/") + ".class";

        try (InputStream inputStream = clazz.getClassLoader().getResourceAsStream(codeSource)) {
            if (inputStream == null)
                throw new RuntimeException("Class " + name + " not found");

            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int r;
            while ((r = inputStream.read(buffer, 0, buffer.length)) != -1) {
                out.write(buffer, 0, r);
            }

            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unable to load resource " + codeSource);
        }
    }

    public static String remapMethodDesc(String v) {
        int closingBracketIndex = v.lastIndexOf(')');
        int openingBracketIndex = v.indexOf('(');

        StringBuilder parameters = new StringBuilder(v.substring(openingBracketIndex + 1, closingBracketIndex));
        //remap parameters
        for (int i = 0; i < parameters.length(); i++) {
            char c = parameters.charAt(i);
            if (c != 'L')
                continue;

            int end = parameters.indexOf(";", i + 1);
            String name = parameters.substring(i + 1, end);

            Pair<String, Map<String, String>> mappedPair = mcpMappings.get(name);
            if (mappedPair != null) {
                parameters.replace(i + 1, end, mappedPair.getLeft());
                i += mappedPair.getLeft().length() - name.length();
            }
        }

        //remap return value
        String returnValue = v.substring(closingBracketIndex + 1);
        int indexOfL = returnValue.indexOf("L");
        if (indexOfL != -1 && indexOfL < 2) {
            String type = returnValue.substring(indexOfL + 1, returnValue.length() - 1);
            Pair<String, Map<String, String>> mappedPair = mcpMappings.get(type);
            if (mappedPair != null)
                returnValue = (indexOfL == 1 ? "[L" : "L") + mappedPair.getLeft() + ";";
        }

        return "(" + parameters.toString() + ")" + returnValue;
    }

    public static ClassWriter getRemappedClass(Class<?> clazz) {
        byte[] bytecode = getBytecode(clazz);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassReader reader = new ClassReader(bytecode);

        //TODO: write custom remapper

        return writer;
    }

    public static String getRemappedForgeName(String s) {
        return forgeMappings.get(s);
    }
}
