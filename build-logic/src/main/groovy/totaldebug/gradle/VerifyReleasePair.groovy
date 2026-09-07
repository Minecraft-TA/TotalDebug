package totaldebug.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarFile

@CacheableTask
abstract class VerifyReleasePair extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract RegularFileProperty getModJar()
    @InputFile @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract RegularFileProperty getCompanionJar()
    @Input abstract Property<String> getReleaseVersion()
    @Input abstract Property<String> getDownloadUri()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction void verify() {
        File mod = modJar.get().asFile
        File companion = companionJar.get().asFile
        String companionHash = GenerateCompanionReleaseMetadata.sha256(companion)
        new JarFile(mod).withCloseable { jar ->
            def descriptor = jar.getJarEntry(ReleaseModJar.DESCRIPTOR)
            if (descriptor == null) throw new GradleException('Release mod has no Companion descriptor.')
            def properties = new Properties()
            jar.getInputStream(descriptor).withCloseable { properties.load(it) }
            if (properties.getProperty('version') != releaseVersion.get()
                    || properties.getProperty('artifact') != companion.name
                    || properties.getProperty('downloadUri') != downloadUri.get()
                    || properties.getProperty('sha256') != companionHash
                    || jar.manifest.mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION) != releaseVersion.get()) {
                throw new GradleException('Packaged mod metadata does not match the staged Companion release.')
            }
        }
        def output = outputDirectory.get().asFile.toPath()
        Files.createDirectories(output)
        Files.writeString(output.resolve('TotalDebugCompanion.jar.sha256'), "${companionHash}  ${companion.name}\n")
        Files.writeString(output.resolve('total_debug.jar.sha256'), "${GenerateCompanionReleaseMetadata.sha256(mod)}  total_debug.jar\n")
    }
}
