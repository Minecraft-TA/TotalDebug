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
abstract class GenerateParchmentParameterIndex extends DefaultTask {
    private static final int MAGIC = 0x5444504E
    private static final int FORMAT_VERSION = 1

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getParchmentArchive()

    @Input
    abstract Property<String> getMinecraftVersion()

    @Input
    abstract Property<String> getMappingsVersion()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void generate() {
        def archive = new ZipFile(parchmentArchive.get().asFile)
        def data
        try {
            def entry = archive.getEntry('parchment.json')
            if (entry == null) {
                throw new GradleException('Parchment archive does not contain parchment.json')
            }
            data = archive.getInputStream(entry).withReader('UTF-8') { reader ->
                new JsonSlurper().parse(reader)
            }
        } finally {
            archive.close()
        }

        def methods = data.classes.collectMany { mappedClass ->
            (mappedClass.methods ?: []).findAll { method -> method.parameters }
                    .collect { method ->
                        [
                                owner     : mappedClass.name,
                                name      : method.name,
                                descriptor: method.descriptor,
                                parameters: method.parameters.sort { parameter -> parameter.index },
                        ]
                    }
        }.sort { left, right ->
            left.owner <=> right.owner ?:
                    left.name <=> right.name ?:
                    left.descriptor <=> right.descriptor
        }

        def output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.withDataOutputStream { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(FORMAT_VERSION)
            stream.writeUTF(minecraftVersion.get())
            stream.writeUTF(mappingsVersion.get())
            stream.writeInt(methods.size())
            methods.each { method ->
                stream.writeUTF(method.owner)
                stream.writeUTF(method.name)
                stream.writeUTF(method.descriptor)
                stream.writeInt(method.parameters.size())
                method.parameters.each { parameter ->
                    stream.writeInt(parameter.index as int)
                    stream.writeUTF(parameter.name)
                }
            }
        }
    }
}
