package totaldebug.gradle

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import javax.inject.Inject
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.util.zip.ZipFile

@CacheableTask
abstract class GenerateThirdPartyNotices extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract ConfigurableFileCollection getDependencyArchives()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @Inject
    abstract FileSystemOperations getFileOperations()

    @TaskAction
    void generate() {
        def staged = Files.createTempDirectory(temporaryDir.toPath(), 'notices-')
        try {
            def inventory = []
            def names = new HashSet<String>()
            dependencyArchives.files.findAll { it.name.endsWith('.jar') }.sort { it.name }.each { archive ->
                if (!names.add(archive.name)) {
                    throw new GradleException("Cannot namespace duplicate dependency filename: ${archive.name}")
                }
                def entries = []
                def namespace = staged.resolve(archive.name)
                new ZipFile(archive).withCloseable { zip ->
                    zip.entries().each { entry ->
                        def lower = entry.name.toLowerCase(Locale.ROOT)
                        def filename = lower.substring(lower.lastIndexOf('/') + 1)
                        if (!entry.directory && !lower.endsWith('.class')
                                && (filename.contains('license') || filename.contains('notice')
                                || filename.startsWith('copying') || filename.startsWith('copyright')
                                || filename == 'about.html' || lower.startsWith('about_files/')
                                || lower.contains('/licenses/') || lower.contains('/legal/'))) {
                            def target = namespace.resolve(entry.name).normalize()
                            if (!target.startsWith(namespace)) {
                                throw new GradleException("Invalid notice path in ${archive.name}: ${entry.name}")
                            }
                            Files.createDirectories(target.parent)
                            zip.getInputStream(entry).withCloseable { input ->
                                Files.copy(input, target)
                            }
                            entries.add(entry.name)
                        }
                    }
                }
                inventory.add([archive: archive.name,
                               notices: entries.sort()])
            }
            Files.writeString(staged.resolve('INDEX.json'),
                    JsonOutput.prettyPrint(JsonOutput.toJson(inventory)) + '\n')
            fileOperations.sync { from staged; into outputDirectory }
            logger.lifecycle("Preserved notices from ${inventory.size()} dependency archives")
        } finally {
            fileOperations.delete { delete staged }
        }
    }
}
