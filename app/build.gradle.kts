import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.relay.agents"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.claudeagents.team"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BUILD_STAMP", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "local"}\"")
        // GeckoView ثقيل: نكتفي بمعمارية arm64 (هواتف اليوم) لتصغير الحجم.
        // الافتراضي arm64؛ نسخة الاختبار على محاكيات x86 تُبنى بـ -Pabis=x86,x86_64
        val abis = (project.findProperty("abis") as String?)?.split(",")?.map { it.trim() } ?: listOf("arm64-v8a")
        ndk { abiFilters.addAll(abis) }
    }

    // مفتاح توقيع ثابت للنسخ التجريبية: كل بناء على GitHub Actions يوقَّع بنفس المفتاح،
    // فيُثبَّت التحديث فوق النسخة القديمة وتبقى بيانات التطبيق (الوكلاء والمشاريع).
    // (مفتاح debug عام وللاستخدام الشخصي فقط؛ لا تنشر التطبيق به.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":openvpn"))
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Credential Manager (تسجيل الدخول بحساب Google داخل التطبيق)
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    // نواة VPN (WireGuard الرسمية)
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    // محرك فايرفوكس لتبويبات المتصفح المعزولة
    // محرك فايرفوكس لتبويبات المتصفح المعزولة
    implementation("org.mozilla.geckoview:geckoview:145.0.20251124145406")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
