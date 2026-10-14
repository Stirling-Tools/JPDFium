plugins {
    id("jpdfium.library-conventions")
}

// The module has no descriptor because it is compiled against Spring's
// automatic modules, so give the jar a stable module name for the module path.
tasks.named<Jar>("jar") {
    manifest {
        attributes["Automatic-Module-Name"] = "stirling.software.jpdfium.spring"
    }
}

publishing {
    publications {
        getByName<MavenPublication>("mavenJava") {
            pom {
                name.set("JPDFium Spring Boot Starter")
                description.set("Spring Boot auto-configuration for JPDFium")
            }
        }
    }
}

dependencies {
    api(project(":jpdfium"))
    // Spring Boot dependencies would be added here when configuring auto-configuration:
    // compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.x.x")
}
