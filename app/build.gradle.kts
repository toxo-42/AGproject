plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("com.chaquo.python")
}

android {
  namespace = "com.example.agproject"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.example.agproject"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Chaquopy: 번들할 Python 네이티브 ABI. 실기기(arm64) + 에뮬레이터(x86_64)
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }
  //viewBinding 기능 ON(code or butten을 쉽게 가져오기 위함)
  buildFeatures {
    viewBinding = true
  }
}

// Chaquopy 설정: Python 코드는 기본 경로 src/main/python 에서 찾는다.
chaquopy {
  defaultConfig {
    version = "3.12"
  }
}

dependencies {

  implementation("androidx.core:core-ktx:1.10.1")
  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.google.android.material:material:1.10.0")
  implementation("androidx.activity:activity:1.8.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  // Glide Transformations(블러효과 확장)
  implementation ("jp.wasabeef:glide-transformations:4.3.0")
  // Glide(이미지 로딩)
  implementation("com.github.bumptech.glide:glide:4.16.0")
  annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.1.5")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

}