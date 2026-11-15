import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Kho JitPack chứa công cụ build của CloudStream
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")

        // DÒNG ĐÃ SỬA LỖI: ghim đúng một bản cố định (81b1d424d2) thay vì "master-SNAPSHOT".
        // "master-SNAPSHOT" = bản mới nhất, mà JitPack hiện không còn phát bản mới nhất nên build bị lỗi.
        classpath("com.github.recloudstream:gradle:81b1d424d2")

        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // Khi chạy trên GitHub Actions, GITHUB_REPOSITORY tự có tên repo của bạn
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "alexwilon337-del/KhoPhimCS31")
    }

    android {
        namespace = "com.example"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8) // Bắt buộc
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Các lớp (class) của CloudStream để viết plugin
        cloudstream("com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib")) // Các hàm cơ bản của Kotlin
        implementation("com.github.Blatzar:NiceHttp:0.4.11") // Thư viện tải trang
        implementation("org.jsoup:jsoup:1.18.3") // Đọc HTML

        // QUAN TRỌNG: không nâng Jackson lên cao hơn 2.13.1, bản mới hơn làm hỏng máy Android cũ.
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1") // Đọc JSON
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
