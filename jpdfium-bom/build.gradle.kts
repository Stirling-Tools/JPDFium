plugins {
    `java-platform`
}

dependencies {
    constraints {
        api(project(":jpdfium"))
        api(project(":jpdfium-spring"))
    }
}
