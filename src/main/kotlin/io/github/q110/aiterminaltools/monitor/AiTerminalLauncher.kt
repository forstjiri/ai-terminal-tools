package io.github.q110.aiterminaltools.monitor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Generates and removes the per-tab launcher scripts shared by all AI tools. */
object AiTerminalLauncher {
    data class Paths(val cmdPath: Path, val shPath: Path)

    fun write(
        toolsDir: Path,
        toolName: String,
        tabId: String,
        token: String,
        port: Int,
        customCommand: String,
        defaultCommand: String
    ): Paths {
        val cmdFile = toolsDir.resolve("run-$toolName-$tabId.cmd")
        val shFile = toolsDir.resolve("run-$toolName-$tabId.sh")
        val execCommand = customCommand.trim().ifEmpty { defaultCommand }

        Files.writeString(cmdFile, buildString {
            appendLine("@echo off")
            appendLine("set AITT_PORT=$port")
            appendLine("set AITT_TOKEN=$token")
            appendLine("set AITT_TAB_ID=$tabId")
            appendLine("set AITT_TOOL=$toolName")
            appendLine(execCommand)
        })

        Files.writeString(shFile, buildString {
            appendLine("#!/usr/bin/env bash")
            appendLine("export AITT_PORT=\"$port\"")
            appendLine("export AITT_TOKEN=\"$token\"")
            appendLine("export AITT_TAB_ID=\"$tabId\"")
            appendLine("export AITT_TOOL=\"$toolName\"")
            appendLine("exec $execCommand")
        })
        setExecutableIfPosix(shFile)
        return Paths(cmdFile, shFile)
    }

    fun cleanup(toolsDir: Path, toolName: String, tabId: String) {
        try {
            Files.deleteIfExists(toolsDir.resolve("run-$toolName-$tabId.cmd"))
            Files.deleteIfExists(toolsDir.resolve("run-$toolName-$tabId.sh"))
        } catch (_: Throwable) {
        }
    }

    private fun setExecutableIfPosix(path: Path) {
        try {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: Throwable) {
        }
    }
}
