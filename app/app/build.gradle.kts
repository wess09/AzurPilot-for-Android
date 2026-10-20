import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    id("azurpilot.android.application")
    id("azurpilot.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.azurpilot.ghio"

    sourceSets {
        named("androidTest") {
            // 独立设备测试避开引用旧运行时类型的历史测试集。
            if (providers.gradleProperty("oobeTestsOnly").orNull == "true") {
                kotlin.directories.clear()
                kotlin.directories.add("src/oobeTest/kotlin")
            } else if (providers.gradleProperty("remoteAccessTestsOnly").orNull == "true") {
                kotlin.directories.clear()
                kotlin.directories.add("src/remoteAccessTest/kotlin")
            } else if (providers.gradleProperty("azurpilot.downloadBenchmark").orNull == "true") {
                kotlin.directories.clear()
                kotlin.directories.add("src/downloadTest/kotlin")
            } else if (providers.gradleProperty("embeddedAdbTestsOnly").orNull == "true") {
                kotlin.directories.clear()
                kotlin.directories.add("src/embeddedAdbTest/kotlin")
            } else if (providers.gradleProperty("navigationTestsOnly").orNull == "true") {
                kotlin.directories.clear()
                kotlin.directories.add("src/navigationTest/kotlin")
            } else {
                kotlin.directories.add("src/navigationTest/kotlin")
            }
        }
        named("main") {
            // proot 九件套（libproot/libproot-loader/libtalloc/busybox/shim 等，Spike A 钉版产物）；
            // 与 src/main/jniLibs/（上游框架的拉取件，gitignore）分开放，本目录是构建输入要入库
            jniLibs.srcDir("src/main/prootLibs")
            // 厂商库由哈希钉版脚本准备，和 PRoot 可执行库分开放。
            jniLibs.srcDir(rootDir.parentFile.resolve(".tmp/ocr-runtime/jni"))
            assets.directories.add(rootDir.parentFile.resolve(".tmp/ocr-runtime/assets").absolutePath)
            // CI 下载的公开机型表用于离线匹配商品名，缺失时退回系统公开名称。
            assets.directories.add(rootDir.parentFile.resolve(".tmp/device-catalog").absolutePath)
        }
    }

    defaultConfig {
        providers.gradleProperty("oobeTestApplicationId").orNull?.let { applicationId = it }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    packaging.jniLibs.keepDebugSymbols += listOf(
        "**/libLiteRtCompilerPlugin_*.so", "**/libLiteRtDispatch_*.so",
        "**/libQnn*.so", "**/libneuronusdk*.so", "**/libhiai*.so",
    )

    androidResources {
        // rootfs.tar.xz 已压缩且要按字节读进度（assets.openFd 只对未压缩资产生效）
        noCompress += "zip"
        noCompress += "xz"
        // 机型目录已使用 gzip 压缩，独立扩展名阻止资产合并器自动解压。
        noCompress += "catalog"
        // OCR 通过流解压到校验后的模型缓存，不需要 openFd；压缩不改变模型权重。
    }
}

/**
 * 从仓库同步宿主、设备、OCR、装备码、NTP、事件过渡和更新 overlay，供轻量 APK 更新已有 rootfs。
 *
 * Copies host, device, OCR, equipment, NTP, event-transition, and update overlays into APK assets.
 * Slim APK updates refresh existing rootfs installations before every session spawn.
 */
abstract class SyncAndroidHostOverlayTask : DefaultTask() {
    /** 仓库内单一来源 / The single in-repo source. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val overlayFiles: ConfigurableFileCollection

    /** 生成资产根目录。 / Generated asset root directory. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun sync() {
        overlayFiles.files.forEach { source ->
            val out = File(outputDir.get().asFile, "overlays/${source.name}")
            out.parentFile.mkdirs()
            source.copyTo(out, overwrite = true)
        }
    }
}

val syncAndroidHostOverlay = tasks.register<SyncAndroidHostOverlayTask>("syncAndroidHostOverlay") {
    overlayFiles.from(listOf("android_host.py", "sitecustomize.py", "android_ocr.py", "android_equipment.py", "android_device.py", "android_app_launch.py", "android_capture.py", "android_opsi_event.py", "android_ntp.py", "android_update.py").map {
        rootDir.parentFile.resolve("rootfs/overlays/$it")
    })
    outputDir.set(layout.buildDirectory.dir("generated/androidHostOverlay"))
}

/**
 * 从被忽略的本地目录注入提交证书，避免将共享私钥放进源码。
 *
 * Injects reporting credentials from an ignored local directory, keeping the shared key out of source.
 */
@org.gradle.work.DisableCachingByDefault(because = "Contains private client credentials")
abstract class SyncDeviceReportCredentialsTask : DefaultTask() {
    /** 只允许正式 CI 打包共享私钥。 / Only official CI builds may bundle the shared private key. */
    @get:Input
    abstract val officialCiBuild: Property<Boolean>
    /** 构建时凭据来源。 / Build-time credential source. */
    @get:Internal
    abstract val credentialsDir: DirectoryProperty

    /** 只有这两个白名单文件可进入 APK。 / Only these allowlisted files may enter the APK. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val credentialFiles: FileTree
        get() = credentialsDir.asFileTree.matching {
            include("client-cert.pem", "client-key.pem")
        }

    /** 生成资产目录。 / Generated asset directory. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** 同步成对凭据，缺失时清理旧资产。 / Copies paired credentials, clearing stale assets when absent. */
    @TaskAction
    fun sync() {
        val source = credentialsDir.get().asFile
        val cert = source.resolve("client-cert.pem")
        val key = source.resolve("client-key.pem")
        check(cert.isFile == key.isFile) { "Device report certificate and key must be provided together" }
        check(!cert.isFile || officialCiBuild.get()) {
            "Device report credentials may only be bundled by GitHub Actions or CNB"
        }
        val out = outputDir.get().asFile.resolve("device-report")
        out.deleteRecursively()
        if (cert.isFile) {
            out.mkdirs()
            cert.copyTo(out.resolve(cert.name), overwrite = true)
            key.copyTo(out.resolve(key.name), overwrite = true)
        } else {
            outputDir.get().asFile.mkdirs()
            logger.warn("Device reporting is unavailable: build credentials are absent")
        }
    }
}

val syncDeviceReportCredentials = tasks.register<SyncDeviceReportCredentialsTask>("syncDeviceReportCredentials") {
    officialCiBuild.set(
        providers.environmentVariable("GITHUB_ACTIONS").orElse("")
            .zip(providers.environmentVariable("CNB").orElse("")) { github, cnb ->
                github == "true" || cnb == "true"
            }
    )
    credentialsDir.set(rootDir.parentFile.resolve(".tmp/report-credentials"))
    outputDir.set(layout.buildDirectory.dir("generated/deviceReportCredentials"))
}

/**
 * 从合并后的 Manifest 排除旧 MGVI，避免它覆盖内置 NeuroPilot SDK。
 *
 * 当前合并器未按 android:name 匹配 uses-native-library 的 remove 标记，
 * 因此通过公开 artifact API 过滤最终声明，不依赖失效的合并标记。
 *
 * Excludes legacy MGVI from the merged manifest to prevent overriding bundled NeuroPilot.
 * The current merger does not match remove markers by native-library name, so the public
 * artifact API filters the final declaration instead.
 */
@CacheableTask
abstract class FilterOcrManifestTask : DefaultTask() {
    /** 完整合并声明。 / Complete merged declarations. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputManifest: RegularFileProperty

    /** 打包使用的过滤后声明。 / Filtered declarations used for packaging. */
    @get:OutputFile
    abstract val outputManifest: RegularFileProperty

    /** 只删除旧 MGVI 声明，保留其他系统库请求。 / Removes only legacy MGVI declarations. */
    @TaskAction
    fun filter() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(inputManifest.get().asFile)
        val libraries = document.getElementsByTagName("uses-native-library")
        for (index in libraries.length - 1 downTo 0) {
            val library = libraries.item(index) as org.w3c.dom.Element
            if (library.getAttributeNS("http://schemas.android.com/apk/res/android", "name") ==
                "libneuron_adapter_mgvi.so") {
                library.parentNode.removeChild(library)
            }
        }
        val output = outputManifest.get().asFile
        output.parentFile.mkdirs()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(output))
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncAndroidHostOverlay) { it.outputDir }
        variant.sources.assets?.addGeneratedSourceDirectory(syncDeviceReportCredentials) { it.outputDir }
        val filterOcrManifest = tasks.register<FilterOcrManifestTask>("${variant.name}FilterOcrManifest")
        variant.artifacts.use(filterOcrManifest)
            .wiredWithFiles(FilterOcrManifestTask::inputManifest, FilterOcrManifestTask::outputManifest)
            .toTransform(SingleArtifact.MERGED_MANIFEST)
    }
}

