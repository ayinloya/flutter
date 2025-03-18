/* groovylint-disable LineLength, UnnecessaryGString, UnnecessaryGetter */
// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import com.android.build.OutputFile
import com.android.build.gradle.AbstractAppExtension
import com.flutter.gradle.AppLinkSettings
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.tasks.PackageAndroidArtifact
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.android.builder.model.BuildType
import com.flutter.gradle.BaseApplicationNameHandler
import com.flutter.gradle.BaseFlutterTask
import com.flutter.gradle.Deeplink
import com.flutter.gradle.DependencyVersionChecker
import com.flutter.gradle.FlutterExtension
import com.flutter.gradle.FlutterPluginUtils
import com.flutter.gradle.IntentFilterCheck
import com.flutter.gradle.VersionUtils
import groovy.xml.QName
import org.gradle.api.file.Directory

import java.nio.file.Paths
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.file.CopySpec
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.internal.os.OperatingSystem


class FlutterPlugin implements Plugin<Project> {

    private static final String DEFAULT_MAVEN_HOST = "https://storage.googleapis.com"

    /** The platforms that can be passed to the `--Ptarget-platform` flag. */
    private static final String PLATFORM_ARM32  = "android-arm"
    private static final String PLATFORM_ARM64  = "android-arm64"
    private static final String PLATFORM_X86    = "android-x86"
    private static final String PLATFORM_X86_64 = "android-x64"

    /** The ABI architectures supported by Flutter. */
    private static final String ARCH_ARM32      = "armeabi-v7a"
    private static final String ARCH_ARM64      = "arm64-v8a"
    private static final String ARCH_X86        = "x86"
    private static final String ARCH_X86_64     = "x86_64"

    private static final String INTERMEDIATES_DIR = "intermediates"

    /** Maps platforms to ABI architectures. */
    private static final Map PLATFORM_ARCH_MAP = [
        (PLATFORM_ARM32)    : ARCH_ARM32,
        (PLATFORM_ARM64)    : ARCH_ARM64,
        (PLATFORM_X86)      : ARCH_X86,
        (PLATFORM_X86_64)   : ARCH_X86_64,
    ]

    /**
     * The version code that gives each ABI a value.
     * For each APK variant, use the following versions to override the version of the Universal APK.
     * Otherwise, the Play Store will complain that the APK variants have the same version.
     */
    private static final Map<String, Integer> ABI_VERSION = [
        (ARCH_ARM32)        : 1,
        (ARCH_ARM64)        : 2,
        (ARCH_X86)          : 3,
        (ARCH_X86_64)       : 4,
    ]

    /** When split is enabled, multiple APKs are generated per each ABI. */
    private static final List DEFAULT_PLATFORMS = [
        PLATFORM_ARM32,
        PLATFORM_ARM64,
        PLATFORM_X86_64,
    ]

    private final static String propLocalEngineRepo = "local-engine-repo"
    private final static String propProcessResourcesProvider = "processResourcesProvider"

    /**
     * The name prefix for flutter builds. This is used to identify gradle tasks
     * where we expect the flutter tool to provide any error output, and skip the
     * standard Gradle error output in the FlutterEventLogger. If you change this,
     * be sure to change any instances of this string in symbols in the code below
     * to match.
     */
    static final String FLUTTER_BUILD_PREFIX = "flutterBuild"

    private Project project
    private File flutterRoot
    private File flutterExecutable
    private String localEngine
    private String localEngineHost
    private String localEngineSrcPath
    private Properties localProperties
    private String engineVersion
    private String engineRealm
    private List<Map<String, Object>> pluginList
    private List<Map<String, Object>> pluginDependencies

    /**
     * Flutter Docs Website URLs for help messages.
     */
    private final String kWebsiteDeploymentAndroidBuildConfig = "https://flutter.dev/to/review-gradle-config"

    @Override
    void apply(Project project) {
        this.project = project

        Project rootProject = project.rootProject
        if (isFlutterAppProject()) {
            rootProject.tasks.register("generateLockfiles") {
                doLast {
                    rootProject.subprojects.each { subproject ->
                        String gradlew = (OperatingSystem.current().isWindows()) ?
                            "${rootProject.projectDir}/gradlew.bat" : "${rootProject.projectDir}/gradlew"
                        rootProject.exec {
                            workingDir(rootProject.projectDir)
                            executable(gradlew)
                            args(":${subproject.name}:dependencies", "--write-locks")
                        }
                    }
                }
            }
        }

        String flutterRootPath = resolveProperty("flutter.sdk", System.getenv("FLUTTER_ROOT"))
        if (flutterRootPath == null) {
            throw new GradleException("Flutter SDK not found. Define location with flutter.sdk in the local.properties file or with a FLUTTER_ROOT environment variable.")
        }
        flutterRoot = project.file(flutterRootPath)
        if (!flutterRoot.isDirectory()) {
            throw new GradleException("flutter.sdk must point to the Flutter SDK directory")
        }

        engineVersion = FlutterPluginUtils.shouldProjectUseLocalEngine(project)
            ? "+" // Match any version since there's only one.
            : "1.0.0-" + Paths.get(flutterRoot.absolutePath, "bin", "cache", "engine.stamp").toFile().text.trim()

        engineRealm = Paths.get(flutterRoot.absolutePath, "bin", "cache", "engine.realm").toFile().text.trim()
        if (engineRealm) {
            engineRealm += "/"
        }

        // Configure the Maven repository.
        String hostedRepository = System.getenv("FLUTTER_STORAGE_BASE_URL") ?: DEFAULT_MAVEN_HOST
        String repository = FlutterPluginUtils.shouldProjectUseLocalEngine(project)
            ? project.property(propLocalEngineRepo)
            : "$hostedRepository/${engineRealm}download.flutter.io"
        rootProject.allprojects {
            repositories {
                maven {
                    url(repository)
                }
            }
        }

        // Load shared gradle functions
        project.apply from: Paths.get(flutterRoot.absolutePath, "packages", "flutter_tools", "gradle", "src", "main", "groovy", "native_plugin_loader.groovy")

        FlutterExtension extension = project.extensions.create("flutter", FlutterExtension)
        Properties localProperties = new Properties()
        File localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.withReader("UTF-8") { reader ->
                localProperties.load(reader)
            }
        }

        extension.flutterVersionCode = localProperties.getProperty("flutter.versionCode", "1")
        extension.flutterVersionName = localProperties.getProperty("flutter.versionName", "1.0")

        this.addFlutterTasks(project)
        forceNdkDownload(project, flutterRootPath)

