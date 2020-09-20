package io.github.starwishsama.comet.commands.subcommands.console

import io.github.starwishsama.comet.BotVariables
import io.github.starwishsama.comet.annotations.CometCommand
import io.github.starwishsama.comet.commands.CommandExecutor
import io.github.starwishsama.comet.commands.CommandProps
import io.github.starwishsama.comet.commands.interfaces.ConsoleCommand
import io.github.starwishsama.comet.enums.UserLevel
import io.github.starwishsama.comet.sessions.SessionManager
import io.github.starwishsama.comet.utils.BotUtil
import kotlin.time.ExperimentalTime

@CometCommand
class DebugCommand : ConsoleCommand {
    @ExperimentalTime
    override suspend fun execute(args: List<String>): String {
        if (args.isNotEmpty()) {
            when (args[0]) {
                "sessions" -> {
                    val sessions = SessionManager.getSessions()
                    return StringBuilder("目前活跃的会话列表: \n").apply {
                        if (sessions.isEmpty()) {
                            append("无")
                        } else {
                            var i = 1
                            for (session in sessions) {
                                append(i + 1).append(" ").append(session.key.toString()).append("\n")
                                i++
                            }
                        }
                    }.trim().toString()
                }
                "info" ->
                    return ("彗星 Bot ${BotVariables.version}\n已注册的命令个数: ${CommandExecutor.countCommands()}\n${BotUtil.getMemoryUsage()}")
                "switch" -> {
                    BotVariables.switch = !BotVariables.switch

                    return if (!BotVariables.switch) {
                        "Bot > おつまち~"
                    } else {
                        "今日もかわいい!"
                    }
                }
            }
        }
        return ""
    }

    override fun getProps(): CommandProps = CommandProps("debug", mutableListOf(), "", "", UserLevel.CONSOLE)
}