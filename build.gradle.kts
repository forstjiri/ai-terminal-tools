import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.12.0"
}

group = "io.github.forstjiri"
version = "0.4.1"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val platformVersion = providers.gradleProperty("platformVersion").getOrElse("2025.1")
val platformType = providers.gradleProperty("platformType").getOrElse("IU")

dependencies {
    intellijPlatform {
        when (platformType.uppercase()) {
            "IC", "IU" -> intellijIdea(platformVersion)
            "PY"       -> pycharm(platformVersion)
            "WS"       -> webstorm(platformVersion)
            "GO"       -> goland(platformVersion)
            "PS"       -> phpstorm(platformVersion)
            "RM"       -> rubymine(platformVersion)
            "RD"       -> rider(platformVersion)
            "CL"       -> clion(platformVersion)
            "DG"       -> datagrip(platformVersion)
            else       -> intellijIdea(platformVersion)
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(listOf("default"))
    }

    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }

    pluginConfiguration {
        id = "io.github.forstjiri.aiterminaltool"
        name = "Opencode / Claude TUI integration"
        version = project.version.toString()
        description = """
            Use your favorite TUI AI coding agent inside IntelliJ with superpowers!

            <p>
              🛠️ There are many tools that integrate OpenCode or Claude, but complex GUIs never perfectly mirror the TUI functionality. On the flip side, pure TUI tools often lack seamless interaction between the IDE and the terminal.
              <br><br>
              💡 To bridge this gap, I forked <a href="https://github.com/Q-110/ai-terminal-tools">🍴 this</a> Chinese plugin, translated it, and modified it for a comfortable, seamless workflow. Feedback is highly welcome!
            </p>
            
            <h4>✨ Features</h4>
            <ul>
              <li>🎯 <strong>Jump to code:</strong> Instantly jump from terminal or console file references to exact editor locations, including line numbers and ranges.</li>
              <li>📤 <strong>Send context:</strong> Easily send editor selections, file paths, dragged files, and console error blocks directly to your active OpenCode or Claude Code terminal.</li>
              <li>🔍 <strong>Diff review:</strong> Review file changes from each AI turn side-by-side using the native IntelliJ diff window.</li>
              <li>↩️ <strong>One-click revert:</strong> Instantly revert AI changes and append the context to your next message.</li>
              <li>📝 <strong>Generate commit messages:</strong> Generate concise commit messages from selected files in the Commit panel, with a configurable AI tool, model, and custom prompt.</li>
              <li>🔔 <strong>Post-turn actions:</strong> Run custom commands automatically after a turn ends (e.g., play sounds using <code>pw-play</code>).</li>              
            </ul>
        """.trimIndent()
        changeNotes = """
            Forked plugin and added new functionality
        """.trimIndent()

        ideaVersion {
            sinceBuild = "251"
        }
    }
}
