package org.maplibre.nativeffi.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes the Prefab package through which an Android app's CMake or ndk-build build links
 * `libmaplibre-native-c.so` and includes its headers. The AAR contains the package under `prefab/`
 * in addition to the `jni/` copy of the library that AGP packages into the app, so the app's native
 * code links the library that the app packages.
 *
 * The task takes each library from a `<abi>/libmaplibre-native-c.so` path in [libraries]. The
 * module declares the STL `none`: the library links libc++ statically and exports only `mln_*`
 * entry points, so the app can use any STL. Prefab rejects a shared library that declares
 * `c++_static`.
 */
@CacheableTask
abstract class GenerateAndroidPrefabPackage : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val headers: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val libraries: ConfigurableFileCollection

  @get:Input abstract val minSdk: Property<Int>

  @get:Input abstract val ndkMajorVersion: Property<Int>

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val output = outputDirectory.get().asFile
    output.deleteRecursively()
    val module = output.resolve("modules/$MODULE_NAME")
    module.mkdirs()

    output
      .resolve("prefab.json")
      .writeText("""{"schema_version": 2, "name": "$MODULE_NAME", "dependencies": []}""" + "\n")
    module
      .resolve("module.json")
      .writeText(
        """{"export_libraries": [], "library_name": "lib$MODULE_NAME", "android": {}}""" + "\n"
      )
    headers.get().asFile.copyRecursively(module.resolve("include"))

    val knownAbis = AndroidTarget.entries.map(AndroidTarget::ndkAbi)
    val packaged = libraries.asFileTree.files.filter { it.name == LIBRARY_FILE }
    check(packaged.isNotEmpty()) { "No $LIBRARY_FILE among the Prefab inputs" }
    packaged.forEach { library: File ->
      val abi = library.parentFile.name
      check(abi in knownAbis) { "$library is not under an Android ABI directory" }
      val abiDirectory = module.resolve("libs/android.$abi")
      check(!abiDirectory.exists()) { "More than one $LIBRARY_FILE for $abi" }
      abiDirectory.mkdirs()
      library.copyTo(abiDirectory.resolve(LIBRARY_FILE))
      abiDirectory
        .resolve("abi.json")
        .writeText(
          """{"abi": "$abi", "api": ${minSdk.get()}, "ndk": ${ndkMajorVersion.get()}, """ +
            """"stl": "none", "static": false}""" +
            "\n"
        )
    }
  }

  private companion object {
    const val MODULE_NAME = "maplibre-native-c"
    const val LIBRARY_FILE = "libmaplibre-native-c.so"
  }
}
