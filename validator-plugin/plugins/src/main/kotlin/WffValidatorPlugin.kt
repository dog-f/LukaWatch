/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

const val ASSEMBLE_DEBUG_TASK = "assembleDebug"
const val BUNDLE_DEBUG_TASK = "bundleDebug"
const val VALIDATE_TASK = "validateWff"
const val MEMORY_FOOTPRINT_TASK = "memoryFootprint"
const val DOWNLOAD_VALIDATOR_TASK = "downloadWffValidator"
const val DOWNLOAD_MEMORY_FOOTPRINT_TASK = "downloadMemoryFootprint"
const val INSTALL_TASK = "validateWffAndInstall"

private const val DEFAULT_RELEASE_TAG = "release"
private const val VALIDATOR_URL =
    "https://github.com/google/watchface/releases/download/%s/wff-validator.jar"
private const val VALIDATOR_PATH = "validator/validator-%s.jar"
private const val VALIDATOR_OUTPUT_PATH = "validator/validator-%s.txt"

private const val MEMORY_FOOTPRINT_URL =
    "https://github.com/google/watchface/releases/download/%s/memory-footprint.jar"
private const val MEMORY_FOOTPRINT_PATH = "memory-footprint/memory-footprint-%s.jar"
private const val MEMORY_FOOTPRINT_OUTPUT_PATH = "memory-footprint/memory-footprint-%s.txt"

class WffValidatorPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Parameter optionally set on the command-line for the whole project, indicating which tag
        // to use for releases downloaded from the watch face repo. Defaults to "release" but could
        // be set to "latest".
        val toolReleaseTag = project.properties["release"] ?: DEFAULT_RELEASE_TAG

        val validatorUrl = VALIDATOR_URL.format(toolReleaseTag)
        val validatorJar = VALIDATOR_PATH.format(toolReleaseTag)
        val validatorOutput = VALIDATOR_OUTPUT_PATH.format(toolReleaseTag)

        val memoryFootprintUrl = MEMORY_FOOTPRINT_URL.format(toolReleaseTag)
        val memoryFootprintJar = MEMORY_FOOTPRINT_PATH.format(toolReleaseTag)
        val memoryFootprintOutput = MEMORY_FOOTPRINT_OUTPUT_PATH.format(toolReleaseTag)

        val validatorDownloadTask =
            project.tasks.register<WffToolDownloadTask>(DOWNLOAD_VALIDATOR_TASK) {
                val validatorPath = project.layout.buildDirectory.file(validatorJar)
                this.toolUrl.set(validatorUrl)
                this.toolJarPath.set(validatorPath)
            }

        val memoryFootprintDownloadTask =
            project.tasks.register<WffToolDownloadTask>(DOWNLOAD_MEMORY_FOOTPRINT_TASK) {
                val memoryFootprintPath = project.layout.buildDirectory.file(memoryFootprintJar)
                this.toolUrl.set(memoryFootprintUrl)
                this.toolJarPath.set(memoryFootprintPath)
            }

        val androidComponents =
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

        androidComponents.onVariants(androidComponents.selector().withName("debug")) { variant ->
            val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)

            project.tasks.register<ValidateWffFilesTask>(VALIDATE_TASK) {
                val wffFileCollection = getWffFileCollection(project)
                val validatorOutputPath = project.layout.buildDirectory.file(validatorOutput)
                validatorJarPath.set(validatorDownloadTask.flatMap { it.toolJarPath })
                validatorOutputFile.set(validatorOutputPath)
                wffFiles.setFrom(wffFileCollection)
                manifestFile.set(mergedManifest)
            }

            val apkDirectoryProvider = variant.artifacts.get(SingleArtifact.APK)
            val loader = variant.artifacts.getBuiltArtifactsLoader()

            project.tasks.register<AdbInstallTask>(INSTALL_TASK) {
                apkLocation.set(apkDirectoryProvider)
                artifactsLoader.set(loader)
            }

            project.tasks.register<MemoryFootprintTask>(MEMORY_FOOTPRINT_TASK) {
                val memoryFootprintOutputPath =
                    project.layout.buildDirectory.file(memoryFootprintOutput)
                memoryFootprintJarPath.set(memoryFootprintDownloadTask.flatMap { it.toolJarPath })
                apkLocation.set(apkDirectoryProvider)
                artifactsLoader.set(loader)
                manifestFile.set(mergedManifest)
                memoryFootprintOutputFile.set(memoryFootprintOutputPath)
            }
        }

        project.afterEvaluate { proj ->
            val validationTask = proj.tasks.named(VALIDATE_TASK)
            proj.tasks.named<Task>(ASSEMBLE_DEBUG_TASK).configure { it.dependsOn(validationTask) }
            proj.tasks.named<Task>(BUNDLE_DEBUG_TASK).configure { it.dependsOn(validationTask) }

            val assembleDebugTask = proj.tasks.named(ASSEMBLE_DEBUG_TASK)
            proj.tasks.named(INSTALL_TASK).configure {
                it.dependsOn(assembleDebugTask)
            }
            proj.tasks.named(MEMORY_FOOTPRINT_TASK).configure {
                it.dependsOn(assembleDebugTask)
            }
        }
    }

    private fun getWffFileCollection(project: Project): FileCollection {
        return project.layout.projectDirectory.dir("src${File.separator}main${File.separator}res").asFileTree
            .filter { it.isFile }
            .filter { it.name.endsWith(".xml") }
            .filter { it.parentFile.name.startsWith("raw") }
    }
}
