package com.github.minecraft_ta.totaldebug.util;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassUtil {

    /**
     * obfuscated name -> pair of real name and class member mappings (obfuscated name -> real name)
     */
    private static final Map<String, Pair<String, Map<String, String>>> mappings = new HashMap<>();

    private ClassUtil() {
    }

    public static void loadMappings() {
        try {
            //TODO: apply mappings needs to handle parameter and return value remapping and array fields values ()[Lpc$a; values
            InputStream forgeMappingsStream = ClassUtil.class.getClassLoader().getResourceAsStream("forge_mappings.csv");
            List<String> forgeMappingsLines = IOUtils.readLines(forgeMappingsStream, StandardCharsets.UTF_8);

            Map<String, String> forgeMap = forgeMappingsLines.stream().map(s -> s.split(",")).collect(Collectors.toMap((s) -> s[0], (s) -> s[1]));

            InputStream mcpMappingsStream = ClassUtil.class.getClassLoader().getResourceAsStream("mcp_mappings.tsrg");
            List<String> mcpMappingsLines = IOUtils.readLines(mcpMappingsStream, StandardCharsets.UTF_8);

            //load all mappings
            Map<String, String> currentMap = null;
            for (String line : mcpMappingsLines) {
                int indexOfFirstSpace = line.indexOf(' ');

                if (line.startsWith("\t")) { //class field or method
                    if (currentMap == null)
                        throw new IllegalStateException("Hit unexpected \t while parsing mcp mappings");

                    String name = line.substring(1, indexOfFirstSpace);
                    String other = line.substring(indexOfFirstSpace + 1);

                    if (!other.startsWith("(")) { //field
                        currentMap.put(name, forgeMap.getOrDefault(other, other));
                    } else { //method
                        int indexOfLastSpace = other.lastIndexOf(' ');
                        String desc = other.substring(0, indexOfLastSpace);

                        String newName = other.substring(indexOfLastSpace + 1);
                        newName = forgeMap.getOrDefault(newName, newName);

                        currentMap.put(name + desc, newName + desc);
                    }
                } else { // start of new class
                    mappings.put(line.substring(0, indexOfFirstSpace),
                            Pair.of(
                                    line.substring(indexOfFirstSpace + 1),
                                    (currentMap = new HashMap<>())
                            )
                    );
                }
            }

            //second pass to remap return values and parameters
            mappings.forEach((obfuscatedClassName, pair) -> {
                Map<String, String> classMemberMap = pair.getRight();

                classMemberMap.forEach((k, v) -> {
                    int closingBracketIndex = v.lastIndexOf(')');
                    int openingBracketIndex = v.indexOf('(');

                    //not a method
                    if (openingBracketIndex == -1 || closingBracketIndex == -1)
                        return;

                    StringBuilder parameters = new StringBuilder(v.substring(openingBracketIndex + 1, closingBracketIndex));
                    //remap parameters
                    for (int i = 0; i < parameters.length(); i++) {
                        char c = parameters.charAt(i);
                        if (c != 'L')
                            continue;

                        int end = parameters.indexOf(";", i + 1);
                        String name = parameters.substring(i + 1, end);

                        Pair<String, Map<String, String>> mappedPair = mappings.get(name);
                        if (mappedPair != null) {
                            parameters.replace(i + 1, end, mappedPair.getLeft());
                            i += mappedPair.getLeft().length() - name.length();
                        }
                    }

                    //remap return value
                    String returnValue = v.substring(closingBracketIndex + 1);
                    //TODO:

                    classMemberMap.put(k, v.substring(0, openingBracketIndex) + "(" + parameters.toString() + ")" + returnValue);
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
            TotalDebug.LOGGER.error("Unable to load resource " + codeSource, e);
        }

        return null;
    }
}
