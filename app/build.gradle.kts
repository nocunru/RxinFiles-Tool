import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名凭据从项目根目录的 keystore.properties 读取，避免硬编码进构建脚本。
// 该文件与本目录下的 .jks 请自行妥善备份 —— 一旦丢失将无法再对已发布的 App 进行升级签名。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.rxinfiles.tool"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.rxinfiles.tool"
        minSdk = 28
        targetSdk = 34
        versionCode = 40200
        versionName = "4.02.00"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            ndkBuild {
                arguments += listOf("APP_PLATFORM=android-28")
            }
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")

                // 三行显式开启是为了让意图明确；AGP 会按 minSdk 决定实际生效的方案。
                // 本工程 minSdk = 28，实测产物只签 v3（v1/v2 均未生效，它们只为 Android 7 以下服务）。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    packaging {
        jniLibs {
            /*
             * 必须是 true（= AndroidManifest 的 extractNativeLibs="true"）。
             *
             * jniLibs/arm64-v8a 下除了 libtermux.so，还放了 libbusybox.so。
             * 后者其实**不是**给 dlopen 用的库，而是「当命令 exec」的多调用二进制
             * （busybox），借 .so 这个名字随包分发 —— 因为 nativeLibraryDir 里
             * 只收 `lib*.so` 形式的文件，文件名不带这个前缀/后缀就不会被解压出去。
             *
             * 为什么必须解压到磁盘：Android 10 起 untrusted_app 对 app_data_file
             * 没有 execute_no_trans（AOSP app_neverallows.te 里是 neverallow），
             * 所以「复制到 filesDir 再执行」会被 SELinux 拒掉；能把「命令」跑起来的
             * 唯一位置是 nativeLibraryDir（/data/app/~~xxx/pkg-yyy/lib/arm64/，
             * SELinux 类型 apk_data_file）。而 extractNativeLibs=false 时系统走
             * android_dlopen_ext 从 APK 内 mmap 加载，磁盘上压根没有实体文件 ——
             * 那条路只对 dlopen 有意义，对「当命令 exec」没有意义。
             *
             * 二进制本身是 **NDK 交叉编译 + Bionic 动态链接**（PIE，解释器
             * /system/bin/linker64，NEEDED 只有 libc/libm/libdl）。
             * 不能用 Alpine 那种 musl 静态版：musl 启动代码会调 set_robust_list，
             * 被 app 沙盒的 seccomp TRAP 成 SIGSYS，进程 exec 后立即被杀。
             * 构建脚本见 tools/rxinbox/build-busybox.py。
             */
            useLegacyPackaging = true

            /*
             * 不要动 libbusybox.so。
             *
             * 它在交叉编译时已经用 llvm-strip 处理过，再 strip 一次没有收益；
             * 更要紧的是它「文件名像库、实际可执行」，让 AGP 的 strip 任务去碰它
             * 属于无谓的风险 —— 一旦失败会直接断掉整个构建。
             * （AGP 8 里 doNotStrip 已废弃，等价写法是 keepDebugSymbols。）
             */
            keepDebugSymbols.add("**/libbusybox.so")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Shizuku - adb 级别的系统权限
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    /*
     * 归档格式支持（RAR / 7z / bzip2 / xz）。
     *
     * 用 app/libs 下的平铺 jar，而不是 `implementation("org.apache.commons:...")`：
     * 这个工程要求**完全离线构建**（gradle --offline），远程仓库一律不解析。
     * 四个 jar 的来源、版本、许可证都写在 app/libs/README.md。
     *
     * 为什么必须引这三方：
     *  - RAR 的压缩格式没有公开规范，纯 Java 实现只有 junrar（它覆盖 RAR 1.5~5.0）；
     *  - 7z 的容器解析在 commons-compress，而标准 .7z 用的 LZMA/LZMA2 解码器在 xz；
     *  - junrar 用 slf4j 记日志（slf4j-nop 只是为了不出「没有 provider」的噪音）。
     */
    implementation(fileTree("libs") { include("*.jar") })
}
