package com.estrin217.terminal.core

import android.content.Context
import com.estrin217.terminal.core.logger.DebugLogger
import java.io.File

class PRootCommandBuilder(private val context: Context) {

    private var rootfsDir: File = TerminalConfig.getRootfsDir(context)
    private var shellCmd: String = "/bin/sh"
    private var distroId: String? = null
    private var workDir: String = "/home/programador"
    private val extraBinds: MutableList<String> = mutableListOf()
    private val extraEnv: MutableList<String> = mutableListOf()
    private var useLink2Symlink: Boolean = true

    fun setRootfsDir(dir: File): PRootCommandBuilder {
        this.rootfsDir = dir
        return this
    }

    fun setShellCommand(shell: String): PRootCommandBuilder {
        this.shellCmd = shell
        return this
    }

    fun setDistroId(id: String): PRootCommandBuilder {
        this.distroId = id
        return this
    }

    fun setWorkDir(dir: String): PRootCommandBuilder {
        this.workDir = dir
        return this
    }

    fun addBind(bind: String): PRootCommandBuilder {
        this.extraBinds.add(bind)
        return this
    }

    fun addEnv(env: String): PRootCommandBuilder {
        this.extraEnv.add(env)
        return this
    }

    fun enableLink2Symlink(enabled: Boolean): PRootCommandBuilder {
        this.useLink2Symlink = enabled
        return this
    }

    fun buildArgs(): Array<String> {
        val prootExe = TerminalConfig.getPRootExecutable(context).absolutePath

        val args = mutableListOf<String>()
        args.add(prootExe)

        if (useLink2Symlink) {
            args.add("--link2symlink")
        }

        args.add("-r")
        args.add(rootfsDir.absolutePath)
        args.add("-0")

        args.add("-b")
        args.add("/dev")
        args.add("-b")
        args.add("/dev/pts")
        args.add("-b")
        args.add("/proc")
        args.add("-b")
        args.add("/sys")
        args.add("-b")
        args.add("/etc/resolv.conf:/etc/resolv.conf")
        args.add("-b")
        args.add("/data/data/${context.packageName}:/data_priv")
        args.add("-w")
        args.add(workDir)

        extraBinds.forEach { bind ->
            args.add("-b")
            args.add(bind)
        }

        args.add(shellCmd)
        return args.toTypedArray()
    }

    fun buildEnv(): Array<String> {
        val rootfsTmpDir = File(rootfsDir, "tmp").absolutePath

        val env = mutableListOf(
            "TERM=xterm-256color",
            "HOME=/home/programador",
            "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
            "USER=programador",
            "LOGNAME=programador",
            "PROOT_TMP_DIR=$rootfsTmpDir",
            "PROOT_NO_SECCOMP=1",
        )

        when (distroId) {
            "alpine" -> env.add("LANG=C.UTF-8")
            else -> env.add("LANG=${java.util.Locale.getDefault().toLanguageTag()}.UTF-8")
        }

        extraEnv.forEach { env.add(it) }
        return env.toTypedArray()
    }

    companion object {
        fun create(context: Context): PRootCommandBuilder = PRootCommandBuilder(context)

        fun defaultArgs(context: Context): Array<String> {
            return create(context).buildArgs()
        }

        fun defaultEnv(context: Context): Array<String> {
            return create(context).buildEnv()
        }

        fun resolveShell(rootfsDir: File): String {
            val candidates = listOf(
                "/bin/bash", "/usr/bin/bash",
                "/bin/sh", "/usr/bin/sh",
                "/bin/dash", "/usr/bin/dash",
                "/bin/ash", "/usr/bin/ash"
            )
            for (shell in candidates) {
                val shellFile = File(rootfsDir, shell.removePrefix("/"))
                if (shellFile.exists() && shellFile.canExecute()) {
                    DebugLogger.i("PRootCommandBuilder", "Resolved shell: $shell")
                    return shell
                }
            }
            DebugLogger.w("PRootCommandBuilder", "No shell found in rootfs, falling back to /bin/sh")
            return "/bin/sh"
        }
    }
}