        // By default, assembling APKs generates fat APKs if multiple platforms are passed.
        // Configuring split per ABI allows to generate separate APKs for each abi.
        // This is a noop when building a bundle.
        if (FlutterPluginUtils.shouldProjectSplitPerAbi(project)) {
            project.android {
                splits {
                    abi {
                        // Enables building multiple APKs per ABI.
                        enable(true)
                        // Resets the list of ABIs that Gradle should create APKs for to none.
                        reset()
                        // Specifies that we do not want to also generate a universal APK that includes all ABIs.
                        universalApk(false)
                    }
                }
            }
        }
        final String propDeferredComponentNames = "deferred-component-names"
        if (project.hasProperty(propDeferredComponentNames)) {
            String[] componentNames = project.property(propDeferredComponentNames).split(",").collect {":${it}"}
            project.android {
                dynamicFeatures = componentNames
            }
        }

        getTargetPlatforms().each { targetArch ->
            String abiValue = PLATFORM_ARCH_MAP[targetArch]
            project.android {
                if (FlutterPluginUtils.shouldProjectSplitPerAbi(project)) {
                    splits {
                        abi {
                            include(abiValue)
                        }
                    }
                }
            }
        }

        String flutterExecutableName = Os.isFamily(Os.FAMILY_WINDOWS) ? "flutter.bat" : "flutter"
        flutterExecutable = Paths.get(flutterRoot.absolutePath, "bin", flutterExecutableName).toFile()

        // Validate that the provided Gradle, Java, AGP, and KGP versions are all within our
        // supported range.
        // TODO(gmackall) Dependency version checking is currently implemented as an additional
        // Gradle plugin because we can't import it from Groovy code. As part of the Groovy
        // -> Kotlin migration, we should remove this complexity and perform the checks inside
        // of the main Flutter Gradle Plugin.
        // See https://github.com/flutter/flutter/issues/121541#issuecomment-1920363687.
        final Boolean shouldSkipDependencyChecks = project.hasProperty("skipDependencyChecks") && project.getProperty("skipDependencyChecks")
        if (!shouldSkipDependencyChecks) {
            try {
                DependencyVersionChecker.checkDependencyVersions(project)
            } catch (Exception e) {
                if (!project.hasProperty("usesUnsupportedDependencyVersions") || !project.usesUnsupportedDependencyVersions) {
                    // Possible bug in dependency checking code - warn and do not block build.
                    project.logger.error("Warning: Flutter was unable to detect project Gradle, Java, " +
                            "AGP, and KGP versions. Skipping dependency version checking. Error was: "
                            + e)
                }
                else {
                    // If usesUnsupportedDependencyVersions is set, the exception was thrown by us
                    // in the dependency version checker plugin so re-throw it here.
                    throw e
                }
            }
        }

        // Use Kotlin source to handle baseApplicationName logic due to Groovy dynamic dispatch bug.
        BaseApplicationNameHandler.setBaseName(project)

        String flutterProguardRules = Paths.get(flutterRoot.absolutePath, "packages", "flutter_tools",
                "gradle", "flutter_proguard_rules.pro")
        project.android.buildTypes {
            // Add profile build type.
            profile {
                initWith(debug)
                if (it.hasProperty("matchingFallbacks")) {
                    matchingFallbacks = ["debug", "release"]
                }
            }
            // TODO(garyq): Shrinking is only false for multi apk split aot builds, where shrinking is not allowed yet.
            // This limitation has been removed experimentally in gradle plugin version 4.2, so we can remove
            // this check when we upgrade to 4.2+ gradle. Currently, deferred components apps may see
            // increased app size due to this.
            if (FlutterPluginUtils.shouldShrinkResources(project)) {
                release {
                    // Enables code shrinking, obfuscation, and optimization for only
                    // your project's release build type.
                    minifyEnabled(true)
                    // Enables resource shrinking, which is performed by the Android Gradle plugin.
                    // The resource shrinker can't be used for libraries.
                    shrinkResources(FlutterPluginUtils.isBuiltAsApp(project))
                    // Fallback to `android/app/proguard-rules.pro`.
                    // This way, custom Proguard rules can be configured as needed.
                    proguardFiles(project.android.getDefaultProguardFile("proguard-android-optimize.txt"), flutterProguardRules, "proguard-rules.pro")
                }
            }
        }

