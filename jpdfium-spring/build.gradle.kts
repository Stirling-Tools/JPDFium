plugins {
    id("jpdfium.library-conventions")
}

publishing {
    publications {
        getByName<MavenPublication>("mavenJava") {
            pom {
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
