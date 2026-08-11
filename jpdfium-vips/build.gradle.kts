plugins {
    id("jpdfium.library-conventions")
}

publishing {
    publications {
        getByName<MavenPublication>("mavenJava") {
            pom {
                description.set("Optional libvips encoding for JPDFium via vips-ffm (HEIC, AVIF, JXL, WebP, PNG, JPEG)")
            }
        }
    }
}

dependencies {
    api(project(":jpdfium"))
    implementation(libs.vips.ffm)
}

tasks.withType<Test> {
    jvmArgs("-Dvipsffm.libpath.vips.override=/opt/homebrew/lib/libvips.42.dylib")
    jvmArgs("-Dvipsffm.libpath.glib.override=/opt/homebrew/lib/libglib-2.0.dylib")
    jvmArgs("-Dvipsffm.libpath.gobject.override=/opt/homebrew/lib/libgobject-2.0.dylib")
}
