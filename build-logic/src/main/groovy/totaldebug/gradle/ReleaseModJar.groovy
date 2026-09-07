package totaldebug.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/** Replaces the pairing descriptor while retaining the final ModDev/JarJar contents. */
@CacheableTask
abstract class ReleaseModJar extends DefaultTask {
    static final String DESCRIPTOR = 'META-INF/totaldebug/companion-release.properties'
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getModJar()
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getReleaseMetadata()
    @Input abstract Property<String> getReleaseVersion()
    @OutputFile abstract RegularFileProperty getOutputFile()

    @TaskAction void packageRelease() {
        def output = outputFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        new JarFile(modJar.get().asFile).withCloseable { original ->
            if (original.manifest == null || original.getJarEntry(DESCRIPTOR) == null) {
                throw new GradleException('Expected the complete mod artifact with manifest and Companion descriptor.')
            }
            def manifest = original.manifest
            manifest.mainAttributes.put(Attributes.Name.IMPLEMENTATION_VERSION, releaseVersion.get())
            new JarOutputStream(Files.newOutputStream(output)).withCloseable { jar ->
                def manifestEntry = new JarEntry(JarFile.MANIFEST_NAME)
                manifestEntry.time = 0
                jar.putNextEntry(manifestEntry)
                manifest.write(jar)
                jar.closeEntry()
                original.entries().toList().findAll { !it.directory && it.name != JarFile.MANIFEST_NAME }
                        .sort { it.name }.each { entry ->
                    def replacement = new JarEntry(entry.name)
                    replacement.time = 0
                    jar.putNextEntry(replacement)
                    if (entry.name == DESCRIPTOR) {
                        Files.copy(releaseMetadata.get().asFile.toPath(), jar)
                    } else {
                        original.getInputStream(entry).withCloseable { it.transferTo(jar) }
                    }
                    jar.closeEntry()
                }
            }
        }
    }
}
