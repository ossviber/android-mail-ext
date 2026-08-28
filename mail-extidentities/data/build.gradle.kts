import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Copyright (c) 2022 Proton Technologies AG
 * This file is part of Proton Technologies AG and Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Mail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    kotlin("plugin.serialization")
    id("app-config-plugin")
}

android {
    namespace = "ch.protonmail.android.extidentities.data"
    compileSdk = AppConfiguration.compileSdk.get()

    defaultConfig {
        minSdk = AppConfiguration.minSdk.get()
        lint.targetSdk = AppConfiguration.targetSdk.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget("17")
        }
    }

    packaging {
        resources.pickFirsts.add("META-INF/LICENSE.md")
        resources.pickFirsts.add("META-INF/NOTICE.md")
    }
}

dependencies {
    kapt(libs.bundles.app.annotationProcessors)

    implementation(libs.bundles.module.data)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.proton.core.crypto)

    // SMTP + MIME (Angus Mail, official Android distribution)
    implementation(libs.angus.jakarta.mail)
    implementation(libs.angus.activation)
    implementation(libs.jakarta.activation.api)

    // Proton SRP login (bcrypt with Proton's $2y$ parameters)
    implementation(libs.favre.bcrypt)
    // PGP signature verification of the SRP modulus
    implementation(libs.bcpg)

    implementation(project(":mail-extidentities:domain"))
    implementation(project(":mail-common:data"))
    implementation(project(":mail-common:domain"))
    implementation(project(":mail-label:domain"))
    implementation(project(":mail-message:domain"))
    implementation(project(":mail-pagination:domain"))
    implementation(libs.androidx.work.runtimeKtx)
    implementation(project(":mail-session:data"))
    implementation(project(":mail-session:domain"))
    implementation(libs.proton.core.domain)
    compileOnly(libs.proton.rust.core)

    testImplementation(libs.bundles.test)
    testImplementation(project(":test:utils"))
}