        if (FlutterPluginUtils.shouldProjectUseLocalEngine(project)) {
            // This is required to pass the local engine to flutter build aot.
            String engineOutPath = project.property("local-engine-out")
            File engineOut = project.file(engineOutPath)
            if (!engineOut.isDirectory()) {
                throw new GradleException("local-engine-out must point to a local engine build")
            }
            localEngine = engineOut.name
            localEngineSrcPath = engineOut.parentFile.parent

            String engineHostOutPath = project.property("local-engine-host-out")
            File engineHostOut = project.file(engineHostOutPath)
            if (!engineHostOut.isDirectory()) {
                throw new GradleException("local-engine-host-out must point to a local engine host build")
            }
            localEngineHost = engineHostOut.name
        }
        project.android.buildTypes.all(this.&addFlutterDependencies)
    }

    private static Properties readPropertiesIfExist(File propertiesFile) {
        Properties result = new Properties()
        if (propertiesFile.exists()) {
            propertiesFile.withReader("UTF-8") { reader -> result.load(reader) }
        }
        return result
    }

    // Add a task that can be called on flutter projects that prints the Java version used in Gradle.
    //
    // Format of the output of this task can be used in debugging what version of Java Gradle is using.
    // Not recommended for use in time sensitive commands like `flutter run` or `flutter build` as
    // Gradle is slower than we want. Particularly in light of https://github.com/flutter/flutter/issues/119196.
    private static void addTaskForJavaVersion(Project project) {
        // Warning: the name of this task is used by other code. Change with caution.
        project.tasks.register("javaVersion") {
            description "Print the current java version used by gradle. "
                "see: https://docs.gradle.org/current/javadoc/org/gradle/api/JavaVersion.html"
            doLast {
                println(JavaVersion.current())
            }
        }
    }

    // Add a task that can be called on Flutter projects that prints the available build variants
    // in Gradle.
    //
    // This task prints variants in this format:
    //
    // BuildVariant: debug
    // BuildVariant: release
    // BuildVariant: profile
    //
    // Format of the output of this task is used by `AndroidProject.getBuildVariants`.
    private static void addTaskForPrintBuildVariants(Project project) {
        // Warning: The name of this task is used by `AndroidProject.getBuildVariants`.
        project.tasks.register("printBuildVariants") {
            description "Prints out all build variants for this Android project"
            doLast {
                project.android.applicationVariants.all { variant ->
                    println "BuildVariant: ${variant.name}"
                }
            }
        }
    }

    // Add a task that can be called on Flutter projects that outputs app link related project
    // settings into a json file.
    //
    // See https://developer.android.com/training/app-links/ for more information about app link.
    //
    // The json will be saved in path stored in outputPath parameter.
    //
    // An example json:
    // {
    //   applicationId: "com.example.app",
    //   deeplinks: [
    //     {"scheme":"http", "host":"example.com", "path":".*"},
    //     {"scheme":"https","host":"example.com","path":".*"}
    //   ]
    // }
    //
    // The output file is parsed and used by devtool.
    private static void addTasksForOutputsAppLinkSettings(Project project) {
        AbstractAppExtension android = (AbstractAppExtension) project.extensions.findByName("android")
        android.applicationVariants.configureEach { variant ->
            // Warning: The name of this task is used by AndroidBuilder.outputsAppLinkSettings
            project.tasks.register("output${variant.name.capitalize()}AppLinkSettings") {
                description "stores app links settings for the given build variant of this Android project into a json file."
                variant.outputs.configureEach { output ->
                    // Deeplinks are defined in AndroidManifest.xml and is only available after
                    // `processResourcesProvider`.
                    Object processResources = output.hasProperty(propProcessResourcesProvider) ?
                            output.processResourcesProvider.get() : output.processResources
                    dependsOn processResources.name
                }
                doLast {
                    AppLinkSettings appLinkSettings = new AppLinkSettings(variant.applicationId)
                    variant.outputs.configureEach { output ->
                        Object processResources = output.hasProperty(propProcessResourcesProvider) ?
                                output.processResourcesProvider.get() : output.processResources
                        Node manifest = new XmlParser().parse(processResources.manifestFile)
                        manifest.application.activity.each { activity ->
                            activity."meta-data".each { metadata ->
                                boolean nameAttribute = metadata.attributes().find { it.key == 'android:name' }?.value == 'flutter_deeplinking_enabled'
                                boolean valueAttribute = metadata.attributes().find { it.key == 'android:value' }?.value == 'true'
                                if (nameAttribute && valueAttribute) {
                                    appLinkSettings.deeplinkingFlagEnabled = true
                                }
                            }
                            activity."intent-filter".each { appLinkIntent ->
                                // Print out the host attributes in data tags.
                                Set<String> schemes = [] as Set<String>
                                Set<String> hosts = [] as Set<String>
                                Set<String> paths = [] as Set<String>
                                IntentFilterCheck intentFilterCheck = new IntentFilterCheck()

                                if (appLinkIntent.attributes().find { it.key == 'android:autoVerify' }?.value == 'true') {
                                    intentFilterCheck.hasAutoVerify = true
                                }
                                appLinkIntent.'action'.each { action ->
                                    if (action.attributes().find { it.key == 'android:name' }?.value == 'android.intent.action.VIEW') {
                                        intentFilterCheck.hasActionView = true
                                    }
                                }
                                appLinkIntent.'category'.each { category ->
                                    if (category.attributes().find { it.key == 'android:name' }?.value == 'android.intent.category.DEFAULT') {
                                        intentFilterCheck.hasDefaultCategory = true
                                    }
                                    if (category.attributes().find { it.key == 'android:name' }?.value == 'android.intent.category.BROWSABLE') {
                                        intentFilterCheck.hasBrowsableCategory = true
                                    }
                                }
                                appLinkIntent.data.each { data ->
                                    data.attributes().each { entry ->
                                        if (entry.key instanceof QName) {
                                            switch (entry.key.getLocalPart()) {
                                                case "scheme":
                                                    schemes.add(entry.value)
                                                    break
                                                case "host":
                                                    hosts.add(entry.value)
                                                    break
                                                case "pathAdvancedPattern":
                                                case "pathPattern":
                                                case "path":
                                                    paths.add(entry.value)
                                                    break
                                                case "pathPrefix":
                                                    paths.add("${entry.value}.*")
                                                    break
                                                case "pathSuffix":
                                                    paths.add(".*${entry.value}")
                                                    break
                                            }
                                        }
                                    }
                                }
                                if (!hosts.isEmpty() || !paths.isEmpty()) {
                                    if (schemes.isEmpty()) {
                                        schemes.add(null)
                                    }
                                    if (hosts.isEmpty()) {
                                        hosts.add(null)
                                    }
                                    if (paths.isEmpty()) {
                                        paths.add('.*')
                                    }
                                    schemes.each { scheme ->
                                        hosts.each { host ->
                                            paths.each { path ->
                                                appLinkSettings.deeplinks.add(new Deeplink(scheme, host, path, intentFilterCheck))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    new File(project.getProperty("outputPath")).write(appLinkSettings.toJson().toString())
                }
            }
        }
    }

    /**
     * Adds the dependencies required by the Flutter project.
     * This includes:
     *    1. The embedding
     *    2. libflutter.so
     */
    void addFlutterDependencies(BuildType buildType) {
        String flutterBuildMode = FlutterPluginUtils.buildModeFor(buildType)
        if (!FlutterPluginUtils.supportsBuildMode(project, flutterBuildMode)) {
            return
        }
        // The embedding is set as an API dependency in a Flutter plugin.
        // Therefore, don't make the app project depend on the embedding if there are Flutter
        // plugin dependencies. In release mode, dev dependencies are stripped, so we do not
        // consider those in the check.
        // This prevents duplicated classes when using custom build types. That is, a custom build
        // type like profile is used, and the plugin and app projects have API dependencies on the
        // embedding.
        List<Map<String, Object>> pluginsThatIncludeFlutterEmbeddingAsTransitiveDependency = flutterBuildMode == "release" ? getPluginListWithoutDevDependencies(project) : getPluginList(project);
        if (!isFlutterAppProject() || pluginsThatIncludeFlutterEmbeddingAsTransitiveDependency.size() == 0) {
            FlutterPluginUtils.addApiDependencies(project, buildType.name,
                    "io.flutter:flutter_embedding_$flutterBuildMode:$engineVersion")
        }
        List<String> platforms = getTargetPlatforms().collect()
        platforms.each { platform ->
            String arch = PLATFORM_ARCH_MAP[platform].replace("-", "_")
            // Add the `libflutter.so` dependency.
            FlutterPluginUtils.addApiDependencies(project, buildType.name,
                    "io.flutter:${arch}_$flutterBuildMode:$engineVersion")
        }
    }

    /**
     * Configures the Flutter plugin dependencies.
     *
     * The plugins are added to pubspec.yaml. Then, upon running `flutter pub get`,
     * the tool generates a `.flutter-plugins-dependencies` file, which contains a map to each plugin location.
     * Finally, the project's `settings.gradle` loads each plugin's android directory as a subproject.
     */
    private void configurePlugins(Project project) {
        configureLegacyPluginEachProjects(project)
        getPluginList(project).each(this.&configurePluginProject)
        getPluginList(project).each(this.&configurePluginDependencies)
    }

    // TODO(54566, 48918): Can remove once the issues are resolved.
    //  This means all references to `.flutter-plugins` are then removed and
    //  apps only depend exclusively on the `plugins` property in `.flutter-plugins-dependencies`.
    /**
     * Workaround to load non-native plugins for developers who may still use an
     * old `settings.gradle` which includes all the plugins from the
     * `.flutter-plugins` file, even if not made for Android.
     * The settings.gradle then:
     *     1) tries to add the android plugin implementation, which does not
     *        exist at all, but is also not included successfully
     *        (which does not throw an error and therefore isn't a problem), or
     *     2) includes the plugin successfully as a valid android plugin
     *        directory exists, even if the surrounding flutter package does not
     *        support the android platform (see e.g. apple_maps_flutter: 1.0.1).
     *        So as it's included successfully it expects to be added as API.
     *        This is only possible by taking all plugins into account, which
     *        only appear on the `dependencyGraph` and in the `.flutter-plugins` file.
     * So in summary the plugins are currently selected from the `dependencyGraph`
     * and filtered then with the [doesSupportAndroidPlatform] method instead of
     * just using the `plugins.android` list.
     */
    private void configureLegacyPluginEachProjects(Project project) {
        try {
            // Read the contents of the settings.gradle file.
            // Remove block/line comments
            String settingsText = FlutterPluginUtils.getSettingsGradleFileFromProjectDir(project.projectDir, project.logger).text
            settingsText = settingsText.replaceAll(/(?s)\/\*.*?\*\//, '').replaceAll(/(?m)\/\/.*$/, '')

            if (!settingsText.contains("'.flutter-plugins'")) {
                return
            }
        } catch (FileNotFoundException ignored) {
            throw new GradleException("settings.gradle/settings.gradle.kts does not exist: " +
                    "${FlutterPluginUtils.getSettingsGradleFileFromProjectDir(project.projectDir, project.logger).absolutePath}")
        }
        // TODO(matanlurey): https://github.com/flutter/flutter/issues/48918.
        project.logger.quiet("Warning: This project is still reading the deprecated '.flutter-plugins. file.")
        project.logger.quiet("In an upcoming stable release support for this file will be completely removed and your build will fail.")
        project.logger.quiet("See https:/flutter.dev/to/flutter-plugins-configuration.")
        List<Map<String, Object>> deps = getPluginDependencies(project)
        List<String> plugins = getPluginList(project).collect { it.name as String }
        deps.removeIf { plugins.contains(it.name) }
        deps.each {
            Project pluginProject = project.rootProject.findProject(":${it.name}")
            if (pluginProject == null) {
                // Plugin was not included in `settings.gradle`, but is listed in `.flutter-plugins`.
                project.logger.error("Plugin project :${it.name} listed, but not found. Please fix your settings.gradle/settings.gradle.kts.")
            } else if (FlutterPluginUtils.pluginSupportsAndroidPlatform(pluginProject)) {
                // Plugin has a functioning `android` folder and is included successfully, although it's not supported.
                // It must be configured nonetheless, to not throw an "Unresolved reference" exception.
                configurePluginProject(it)
            /* groovylint-disable-next-line EmptyElseBlock */
            } else {
            // Plugin has no or an empty `android` folder. No action required.
            }
        }
    }

    /** Adds the plugin project dependency to the app project. */
    private void configurePluginProject(Map<String, Object> pluginObject) {
        assert(pluginObject.name instanceof String)
        Project pluginProject = project.rootProject.findProject(":${pluginObject.name}")
        if (pluginProject == null) {
            return
        }
        // Apply the "flutter" Gradle extension to plugins so that they can use it's vended
        // compile/target/min sdk values.
        pluginProject.extensions.create("flutter", FlutterExtension)

        // Add plugin dependency to the app project. We only want to add dependency
        // for dev dependencies in non-release builds.
        project.afterEvaluate {
            project.android.buildTypes.all { buildType ->
                if (!pluginObject.dev_dependency || buildType.name != 'release') {
                    project.dependencies.add("${buildType.name}Api", pluginProject)
                }
            }
        }

        Closure addEmbeddingDependencyToPlugin = { BuildType buildType ->
            String flutterBuildMode = FlutterPluginUtils.buildModeFor(buildType)
            // In AGP 3.5, the embedding must be added as an API implementation,
            // so java8 features are desugared against the runtime classpath.
            // For more, see https://github.com/flutter/flutter/issues/40126
            if (!FlutterPluginUtils.supportsBuildMode(project, flutterBuildMode)) {
                return
            }
            if (!pluginProject.hasProperty("android")) {
                return
            }
            // Copy build types from the app to the plugin.
            // This allows to build apps with plugins and custom build types or flavors.
            pluginProject.android.buildTypes {
                "${buildType.name}" {}
            }
            // The embedding is API dependency of the plugin, so the AGP is able to desugar
            // default method implementations when the interface is implemented by a plugin.
            //
            // See https://issuetracker.google.com/139821726, and
            // https://github.com/flutter/flutter/issues/72185 for more details.
            FlutterPluginUtils.addApiDependencies(
              pluginProject,
              buildType.name,
              "io.flutter:flutter_embedding_$flutterBuildMode:$engineVersion"
            )
        }

        // Wait until the Android plugin loaded.
        pluginProject.afterEvaluate {
            // Checks if there is a mismatch between the plugin compileSdkVersion and the project compileSdkVersion.
            if (pluginProject.android.compileSdkVersion > project.android.compileSdkVersion) {
                project.logger.quiet("Warning: The plugin ${pluginObject.name} requires Android SDK version ${getCompileSdkFromProject(pluginProject)} or higher.")
                project.logger.quiet("For more information about build configuration, see $kWebsiteDeploymentAndroidBuildConfig.")
            }

            project.android.buildTypes.all(addEmbeddingDependencyToPlugin)
        }
    }

    private void forceNdkDownload(Project gradleProject, String flutterSdkRootPath) {
        // If the project is already configuring a native build, we don't need to do anything.
        Boolean forcingNotRequired = gradleProject.android.externalNativeBuild.cmake.path != null
        if (forcingNotRequired) {
            return
        }

        // Otherwise, point to an empty CMakeLists.txt, and ignore associated warnings.
        gradleProject.android {
            externalNativeBuild {
                cmake {
                    // Respect the existing configuration if it exists - the NDK will already be
                    // downloaded in this case.
                    path = flutterSdkRootPath + "/packages/flutter_tools/gradle/src/main/groovy/CMakeLists.txt"
                }
            }

            defaultConfig {
                externalNativeBuild {
                    cmake {
                        // CMake will print warnings when you try to build an empty project.
                        // These arguments silence the warnings - our project is intentionally
                        // empty.
                        arguments("-Wno-dev", "--no-warn-unused-cli")
                    }
                }
            }
        }
    }

    /** Prints error message and fix for any plugin compileSdkVersion or ndkVersion that are higher than the project. */
    private void detectLowCompileSdkVersionOrNdkVersion() {
        project.afterEvaluate {
            // Default to int max if using a preview version to skip the sdk check.
            int projectCompileSdkVersion = Integer.MAX_VALUE
            // Stable versions use ints, legacy preview uses string.
            if (getCompileSdkFromProject(project).isInteger()) {
                projectCompileSdkVersion = getCompileSdkFromProject(project) as int
            }
            int maxPluginCompileSdkVersion = projectCompileSdkVersion
            String ndkVersionIfUnspecified = "21.1.6352462" /* The default for AGP 4.1.0 used in old templates. */
            String projectNdkVersion = project.android.ndkVersion ?: ndkVersionIfUnspecified
            String maxPluginNdkVersion = projectNdkVersion
            int numProcessedPlugins = getPluginList(project).size()
            List<Tuple2<String, String>> pluginsWithHigherSdkVersion = []
            List<Tuple2<String, String>> pluginsWithDifferentNdkVersion = []

            getPluginList(project).each { pluginObject ->
                assert(pluginObject.name instanceof String)
                Project pluginProject = project.rootProject.findProject(":${pluginObject.name}")
                if (pluginProject == null) {
                    return
                }
                pluginProject.afterEvaluate {
                    // Default to int min if using a preview version to skip the sdk check.
                    int pluginCompileSdkVersion = Integer.MIN_VALUE
                    // Stable versions use ints, legacy preview uses string.
                    if (getCompileSdkFromProject(pluginProject).isInteger()) {
                        pluginCompileSdkVersion = getCompileSdkFromProject(pluginProject) as int
                    }

                    maxPluginCompileSdkVersion = Math.max(pluginCompileSdkVersion, maxPluginCompileSdkVersion)
                    if (pluginCompileSdkVersion > projectCompileSdkVersion) {
                        pluginsWithHigherSdkVersion.add(new Tuple(pluginProject.name, pluginCompileSdkVersion))
                    }

                    String pluginNdkVersion = pluginProject.android.ndkVersion ?: ndkVersionIfUnspecified
                    maxPluginNdkVersion = VersionUtils.mostRecentSemanticVersion(pluginNdkVersion, maxPluginNdkVersion)
                    if (pluginNdkVersion != projectNdkVersion) {
                        pluginsWithDifferentNdkVersion.add(new Tuple(pluginProject.name, pluginNdkVersion))
                    }

                    numProcessedPlugins--
                    if (numProcessedPlugins == 0) {
                        if (maxPluginCompileSdkVersion > projectCompileSdkVersion) {
                            project.logger.error("Your project is configured to compile against Android SDK $projectCompileSdkVersion, but the following plugin(s) require to be compiled against a higher Android SDK version:")
                            for (Tuple2<String, String> pluginToCompileSdkVersion : pluginsWithHigherSdkVersion) {
                                project.logger.error("- ${pluginToCompileSdkVersion.v1} compiles against Android SDK ${pluginToCompileSdkVersion.v2}")
                            }
                            File buildGradleFile = FlutterPluginUtils.getBuildGradleFileFromProjectDir(project.projectDir, project.logger)
                            project.logger.error("""\
                                Fix this issue by compiling against the highest Android SDK version (they are backward compatible).
                                Add the following to ${buildGradleFile.path}:

                                    android {
                                        compileSdk = ${maxPluginCompileSdkVersion}
                                        ...
                                    }
                                """.stripIndent())
                        }
                        if (maxPluginNdkVersion != projectNdkVersion) {
                            project.logger.error("Your project is configured with Android NDK $projectNdkVersion, but the following plugin(s) depend on a different Android NDK version:")
                            for (Tuple2<String, String> pluginToNdkVersion : pluginsWithDifferentNdkVersion) {
                                project.logger.error("- ${pluginToNdkVersion.v1} requires Android NDK ${pluginToNdkVersion.v2}")
                            }
                            File buildGradleFile = FlutterPluginUtils.getBuildGradleFileFromProjectDir(project.projectDir, project.logger)
                            project.logger.error("""\
                                Fix this issue by using the highest Android NDK version (they are backward compatible).
                                Add the following to ${buildGradleFile.path}:

                                    android {
                                        ndkVersion = \"${maxPluginNdkVersion}\"
                                        ...
                                    }
                                """.stripIndent())
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the portion of the compileSdkVersion string that corresponds to either the numeric
     * or string version.
     */
    private static String getCompileSdkFromProject(Project gradleProject) {
        return gradleProject.android.compileSdkVersion.substring(8)
    }

    /**
     * Add the dependencies on other plugin projects to the plugin project.
     * A plugin A can depend on plugin B. As a result, this dependency must be surfaced by
     * making the Gradle plugin project A depend on the Gradle plugin project B.
     */
    private void configurePluginDependencies(Map<String, Object> pluginObject) {
        assert(pluginObject.name instanceof String)
        Project pluginProject = project.rootProject.findProject(":${pluginObject.name}")
        if (pluginProject == null) {
            return
        }

        project.android.buildTypes.each { buildType ->
            String flutterBuildMode = FlutterPluginUtils.buildModeFor(buildType)
            if (flutterBuildMode == "release" && pluginObject.dev_dependency) {
                // This plugin is a dev dependency will not be included in the
                // release build, so no need to add its dependencies.
                return
            }
            def dependencies = pluginObject.dependencies
            assert(dependencies instanceof List<String>)
            dependencies.each { pluginDependencyName ->
                if (pluginDependencyName.empty) {
                    return
                }
                Project dependencyProject = project.rootProject.findProject(":$pluginDependencyName")
                if (dependencyProject == null) {
                    return
                }
                // Wait for the Android plugin to load and add the dependency to the plugin project.
                pluginProject.afterEvaluate {
                    pluginProject.dependencies {
                        implementation(dependencyProject)
                    }
                }
            }
        }
    }

    /**
     * Gets the list of plugins (as map) that support the Android platform.
     *
     * The map value contains either the plugins `name` (String),
     * its `path` (String), or its `dependencies` (List<String>).
     * See [NativePluginLoader#getPlugins] in packages/flutter_tools/gradle/src/main/groovy/native_plugin_loader.groovy
     */
    private List<Map<String, Object>> getPluginList(Project project) {
        if (pluginList == null) {
            pluginList = project.ext.nativePluginLoader.getPlugins(FlutterPluginUtils.getFlutterSourceDirectory(project))
        }
        return pluginList
    }

    /**
     * Gets the list of plugins (as map) that support the Android platform and are dependencies of the
     * Android project excluding dev dependencies.
     *
     * The map value contains either the plugins `name` (String),
     * its `path` (String), or its `dependencies` (List<String>).
     * See [NativePluginLoader#getPlugins] in packages/flutter_tools/gradle/src/main/groovy/native_plugin_loader.groovy
     */
    private List<Map<String, Object>> getPluginListWithoutDevDependencies(Project project) {
        List<Map<String, Object>> pluginListWithoutDevDependencies = []
        for (Map<String, Object> plugin in getPluginList(project)) {
            if (!plugin.dev_dependency) {
                pluginListWithoutDevDependencies += plugin
            }
        }
        return pluginListWithoutDevDependencies
    }

    // TODO(54566, 48918): Remove in favor of [getPluginList] only, see also
    //  https://github.com/flutter/flutter/blob/1c90ed8b64d9ed8ce2431afad8bc6e6d9acc4556/packages/flutter_tools/lib/src/flutter_plugins.dart#L212
    /** Gets the plugins dependencies from `.flutter-plugins-dependencies`. */
    private List<Map<String, Object>> getPluginDependencies(Project project) {
        if (pluginDependencies == null) {
            Map meta = project.ext.nativePluginLoader.getDependenciesMetadata(FlutterPluginUtils.getFlutterSourceDirectory(project))
            if (meta == null) {
                pluginDependencies = []
            } else {
                assert(meta.dependencyGraph instanceof List<Map>)
                pluginDependencies = meta.dependencyGraph as List<Map<String, Object>>
            }
        }
        return pluginDependencies
    }

    private String resolveProperty(String name, String defaultValue) {
        if (localProperties == null) {
            localProperties = readPropertiesIfExist(new File(project.projectDir.parentFile, "local.properties"))
        }
        return project.findProperty(name) ?: localProperties?.getProperty(name, defaultValue)
    }

    private List<String> getTargetPlatforms() {
        final String propTargetPlatform = "target-platform"
        if (!project.hasProperty(propTargetPlatform)) {
            return DEFAULT_PLATFORMS
        }
        return project.property(propTargetPlatform).split(",").collect {
            if (!PLATFORM_ARCH_MAP[it]) {
                throw new GradleException("Invalid platform: $it.")
            }
            return it
        }
    }

    private boolean isFlutterAppProject() {
        return project.android.hasProperty("applicationVariants")
    }

    private void addFlutterTasks(Project project) {
        if (project.state.failure) {
            return
        }
        String[] fileSystemRootsValue = null
        final String propFileSystemRoots = "filesystem-roots"
        if (project.hasProperty(propFileSystemRoots)) {
            fileSystemRootsValue = project.property(propFileSystemRoots).split("\\|")
        }
        String fileSystemSchemeValue = null
        final String propFileSystemScheme = "filesystem-scheme"
        if (project.hasProperty(propFileSystemScheme)) {
            fileSystemSchemeValue = project.property(propFileSystemScheme)
        }
        Boolean trackWidgetCreationValue = true
        final String propTrackWidgetCreation = "track-widget-creation"
        if (project.hasProperty(propTrackWidgetCreation)) {
            trackWidgetCreationValue = project.property(propTrackWidgetCreation).toBoolean()
        }
        String frontendServerStarterPathValue = null
        final String propFrontendServerStarterPath = "frontend-server-starter-path"
        if (project.hasProperty(propFrontendServerStarterPath)) {
            frontendServerStarterPathValue = project.property(propFrontendServerStarterPath)
        }
        String extraFrontEndOptionsValue = null
        final String propExtraFrontEndOptions = "extra-front-end-options"
        if (project.hasProperty(propExtraFrontEndOptions)) {
            extraFrontEndOptionsValue = project.property(propExtraFrontEndOptions)
        }
        String extraGenSnapshotOptionsValue = null
        final String propExtraGenSnapshotOptions = "extra-gen-snapshot-options"
        if (project.hasProperty(propExtraGenSnapshotOptions)) {
            extraGenSnapshotOptionsValue = project.property(propExtraGenSnapshotOptions)
        }
        String splitDebugInfoValue = null
        final String propSplitDebugInfo = "split-debug-info"
        if (project.hasProperty(propSplitDebugInfo)) {
            splitDebugInfoValue = project.property(propSplitDebugInfo)
        }
        Boolean dartObfuscationValue = false
        final String propDartObfuscation = "dart-obfuscation"
        if (project.hasProperty(propDartObfuscation)) {
            dartObfuscationValue = project.property(propDartObfuscation).toBoolean()
        }
        Boolean treeShakeIconsOptionsValue = false
        final String propTreeShakeIcons = "tree-shake-icons"
        if (project.hasProperty(propTreeShakeIcons)) {
            treeShakeIconsOptionsValue = project.property(propTreeShakeIcons).toBoolean()
        }
        String dartDefinesValue = null
        final String propDartDefines = "dart-defines"
        if (project.hasProperty(propDartDefines)) {
            dartDefinesValue = project.property(propDartDefines)
        }
        String performanceMeasurementFileValue
        final String propPerformanceMeasurementFile = "performance-measurement-file"
        if (project.hasProperty(propPerformanceMeasurementFile)) {
            performanceMeasurementFileValue = project.property(propPerformanceMeasurementFile)
        }
        String codeSizeDirectoryValue
        final String propCodeSizeDirectory = "code-size-directory"
        if (project.hasProperty(propCodeSizeDirectory)) {
            codeSizeDirectoryValue = project.property(propCodeSizeDirectory)
        }
        Boolean deferredComponentsValue = false
        final String propDeferredComponents = "deferred-components"
        if (project.hasProperty(propDeferredComponents)) {
            deferredComponentsValue = project.property(propDeferredComponents).toBoolean()
        }
        Boolean validateDeferredComponentsValue = true
        final String propValidateDeferredComponents = "validate-deferred-components"
        if (project.hasProperty(propValidateDeferredComponents)) {
            validateDeferredComponentsValue = project.property(propValidateDeferredComponents).toBoolean()
        }
        addTaskForJavaVersion(project)
        if (isFlutterAppProject()) {
            addTaskForPrintBuildVariants(project)
            addTasksForOutputsAppLinkSettings(project)
        }
        List<String> targetPlatforms = getTargetPlatforms()
        def addFlutterDeps = { variant ->
            if (FlutterPluginUtils.shouldProjectSplitPerAbi(project)) {
                variant.outputs.each { output ->
                    // Assigns the new version code to versionCodeOverride, which changes the version code
                    // for only the output APK, not for the variant itself. Skipping this step simply
                    // causes Gradle to use the value of variant.versionCode for the APK.
                    // For more, see https://developer.android.com/studio/build/configure-apk-splits
                    Integer abiVersionCode = ABI_VERSION[output.getFilter(OutputFile.ABI)]
                    if (abiVersionCode != null) {
                        output.versionCodeOverride =
                            abiVersionCode * 1000 + variant.versionCode
                    }
                }
            }
            // Build an AAR when this property is defined.
            boolean isBuildingAar = project.hasProperty("is-plugin")
            // In add to app scenarios, a Gradle project contains a `:flutter` and `:app` project.
            // `:flutter` is used as a subproject when these tasks exists and the build isn't building an AAR.
            Task packageAssets
            Task cleanPackageAssets
            try {
                packageAssets = project.tasks.named("package${variant.name.capitalize()}Assets").get()
            } catch (UnknownTaskException ignored) {
                packageAssets = null
            }
            try {
                cleanPackageAssets = project.tasks.named("cleanPackage${variant.name.capitalize()}Assets").get()
            } catch (UnknownTaskException ignored) {
                cleanPackageAssets = null
            }
            boolean isUsedAsSubproject = packageAssets && cleanPackageAssets && !isBuildingAar

            String variantBuildMode = FlutterPluginUtils.buildModeFor(variant.buildType)
            String flavorValue = variant.getFlavorName()
            String taskName = FlutterPluginUtils.toCamelCase(["compile", FLUTTER_BUILD_PREFIX, variant.name])
            // Be careful when configuring task below, Groovy has bizarre
            // scoping rules: writing `verbose isVerbose()` means calling
            // `isVerbose` on the task itself - which would return `verbose`
            // original value. You either need to hoist the value
            // into a separate variable `verbose verboseValue` or prefix with
            // `this` (`verbose this.isVerbose()`).
            TaskProvider<FlutterTask> compileTaskProvider = project.tasks.register(taskName , FlutterTask) {
                flutterRoot(this.flutterRoot)
                flutterExecutable(this.flutterExecutable)
                buildMode(variantBuildMode)
                minSdkVersion(variant.mergedFlavor.minSdkVersion.apiLevel)
                localEngine(this.localEngine)
                localEngineHost(this.localEngineHost)
                localEngineSrcPath(this.localEngineSrcPath)
                targetPath(FlutterPluginUtils.getFlutterTarget(project))
                verbose(FlutterPluginUtils.isProjectVerbose(project))
                fastStart(FlutterPluginUtils.isProjectFastStart(project))
                fileSystemRoots(fileSystemRootsValue)
                fileSystemScheme(fileSystemSchemeValue)
                trackWidgetCreation(trackWidgetCreationValue)
                targetPlatformValues = targetPlatforms
                sourceDir(FlutterPluginUtils.getFlutterSourceDirectory(project))
                intermediateDir(project.file(project.layout.buildDirectory.dir("$INTERMEDIATES_DIR/flutter/${variant.name}/")))
                frontendServerStarterPath(frontendServerStarterPathValue)
                extraFrontEndOptions(extraFrontEndOptionsValue)
                extraGenSnapshotOptions(extraGenSnapshotOptionsValue)
                splitDebugInfo(splitDebugInfoValue)
                treeShakeIcons(treeShakeIconsOptionsValue)
                dartObfuscation(dartObfuscationValue)
                dartDefines(dartDefinesValue)
                performanceMeasurementFile(performanceMeasurementFileValue)
                codeSizeDirectory(codeSizeDirectoryValue)
                deferredComponents(deferredComponentsValue)
                validateDeferredComponents(validateDeferredComponentsValue)
                flavor(flavorValue)
            }
            Task compileTask = compileTaskProvider.get()
            File libJar = project.file(project.layout.buildDirectory.dir("$INTERMEDIATES_DIR/flutter/${variant.name}/libs.jar"))
            TaskProvider<Jar> packJniLibsTaskProvider = project.tasks.register("packJniLibs${FLUTTER_BUILD_PREFIX}${variant.name.capitalize()}", Jar) {
                destinationDirectory = libJar.parentFile
                archiveFileName = libJar.name
                dependsOn(compileTask)
                targetPlatforms.each { targetPlatform ->
                    String abi = PLATFORM_ARCH_MAP[targetPlatform]
                    from("${compileTask.intermediateDir}/${abi}") {
                        include("*.so")
                        // Move `app.so` to `lib/<abi>/libapp.so`
                        rename { String filename ->
                            return "lib/${abi}/lib${filename}"
                        }
                    }
                    // Copy the native assets created by build.dart and placed in build/native_assets by flutter assemble.
                    // The `$project.layout.buildDirectory` is '.android/Flutter/build/' instead of 'build/'.
                    String buildDir = "${FlutterPluginUtils.getFlutterSourceDirectory(project)}/build"
                    String nativeAssetsDir = "${buildDir}/native_assets/android/jniLibs/lib"
                    from("${nativeAssetsDir}/${abi}") {
                        include("*.so")
                        rename { String filename ->
                            return "lib/${abi}/${filename}"
                        }
                    }
                }
            }
            Task packJniLibsTask = packJniLibsTaskProvider.get()
            FlutterPluginUtils.addApiDependencies(project, variant.name, project.files {
                packJniLibsTask
            })
            TaskProvider<Copy> copyFlutterAssetsTaskProvider = project.tasks.register(
            "copyFlutterAssets${variant.name.capitalize()}" , Copy
            ) {
                dependsOn(compileTask)
                with(compileTask.assets)
                String currentGradleVersion = project.getGradle().getGradleVersion()

                // See https://docs.gradle.org/current/javadoc/org/gradle/api/file/ConfigurableFilePermissions.html
                // See https://github.com/flutter/flutter/pull/50047
                if (FlutterPluginUtils.compareVersionStrings(currentGradleVersion, "8.3") >= 0) {
                    filePermissions {
                        user {
                            read = true
                            write = true
                        }
                    }
                } else {
                    // See https://docs.gradle.org/8.2/dsl/org.gradle.api.tasks.Copy.html#org.gradle.api.tasks.Copy:fileMode
                    // See https://github.com/flutter/flutter/pull/50047
                    fileMode(0644)
                }
                if (isUsedAsSubproject) {
                    dependsOn(packageAssets)
                    dependsOn(cleanPackageAssets)
                    into(packageAssets.outputDir)
                    return
                }
                // `variant.mergeAssets` will be removed at the end of 2019.
                def mergeAssets = variant.hasProperty("mergeAssetsProvider") ?
                    variant.mergeAssetsProvider.get() : variant.mergeAssets
                dependsOn(mergeAssets)
                dependsOn("clean${mergeAssets.name.capitalize()}")
                mergeAssets.mustRunAfter("clean${mergeAssets.name.capitalize()}")
                into(mergeAssets.outputDir)
            }
            Task copyFlutterAssetsTask = copyFlutterAssetsTaskProvider.get()
            if (!isUsedAsSubproject) {
                def variantOutput = variant.outputs.first()
                def processResources = variantOutput.hasProperty(propProcessResourcesProvider) ?
                    variantOutput.processResourcesProvider.get() : variantOutput.processResources
                processResources.dependsOn(copyFlutterAssetsTask)
            }
            // The following tasks use the output of copyFlutterAssetsTask,
            // so it's necessary to declare it as an dependency since Gradle 8.
            // See https://docs.gradle.org/8.1/userguide/validation_problems.html#implicit_dependency.
            def tasksToCheck = [
                    "compress${variant.name.capitalize()}Assets",
                    "bundle${variant.name.capitalize()}Aar",
                    "bundle${variant.name.capitalize()}LocalLintAar"
            ]
            tasksToCheck.each { taskTocheck ->
                try {
                    project.tasks.named(taskTocheck).configure { task ->
                        task.dependsOn(copyFlutterAssetsTask)
                    }
                } catch (UnknownTaskException ignored) {
                }
            }
            return copyFlutterAssetsTask
        } // end def addFlutterDeps
        if (isFlutterAppProject()) {
            AbstractAppExtension android = (AbstractAppExtension) project.extensions.findByName("android")
            android.applicationVariants.configureEach { variant ->
                Task assembleTask = variant.assembleProvider.get()
                if (!FlutterPluginUtils.shouldConfigureFlutterTask(project, assembleTask)) {
                    return
                }
                Task copyFlutterAssetsTask = addFlutterDeps(variant)
                BaseVariantOutput variantOutput = variant.outputs.first()
                ProcessAndroidResources processResources = variantOutput.hasProperty(propProcessResourcesProvider) ?
                    variantOutput.processResourcesProvider.get() : variantOutput.processResources
                processResources.dependsOn(copyFlutterAssetsTask)

                // Copy the output APKs into a known location, so `flutter run` or `flutter build apk`
                // can discover them. By default, this is `<app-dir>/build/app/outputs/flutter-apk/<filename>.apk`.
                //
                // The filename consists of `app<-abi>?<-flavor-name>?-<build-mode>.apk`.
                // Where:
                //   * `abi` can be `armeabi-v7a|arm64-v8a|x86|x86_64` only if the flag `split-per-abi` is set.
                //   * `flavor-name` is the flavor used to build the app in lower case if the assemble task is called.
                //   * `build-mode` can be `release|debug|profile`.
                variant.outputs.each { output ->
                    assembleTask.doLast {
                        PackageAndroidArtifact packageApplicationProvider = variant.packageApplicationProvider.get()
                        Directory outputDirectory = packageApplicationProvider.outputDirectory.get()
                        String outputDirectoryStr = outputDirectory.toString()
                        String filename = "app"
                        String abi = output.getFilter(OutputFile.ABI)
                        if (abi != null && !abi.isEmpty()) {
                            filename += "-${abi}"
                        }
                        if (variant.flavorName != null && !variant.flavorName.isEmpty()) {
                            filename += "-${variant.flavorName.toLowerCase()}"
                        }
                        filename += "-${FlutterPluginUtils.buildModeFor(variant.buildType)}"
                        project.copy {
                            from new File("$outputDirectoryStr/${output.outputFileName}")
                            into new File("${project.layout.buildDirectory.dir("outputs/flutter-apk").get()}")
                            rename {
                                return "${filename}.apk"
                            }
                        }
                    }
                }
            }
            // Copy the native assets created by build.dart and placed here by flutter assemble.
            // This path is not flavor specific and must only be added once.
            // If support for flavors is added to native assets, then they must only be added
            // once per flavor; see https://github.com/dart-lang/native/issues/1359.
            String nativeAssetsDir = "${project.layout.buildDirectory.get()}/../native_assets/android/jniLibs/lib/"
            android.sourceSets.main.jniLibs.srcDir(nativeAssetsDir)
            configurePlugins(project)
            detectLowCompileSdkVersionOrNdkVersion()
            return
        }
        // Flutter host module project (Add-to-app).
        String hostAppProjectName = project.rootProject.hasProperty("flutter.hostAppProjectName") ? project.rootProject.property("flutter.hostAppProjectName") : "app"
        Project appProject = project.rootProject.findProject(":${hostAppProjectName}")
        assert(appProject != null) : "Project :${hostAppProjectName} doesn't exist. To customize the host app project name, set `flutter.hostAppProjectName=<project-name>` in gradle.properties."
        // Wait for the host app project configuration.
        appProject.afterEvaluate {
            assert(appProject.android != null)
            project.android.libraryVariants.all { libraryVariant ->
                Task copyFlutterAssetsTask
                appProject.android.applicationVariants.all { appProjectVariant ->
                    Task appAssembleTask = appProjectVariant.assembleProvider.get()
                    if (!FlutterPluginUtils.shouldConfigureFlutterTask(project, appAssembleTask)) {
                        return
                    }
                    // Find a compatible application variant in the host app.
                    //
                    // For example, consider a host app that defines the following variants:
                    // | ----------------- | ----------------------------- |
                    // |   Build Variant   |   Flutter Equivalent Variant  |
                    // | ----------------- | ----------------------------- |
                    // |   freeRelease     |   release                     |
                    // |   freeDebug       |   debug                       |
                    // |   freeDevelop     |   debug                       |
                    // |   profile         |   profile                     |
                    // | ----------------- | ----------------------------- |
                    //
                    // This mapping is based on the following rules:
                    // 1. If the host app build variant name is `profile` then the equivalent
                    //    Flutter variant is `profile`.
                    // 2. If the host app build variant is debuggable
                    //    (e.g. `buildType.debuggable = true`), then the equivalent Flutter
                    //    variant is `debug`.
                    // 3. Otherwise, the equivalent Flutter variant is `release`.
                    String variantBuildMode = FlutterPluginUtils.buildModeFor(libraryVariant.buildType)
                    if (FlutterPluginUtils.buildModeFor(appProjectVariant.buildType) != variantBuildMode) {
                        return
                    }
                    copyFlutterAssetsTask = copyFlutterAssetsTask ?: addFlutterDeps(libraryVariant)
                    Task mergeAssets = project
                        .tasks
                        .findByPath(":${hostAppProjectName}:merge${appProjectVariant.name.capitalize()}Assets")
                    assert(mergeAssets)
                    mergeAssets.dependsOn(copyFlutterAssetsTask)
                }
            }
        }
        configurePlugins(project)
        detectLowCompileSdkVersionOrNdkVersion()
    }
}

class FlutterTask extends BaseFlutterTask {

    @OutputDirectory
    File getOutputDirectory() {
        return intermediateDir
    }

    @Internal
    String getAssetsDirectory() {
        return "${outputDirectory}/flutter_assets"
    }

    @Internal
    CopySpec getAssets() {
        return project.copySpec {
            from("${intermediateDir}")
            include("flutter_assets/**") // the working dir and its files
        }
    }

    @Internal
    CopySpec getSnapshots() {
        return project.copySpec {
            from("${intermediateDir}")

            if (buildMode == "release" || buildMode == "profile") {
                targetPlatformValues.each {
                    include("${PLATFORM_ARCH_MAP[targetArch]}/app.so")
                }
            }
        }
    }

    FileCollection readDependencies(File dependenciesFile, Boolean inputs) {
        if (dependenciesFile.exists()) {
            // Dependencies file has Makefile syntax:
            //   <target> <files>: <source> <files> <separated> <by> <non-escaped space>
            String depText = dependenciesFile.text
            // So we split list of files by non-escaped(by backslash) space,
            def matcher = depText.split(": ")[inputs ? 1 : 0] =~ /(\\ |\S)+/
            // then we replace all escaped spaces with regular spaces
            def depList = matcher.collect{ it[0].replaceAll("\\\\ ", " ") }
            return project.files(depList)
        }
        return project.files()
    }

    @InputFiles
    FileCollection getSourceFiles() {
        FileCollection sources = project.files()
        for (File depfile in getDependenciesFiles()) {
            sources += readDependencies(depfile, true)
        }
        return sources + project.files("pubspec.yaml")
    }

    @OutputFiles
    FileCollection getOutputFiles() {
        FileCollection sources = project.files()
        for (File depfile in getDependenciesFiles()) {
            sources += readDependencies(depfile, false)
        }
        return sources
    }

    @TaskAction
    void build() {
        buildBundle()
    }
}
