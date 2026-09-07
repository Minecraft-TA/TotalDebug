package totaldebug.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

import java.nio.file.Files
import java.security.MessageDigest

@CacheableTask
abstract class GenerateCompanionReleaseMetadata extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getCompanionJar()
    @Input abstract Property<String> getReleaseVersion()
    @Input abstract Property<String> getPublishedVersion()
    @Input abstract Property<String> getDownloadUri()
    @OutputFile abstract RegularFileProperty getOutputFile()

    @TaskAction void generate() {
        String version = releaseVersion.get()
        if (!(version ==~ /[0-9][A-Za-z0-9.+-]*/) || version == publishedVersion.get()) {
            throw new GradleException('Set -PreleaseVersion to a new, unused release version.')
        }
        URI uri = URI.create(downloadUri.get())
        if (uri.scheme != 'https' || !uri.path.endsWith('/TotalDebugCompanion.jar')) {
            throw new GradleException('Companion release URL must be an immutable HTTPS asset URL.')
        }
        String hash = sha256(companionJar.get().asFile)
        def output = outputFile.get().asFile.toPath()
        Files.createDirectories(output.parent)
        Files.writeString(output, "version=${version}\nartifact=TotalDebugCompanion.jar\ndownloadUri=${uri}\nsha256=${hash}\n")
    }

    static String downloadUriFor(String version) {
        "https://github.com/Minecraft-TA/TotalDebug/releases/download/v${version}/TotalDebugCompanion.jar"
    }

    static String sha256(File file) {
        def digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { input ->
            byte[] buffer = new byte[64 * 1024]
            int count
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count)
        }
        HexFormat.of().formatHex(digest.digest())
    }
}
