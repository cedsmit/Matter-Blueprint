
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

version = providers.gradleProperty("modVersion").get()

base {
    archivesName.set("matter-blueprint")
}
