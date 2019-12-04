rootProject.name = "test-jda"

includeBuild("../JDA") {
    dependencySubstitution {
        substitute(module("net.dv8tion:JDA")).with(project(":"))
    }
}

//includeBuild("../Magma") {
//    dependencySubstitution {
//        substitute(module("club.minnced:magma")).with(project(":"))
//    }
//}

//includeBuild("../jda-nas") {
//    dependencySubstitution {
//        substitute(module("com.sedmelluq:jda-nas")).with(project(":"))
//    }
//}

includeBuild("../jda-reactor") {
    dependencySubstitution {
        substitute(module("club.minnced:jda-reactor")).with(project(":"))
    }
}