// 捆绑运行时校验：rootfs.tar.xz（300MB+）与 BUILD_MANIFEST 由 CI 的 rootfs workflow 产出、
// 不入库，本地缺失时所有 package/assemble 任务在此明确失败，避免打出没有运行时的残缺包；
// azurpilot.slimApk=true 的精简构建跳过该校验
val verifyBundledAzurPilotRuntime = tasks.register("verifyBundledAzurPilotRuntime") {
    val archive = layout.projectDirectory.file("src/main/assets/rootfs/rootfs.tar.xz")
    val manifest = layout.projectDirectory.file("src/main/assets/rootfs/BUILD_MANIFEST")
    val releaseAbi = providers.gradleProperty("azurpilot.releaseAbi").orNull?.trim().orEmpty()
    doLast {
        check(archive.asFile.isFile && archive.asFile.length() > 0) {
            "缺少 AzurPilot rootfs.tar.xz；先运行 rootfs workflow 并复制对应架构的构建产物"
        }
        val manifestText = manifest.asFile.readText()
        check(manifestText.contains("\"runtime\": \"azurpilot-android\"")) {
            "缺少与 AzurPilot rootfs 配套的 BUILD_MANIFEST"
        }
        // per-arch full APK：内置的 rootfs 架构必须与收窄的 ABI 一致，装到别的架构上跑不了
        if (releaseAbi.isNotEmpty()) {
            val arch = Regex("\"rootfs_arch\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
            check(arch == releaseAbi) {
                "BUILD_MANIFEST 的 rootfs_arch=$arch 与 -Pazurpilot.releaseAbi=$releaseAbi 不一致，内置了别的架构的 rootfs"
            }
        }
    }
}

if (providers.gradleProperty("azurpilot.slimApk").orNull != "true") {
    tasks.matching { it.name.startsWith("package") || it.name.startsWith("assemble") }
        .configureEach { dependsOn(verifyBundledAzurPilotRuntime) }
}

// NPU 运行库缺失时明确拒绝打包，避免发布只带接口却无法请求加速的 APK。
val verifyBundledOcrRuntime = tasks.register("verifyBundledOcrRuntime") {
    val expectedVersion = libs.versions.litert.get()
    doLast {
        check(expectedVersion == "2.1.0rc1") {
            "Revalidate the OCR JNI ModelWrapper adapter before upgrading LiteRT"
        }
        val runtime = rootDir.parentFile.resolve(".tmp/ocr-runtime")
        val manifest = runtime.resolve("assets/ocr/runtime.json")
        check(manifest.isFile) {
            "Run python app/scripts/fetch_ocr_runtime.py before building an APK"
        }
        check(manifest.readText().contains("\"litert\": \"$expectedVersion\"")) {
            "LiteRT core and bundled vendor plugins must use the same version"
        }
        check(runtime.resolve("assets/ocr/hiai-runtime.json").isFile) {
            "HiAI runtime provenance is missing; run app/scripts/fetch_ocr_runtime.py"
        }
        if (providers.gradleProperty("azurpilot.releaseAbi").orNull != "x86_64") {
            listOf("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so",
                "libLiteRtCompilerPlugin_MediaTek.so", "libLiteRtDispatch_MediaTek_Vendor.so",
                "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so",
                "libneuronusdk_adapter.mtk.so", "libneuronusdk_adapter.9.mtk.so",
                "libhiai.so", "libhiai_ir.so", "libhiai_ir_build.so",
                "libhiai_model_compatible.so", "libhiai_enhance.so").forEach {
                check(runtime.resolve("jni/arm64-v8a/$it").isFile) { "Missing OCR library: $it" }
            }
        }
    }
}
tasks.matching { it.name.startsWith("package") || it.name.startsWith("assemble") }
    .configureEach { dependsOn(verifyBundledOcrRuntime) }

dependencies {
    implementation(libs.hidden.api.bypass)
    // 项目已内置 shader 源码；squircle 的传递副本会让 R8 报重复类。
    implementation(project(":miuix-runtime")) {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-shader")
    }
    implementation(libs.material.kolor)
    implementation(libs.litert)
    // 隐藏框架 API 的实现只在运行时的平台上存在，编译期仅需签名镜像，故 compileOnly
    compileOnly(project(":hidden-api"))

    // @PrefSchema / @PrefKey 注解与配套 KSP 处理器：生成 DataStore schema 访问器
    // （原始键名 snake_case，迁移需用原始键）
    implementation(project(":annotation-api"))
    ksp(project(":ksp-processor"))

    // MIUI 上系统权限页的跳转差异大，自己拼 Intent 覆盖不全
    implementation(libs.xx.permissions)

    implementation(project(":adb-runtime"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.lottie.compose)
    implementation(project(":navigation-runtime")) {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-shader")
    }
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.window)
    implementation(libs.androidx.browser)

    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // AzurPilot /api/v1/ws 网关：WebSocket 富接口（实例/总览/自启/热更新）
    implementation(libs.okhttp)
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.zxing:core:3.5.4")
    implementation(project(":download-core"))


    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)

    // 首启解压 rootfs.tar.xz（设备端流式解 tar/xz；busybox tar 解 ubuntu 硬链接有前向引用死坑）
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    // 前台模式控制层：拖拽/吸边/多屏/返回键拦截都在库里，自己写这几样是纯坑区
    implementation(libs.floatingx)
    implementation(libs.floatingx.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
