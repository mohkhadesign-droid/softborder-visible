Put softborder-1.5.2.jar in this folder before building.

build.gradle consumes it as a flatDir dependency ('blank:softborder:1.5.2'), which resolves to
the file softborder-1.5.2.jar in this directory. Only Soft Borders' public API is compiled
against; nothing from it is bundled into the built jar.

If your Soft Borders version differs, rename the file to match, or change the version in the
dependency line in build.gradle.
