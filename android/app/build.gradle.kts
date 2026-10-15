plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "lab.g23"
 compileSdk = 35
 buildToolsVersion = "35.0.0"
 ndkVersion = "27.0.12077973"
 defaultConfig {
  applicationId = "lab.g23"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "0.1.0"
  externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
 }
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
 sourceSets["main"].assets.srcDir("../../protocol")
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { buildConfig = true }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 implementation("androidx.activity:activity-ktx:1.9.3")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.camera:camera-core:1.5.1")
 implementation("androidx.camera:camera-camera2:1.5.1")
 implementation("androidx.camera:camera-lifecycle:1.5.1")
 implementation("androidx.camera:camera-view:1.5.1")
 implementation("androidx.exifinterface:exifinterface:1.3.7")
}
