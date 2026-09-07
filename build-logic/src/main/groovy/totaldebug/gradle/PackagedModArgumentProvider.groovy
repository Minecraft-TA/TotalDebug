package totaldebug.gradle

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.process.CommandLineArgumentProvider

class PackagedModArgumentProvider implements CommandLineArgumentProvider {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    final Provider<RegularFile> artifact
    PackagedModArgumentProvider(Provider<RegularFile> artifact) { this.artifact = artifact }
    @Override Iterable<String> asArguments() {
        ["-Dtotaldebug.packagedMod=${artifact.get().asFile.absolutePath}".toString()]
    }
}
