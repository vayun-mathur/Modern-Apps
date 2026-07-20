plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "robot_2"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.openassistant"
    }

    androidResources {
        // SigLIP2 .onnx encoders are downloaded to external files at runtime,
        // but the SentencePiece tokenizer.model is downloaded too; keep .onnx
        // uncompressed for consistency with the ONNX Runtime memory-mapped read.
        noCompress += "onnx"
    }

    packaging {
        jniLibs {
            pickFirsts.add("**/libLiteRtTopKOpenClSampler.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

dependencies {

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    // adaptive navigation
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // display images
    implementation(libs.coil.compose)

    // ai
    implementation(libs.litertlm.android)
    // litertlm 0.14.0 needs kotlinx-coroutines 1.11.0 (close$default on the
    // SendChannel interface); requesting it directly wins over the transitive 1.9.0.
    implementation(libs.kotlinx.coroutines.android)
    // SigLIP2 image/text embedding (semantic photo search served to the photos
    // app) runs on ONNX Runtime; litertlm stays for the chat LLM.
    implementation(libs.onnxruntime.android)

    implementation(project(":library:downloadservice"))
}