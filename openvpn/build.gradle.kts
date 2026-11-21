import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskProvider

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// نواة OpenVPN (ics-openvpn، رخصة GPLv2) مبنية من المصدر كمكتبة داخل التطبيق.
// تعديلات عن الأصل: مكتبة بدل تطبيق، بلا واجهة ui، compileSdk 36، وABI واحدة افتراضياً.
android {
    namespace = "de.blinkt.openvpn"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        buildConfigField("boolean", "openvpn3", "true")
        buildConfigField("String", "FLAVOR", "\"skeleton\"")
        val abis = (project.findProperty("abis") as String?)?.split(",")?.map { it.trim() } ?: listOf("arm64-v8a")
        ndk { abiFilters.addAll(abis) }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir("build/ovpnassets")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

abstract class GenerateSwigTask : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

fun registerGenSwigTask(variantName: String, variantDirName: String): TaskProvider<GenerateSwigTask> {
    val baseDir = layout.buildDirectory.dir("generated/source/ovpn3swig/${variantDirName}")
    return tasks.register<GenerateSwigTask>("generateOpenVPN3Swig${variantName}") {
        val genDir = baseDir.get().asFile.resolve("net/openvpn/ovpn3")
        outputDir.set(baseDir)
        doFirst { mkdir(genDir) }
        commandLine(
            listOf(
                "swig", "-outdir", genDir.absolutePath, "-outcurrentdir", "-c++", "-java",
                "-package", "net.openvpn.ovpn3",
                "-Isrc/main/cpp/openvpn3/client", "-Isrc/main/cpp/openvpn3/",
                "-DOPENVPN_PLATFORM_ANDROID",
                "-o", "${genDir}/ovpncli_wrap.cxx", "-oh", "${genDir}/ovpncli_wrap.h",
                "src/main/cpp/openvpn3/client/ovpncli.i"
            )
        )
        inputs.files("src/main/cpp/openvpn3/client/ovpncli.i")
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val execTask = registerGenSwigTask(variant.name, variant.name.replace("-", "/"))
        variant.sources.java?.addGeneratedSourceDirectory(execTask, GenerateSwigTask::outputDir)
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
}
