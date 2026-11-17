import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/**
 * SPEC §7: fails the build if the merged manifest asks for any permission outside the allowlist.
 *
 * Allowlist entries may contain `{applicationId}`, which is replaced with the variant's id.
 * `android.permission.INTERNET` is always rejected, even if it's on the list.
 */
@CacheableTask
abstract class CheckPermissionsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val allowlist: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val appId = applicationId.get()
        val allowed = allowlist.get().map { it.replace("{applicationId}", appId) }.toSet()

        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(mergedManifest.get().asFile)
        val requested = sequenceOf("uses-permission", "uses-permission-sdk-23")
            .flatMap { tag ->
                val nodes = doc.getElementsByTagName(tag)
                (0 until nodes.length).asSequence().map { nodes.item(it) as Element }
            }
            .map { it.getAttributeNS(ANDROID_NS, "name") }
            .toSortedSet()

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(requested.joinToString(separator = "\n", postfix = "\n"))
        }

        val problems = buildList {
            if (INTERNET in requested) add("$INTERNET is never allowed (SPEC §1, §7)")
            requested.filter { it != INTERNET && it !in allowed }
                .forEach { add("$it is not on the permission allowlist") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Permission check failed for $appId:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nIf a new permission is really needed, ask first, then add it to the allowlist in " +
                    "app/build.gradle.kts and record it in docs/DECISIONS.md.",
            )
        }
        logger.lifecycle("Permission check passed for $appId: ${requested.joinToString()}")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val INTERNET = "android.permission.INTERNET"
    }
}
