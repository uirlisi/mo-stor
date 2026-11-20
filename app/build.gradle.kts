import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

/** SPEC §7. Ask before adding anything here, and record it in docs/DECISIONS.md. */
val permissionAllowlist = listOf(
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    // Declared by androidx.core in the app's own namespace (signature level); approved 2026-11-10.
    "{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    // Bluetooth sync (SPEC §7, §9); approved 2026-11-14. Scan carries neverForLocation.
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_ADVERTISE",
)

// Release signing comes from the git-ignored local.properties (see docs/DECISIONS.md).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
val signingKeys = listOf("mostor.keystore.path", "mostor.keystore.password", "mostor.key.alias", "mostor.key.password")
val missingSigningKeys = signingKeys.filter { localProps.getProperty(it).isNullOrBlank() }

// Builds prod without the release key (-Pmostor.unsigned=true): for anyone building from source,
// and to check that the build is reproducible (it equals the signed release minus the signature).
val unsignedProd = providers.gradleProperty("mostor.unsigned").orNull == "true"

android {
    namespace = "io.github.uirlisi.mostor"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.uirlisi.mostor"
        minSdk = 31
        targetSdk = 37
        versionCode = 21
        versionName = "0.7.0"
    }

    signingConfigs {
        create("release") {
            if (missingSigningKeys.isEmpty()) {
                storeFile = file(localProps.getProperty("mostor.keystore.path"))
                storePassword = localProps.getProperty("mostor.keystore.password")
                keyAlias = localProps.getProperty("mostor.key.alias")
                keyPassword = localProps.getProperty("mostor.key.password")
            }
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("prod") {
            dimension = "env"
            signingConfig = if (unsignedProd) null else signingConfigs.getByName("release")
        }
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Reproducible APKs: no dependency list encrypted for Google in the signing block,
    // and no git commit id (private commits differ from the public ones the same files come from).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildTypes.configureEach {
        vcsInfo.include = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric reaches into JDK internals; the JDK running Gradle (JBR 25) needs these opened.
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
            )
        }
    }

    // MigrationTestHelper reads the exported schemas as assets. Under Robolectric those come from the
    // app variant, so the dev flavor carries them (about 14 KB); prod never does.
    sourceSets.getByName("dev").assets.srcDir("$projectDir/schemas")
}

// Exported Room schemas are committed: every released version must stay (SPEC, CLAUDE.md: migrations).
room {
    schemaDirectory("$projectDir/schemas")
}

// Room's generated code (MoStorDb_Impl and the DAOs) is always produced in full and never restored
// from the build cache: an incremental KSP run once dropped MoStorDb_Impl, and the cache then brought
// the incomplete output back even after `clean` (DECISIONS, 2026-11-11). Without it the app can't open
// its database. The project is small, so this costs seconds.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach {
    outputs.cacheIf { false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

androidComponents {
    // No debug build may ever carry the prod application id.
    beforeVariants { variant ->
        if (variant.flavorName == "prod" && variant.buildType == "debug") variant.enable = false
    }

    onVariants(selector().withBuildType("release")) { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val gates = mutableListOf<TaskProvider<*>>()

        gates += tasks.register<CheckPermissionsTask>("check${cap}Permissions") {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest requests a permission outside the allowlist."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            applicationId.set(variant.applicationId)
            allowlist.set(permissionAllowlist)
            report.set(layout.buildDirectory.file("reports/permissions/${variant.name}.txt"))
        }

        if (variant.flavorName == "prod" && !unsignedProd) {
            val missing = missingSigningKeys
            gates += tasks.register("check${cap}Signing") {
                group = "verification"
                description = "Fails if the release key isn't configured in local.properties."
                doLast {
                    if (missing.isNotEmpty()) {
                        throw GradleException(
                            "Release signing is not configured. Add to local.properties: ${missing.joinToString()}. " +
                                "See docs/DECISIONS.md (Release key storage).",
                        )
                    }
                }
            }
        }

        tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" || it.name == "install$cap" }
            .configureEach { dependsOn(gates) }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Room DAO and migration tests run on the JVM under Robolectric (docs/DECISIONS.md).
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
