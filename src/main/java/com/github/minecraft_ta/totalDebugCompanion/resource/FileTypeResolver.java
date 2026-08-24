package com.github.minecraft_ta.totalDebugCompanion.resource;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import java.util.Locale;
import java.util.Set;

public final class FileTypeResolver {

    public static final String SYNTAX_STYLE_TOML = "text/toml";
    public static final String SYNTAX_STYLE_MANIFEST = "text/manifest";

    private static final Set<String> OTHER_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "gif", "webp", "bmp", "ico");
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "bin", "dat", "nbt", "ogg", "wav", "mp3", "dll", "so", "dylib",
            "ttf", "otf", "woff", "woff2", "jar", "zip"
    );

    private FileTypeResolver() {
    }

    public static ResourceFileType resolve(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.equals("module-info.class")) {
            return type(ResourceFileType.Kind.CLASS, null, Icons.MODULE, "Java module");
        }
        if (lowerName.equals("manifest.mf") || lowerName.equals("manifest")) {
            return text(SYNTAX_STYLE_MANIFEST, Icons.MANIFEST_FILE, "JAR manifest");
        }
        if (lowerName.equals("dockerfile")) {
            return text(RSyntaxTextArea.SYNTAX_STYLE_DOCKERFILE, Icons.TEXT_FILE, "Dockerfile");
        }
        if (lowerName.equals(".env")) {
            return text(RSyntaxTextArea.SYNTAX_STYLE_ENV, Icons.CONFIG_FILE, "Environment file");
        }

        String extension = extension(lowerName);
        if (OTHER_IMAGE_EXTENSIONS.contains(extension)) {
            return type(ResourceFileType.Kind.BINARY, null, Icons.IMAGE_FILE, "Unsupported image");
        }
        if (BINARY_EXTENSIONS.contains(extension)) {
            return type(ResourceFileType.Kind.BINARY, null, binaryIcon(extension), "Binary file");
        }

        return switch (extension) {
            case "class" -> type(ResourceFileType.Kind.CLASS, null, Icons.JAVA_CLASS, "Java class");
            case "java" -> text(RSyntaxTextArea.SYNTAX_STYLE_JAVA, Icons.JAVA_FILE, "Java");
            case "png" -> type(ResourceFileType.Kind.PNG, null, Icons.IMAGE_FILE, "PNG image");
            case "toml" -> text(SYNTAX_STYLE_TOML, Icons.CONFIG_FILE, "TOML");
            case "cfg", "conf", "config", "ini" -> text(RSyntaxTextArea.SYNTAX_STYLE_INI, Icons.CONFIG_FILE, "Configuration");
            case "json", "mcmeta" -> text(RSyntaxTextArea.SYNTAX_STYLE_JSON, Icons.JSON_FILE, "JSON");
            case "mf" -> text(SYNTAX_STYLE_MANIFEST, Icons.MANIFEST_FILE, "JAR manifest");
            case "properties" -> text(RSyntaxTextArea.SYNTAX_STYLE_PROPERTIES_FILE, Icons.PROPERTIES_FILE, "Properties");
            case "lang" -> text(RSyntaxTextArea.SYNTAX_STYLE_PROPERTIES_FILE, Icons.RESOURCE_BUNDLE, "Language bundle");
            case "xml" -> text(RSyntaxTextArea.SYNTAX_STYLE_XML, Icons.XML_FILE, "XML");
            case "yaml", "yml" -> text(RSyntaxTextArea.SYNTAX_STYLE_YAML, Icons.YAML_FILE, "YAML");
            case "md", "markdown" -> text(RSyntaxTextArea.SYNTAX_STYLE_MARKDOWN, Icons.MARKDOWN_FILE, "Markdown");
            case "html", "htm", "xhtml" -> text(RSyntaxTextArea.SYNTAX_STYLE_HTML, Icons.HTML_FILE, "HTML");
            case "css" -> text(RSyntaxTextArea.SYNTAX_STYLE_CSS, Icons.CSS_FILE, "CSS");
            case "js", "mjs", "cjs" -> text(RSyntaxTextArea.SYNTAX_STYLE_JAVASCRIPT, Icons.JAVASCRIPT_FILE, "JavaScript");
            case "csv", "tsv" -> text(RSyntaxTextArea.SYNTAX_STYLE_CSV, Icons.TEXT_FILE, "Delimited text");
            case "sh", "bash", "zsh" -> text(RSyntaxTextArea.SYNTAX_STYLE_UNIX_SHELL, Icons.TEXT_FILE, "Shell script");
            case "bat", "cmd" -> text(RSyntaxTextArea.SYNTAX_STYLE_WINDOWS_BATCH, Icons.TEXT_FILE, "Batch script");
            case "kt", "kts" -> text(RSyntaxTextArea.SYNTAX_STYLE_KOTLIN, Icons.TEXT_FILE, "Kotlin");
            case "groovy", "gradle" -> text(RSyntaxTextArea.SYNTAX_STYLE_GROOVY, Icons.TEXT_FILE, "Groovy");
            case "py" -> text(RSyntaxTextArea.SYNTAX_STYLE_PYTHON, Icons.TEXT_FILE, "Python");
            case "sql" -> text(RSyntaxTextArea.SYNTAX_STYLE_SQL, Icons.TEXT_FILE, "SQL");
            case "txt", "log", "glsl", "fsh", "vsh", "accesswidener", "at" ->
                    text(RSyntaxTextArea.SYNTAX_STYLE_NONE, Icons.TEXT_FILE, "Text");
            default -> type(ResourceFileType.Kind.UNKNOWN, RSyntaxTextArea.SYNTAX_STYLE_NONE, Icons.TEXT_FILE, "File");
        };
    }

    private static ResourceFileType text(String syntaxStyle, javax.swing.Icon icon, String description) {
        return type(ResourceFileType.Kind.TEXT, syntaxStyle, icon, description);
    }

    private static ResourceFileType type(
            ResourceFileType.Kind kind,
            String syntaxStyle,
            javax.swing.Icon icon,
            String description
    ) {
        return new ResourceFileType(kind, syntaxStyle, icon, description);
    }

    private static javax.swing.Icon binaryIcon(String extension) {
        return switch (extension) {
            case "jar", "zip" -> Icons.JAR_FILE;
            case "ttf", "otf", "woff", "woff2" -> Icons.FONT_FILE;
            default -> Icons.BINARY_FILE;
        };
    }

    private static String extension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        return separator == -1 ? "" : fileName.substring(separator + 1);
    }
}
