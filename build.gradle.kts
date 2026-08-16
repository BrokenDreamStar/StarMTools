plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("org.xerial:sqlite-jdbc:3.45.3.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    runServer {
        minecraftVersion("26.1.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    jar {
        archiveFileName = "${rootProject.name}-${project.version}.jar"
        // 将仓库根目录的更新日志一并打进发布 jar，方便使用者在 jar 内直接查看
        from(rootProject.file("CHANGELOG.md"))
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
