package totaldebug.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.util.jar.JarFile

@CacheableTask
abstract class VerifyCompanionJar extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getExecutableJar()
    @OutputFile abstract RegularFileProperty getReportFile()

    @TaskAction void verify() {
        new JarFile(executableJar.get().asFile).withCloseable { jar ->
            def expected = [
                    'com/github/minecraft_ta/totalDebugCompanion/CompanionApp.class',
                    'com/github/minecraft_ta/totaldebug/protocol/CompanionProtocol.class',
                    'com/github/minecraft_ta/totaldebug/storage/RuntimeInventory.class',
                    'com/github/minecraft_ta/totaldebug/evaluation/PausedEvaluationBridge.class',
                    'totaldebug/parchment-parameters.bin',
                    'META-INF/third-party/vendored-jdt-ls/LICENSE',
                    'META-INF/third-party/INDEX.json'
            ]
            expected.each { name ->
                if (jar.getJarEntry(name) == null) throw new GradleException("Companion executable is missing ${name}")
            }
            if (jar.manifest.mainAttributes.getValue('Main-Class') != 'com.github.minecraft_ta.totalDebugCompanion.CompanionApp') {
                throw new GradleException('Companion executable has the wrong main class.')
            }
            def names = jar.entries().toList().collect { it.name }
            if (names.size() != names.toSet().size() || !names.any { it.startsWith('META-INF/services/') }) {
                throw new GradleException('Companion executable has duplicate entries or missing service metadata.')
            }
        }
        def report = reportFile.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(report, 'Companion executable contents verified.\n')
    }
}
