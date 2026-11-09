plugins { kotlin("jvm") version "2.3.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(21); compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
// 直接编译生产网关和光学协议，避免 app 测试源集中既有孤立桩影响定向验证。
sourceSets.main {
    kotlin.srcDirs("../../app/app/src/main/kotlin", "fixtures")
    kotlin.include("com/azurpilot/ghio/proot/AzurPilotGateway.kt")
    kotlin.include("com/azurpilot/ghio/proot/AzurPilotStatistics.kt")
    kotlin.include("com/azurpilot/ghio/proot/AzurPilotResourceFlows.kt")
    kotlin.include("com/azurpilot/ghio/proot/AzurPilotValues.kt")
    kotlin.include("com/azurpilot/ghio/remoteaccess/ParticleCode.kt")
    kotlin.include("**/GatewayEnvironment.kt", "**/RemoteEnvironment.kt", "**/DispatchEnvironment.kt", "**/LogEnvironment.kt")
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.2.1")
}
tasks.test { testLogging { events("failed", "passed") } }
