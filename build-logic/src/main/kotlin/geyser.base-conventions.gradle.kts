plugins {
    `java-library`
    id("net.kyori.indra")
}

val rootProperties: Map<String, *> = project.rootProject.properties
group = rootProperties["group"] as String + "." + rootProperties["id"] as String
version = rootProperties["version"] as String
description = rootProperties["description"] as String

indra {
    github("GeyserMC", "Geyser") {
        ci(true)
        issues(true)
        scm(true)
    }
    mitLicense()

    javaVersions {
        target(21)
    }
}

dependencies {
    compileOnly("org.checkerframework:checker-qual:" + libs.checker.qual.get().version)
}

repositories {
    // mavenLocal()

    mavenCentral()

    // Builds github.com/eofihbzefhzb/NetworkCompatible on demand from its release tag.
    // Used instead of Maven Central for netty-transport-nethernet: the fork resolves the
    // NetherNet peer address from ICE candidates, and publishing to Central would need the
    // dev.kastle namespace. Restricted to that one group so nothing else resolves here.
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.eofihbzefhzb.NetworkCompatible")
        }
    }

    // Floodgate, Cumulus etc.
    maven("https://repo.opencollab.dev/main")

    // Paper, Velocity
    maven("https://repo.papermc.io/repository/maven-public")

    // Spigot
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots") {
        mavenContent { snapshotsOnly() }
    }

    // NeoForge
    maven("https://maven.neoforged.net/releases") {
        mavenContent { releasesOnly() }
    }

    // Minecraft
    maven("https://libraries.minecraft.net") {
        name = "minecraft"
        mavenContent { releasesOnly() }
    }

    // ViaVersion
    maven("https://repo.viaversion.com") {
        name = "viaversion"
    }

    // Jitpack for e.g. MCPL
    maven("https://jitpack.io") {
        content { includeGroupByRegex("com\\.github\\..*") }
    }
}
