package io.github.starwishsama.comet.api.command

import io.github.starwishsama.comet.BotVariables
import io.github.starwishsama.comet.api.command.interfaces.ChatCommand
import io.github.starwishsama.comet.api.command.interfaces.ConsoleCommand
import io.github.starwishsama.comet.api.command.interfaces.SuspendCommand
import io.github.starwishsama.comet.managers.GroupConfigManager
import io.github.starwishsama.comet.objects.BotUser
import io.github.starwishsama.comet.sessions.DaemonSession
import io.github.starwishsama.comet.sessions.Session
import io.github.starwishsama.comet.sessions.SessionManager
import io.github.starwishsama.comet.utils.BotUtil
import io.github.starwishsama.comet.utils.StringUtil.convertToChain
import io.github.starwishsama.comet.utils.StringUtil.getLastingTimeAsString
import io.github.starwishsama.comet.utils.debugS
import io.github.starwishsama.comet.utils.network.NetUtil
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.isBotMuted
import net.mamoe.mirai.event.subscribeMessages
import net.mamoe.mirai.message.GroupMessageEvent
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.message.data.*
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime

/**
 * 彗星 Bot 命令处理器
 *
 * 处理群聊/私聊聊天信息中存在的命令
 * @author Nameless
 */
object CommandExecutor {
    private val commands: MutableSet<ChatCommand> = mutableSetOf()
    private val consoleCommands = mutableListOf<ConsoleCommand>()

    /**
     * 注册命令
     *
     * @param command 要注册的命令
     */
    private fun setupCommand(command: ChatCommand) {
        if (!commands.contains(command) && command.canRegister()) {
            commands.add(command)
        } else {
            BotVariables.logger.warning("[命令] 正在尝试注册已有命令 ${command.getProps().name}")
        }
    }

    /**
     * 注册命令
     *
     * @param commands 要注册的命令集合
     */
    @Suppress("unused")
    fun setupCommand(commands: Array<ChatCommand>) {
        commands.forEach {
            if (!CommandExecutor.commands.contains(it) && it.canRegister()) {
                CommandExecutor.commands.add(it)
            }
        }
    }

    fun setupCommand(commands: Array<Any>) {
        commands.forEach {
            when (it) {
                is ChatCommand -> setupCommand(it)
                is ConsoleCommand ->
                    if (!consoleCommands.contains(it)) {
                        consoleCommands.plusAssign(it)
                    } else {
                        BotVariables.logger.warning("[命令] 正在尝试注册已有命令 ${it.getProps().name}")
                    }
                else -> {
                    BotVariables.logger.warning("[命令] 正在尝试注册非命令类 ${it.javaClass.simpleName}")
                }
            }
        }
    }


    /**
     * FIXME: 考虑多个 Comet 机器人在群内的问题, 优先处理原则
     */
    @ExperimentalTime
    fun startHandler(bot: Bot) {
        bot.subscribeMessages {
            always {
                val executedTime = LocalDateTime.now()
                if (sender.id != 80000000L) {
                    if (this is GroupMessageEvent && group.isBotMuted) return@always

                    val isCommand: Boolean
                    val result = dispatchCommand(this)

                    isCommand = result.msg !is EmptyMessageChain

                    try {
                        if (isCommand && result.msg.isNotEmpty()) {
                            this.subject.sendMessage(result.msg)
                        }
                    } catch (e: IllegalArgumentException) {
                        BotVariables.logger.warning("正在尝试发送空消息, 执行的命令为 $result")
                    }

                    if (isCommand) {
                        BotVariables.logger.debugS(
                                "[命令] 命令执行耗时 ${executedTime.getLastingTimeAsString(builtInMethod = true)}" +
                                        if (result.result.isOk()) ", 执行结果: ${result.result.name}" else ""
                        )
                    }
                }
            }
        }
    }

    /**
     * 执行消息中的命令
     *
     * @param event Mirai 消息命令 (聊天)
     */
    @ExperimentalTime
    suspend fun dispatchCommand(event: MessageEvent): ExecutedResult {
        val executedTime = LocalDateTime.now()
        val senderId = event.sender.id
        val message = event.message.contentToString()
        val cmd = getCommand(getCommandName(message))

        val debug = cmd?.getProps()?.name?.contentEquals("debug") == true

        val user = BotUser.getUserSafely(senderId)

        if (BotVariables.switch || debug) {
            try {
                val session = SessionManager.getSessionByEvent(event)

                /**
                 * 如果不是监听会话, 则停止尝试解析并执行可能的命令
                 * 反之在监听时仍然可以执行命令
                 */
                if (session != null && !handleSession(event, executedTime)) {
                    return ExecutedResult(EmptyMessageChain, cmd, CommandStatus.MoveToSession())
                }

                /** 检查是否在尝试执行被禁用命令 */
                if (cmd != null && event is GroupMessageEvent &&
                        GroupConfigManager.getConfigOrNew(event.group.id).isDisabledCommand(cmd)) {
                    return if (BotUtil.hasNoCoolDown(user.id)) {
                        ExecutedResult(BotUtil.sendMessage("该命令已被管理员禁用"), cmd, CommandStatus.Disabled())
                    } else {
                        ExecutedResult(EmptyMessageChain, cmd, CommandStatus.Disabled())
                    }
                }

                if (isCommandPrefix(message) && cmd != null) {
                    var splitMessage = message.split(" ")
                    splitMessage = splitMessage.subList(1, splitMessage.size)

                    BotVariables.logger.debug("[命令] $senderId 尝试执行命令: $message")

                    val status: CommandStatus

                    /** 检查是否有权限执行命令 */
                    val result: MessageChain = if (cmd.hasPermission(user, event)) {
                        status = CommandStatus.Success()
                        cmd.execute(event, splitMessage, user)
                    } else {
                        status = CommandStatus.NoPermission()
                        BotUtil.sendMessage("你没有权限!")
                    }

                    return ExecutedResult(result, cmd, status)
                }
            } catch (t: Throwable) {
                return if (NetUtil.isTimeout(t)) {
                    BotVariables.logger.warning("疑似连接超时: \n${t.stackTraceToString()}")
                    ExecutedResult("Bot > 在执行网络操作时连接超时: ${t.message ?: ""}".convertToChain(), cmd)
                } else {
                    BotVariables.logger.warning("[命令] 在试图执行命令时发生了一个错误, 原文: ${message.split(" ")}, 发送者: $senderId\n${t.stackTraceToString()}")
                    if (user.isBotOwner()) {
                        ExecutedResult(BotUtil.sendMessage("在试图执行命令时发生了一个错误\n简易报错信息 (如果有的话):\n${t.javaClass.name}: ${t.message}"), cmd, CommandStatus.Failed())
                    } else {
                        ExecutedResult(BotUtil.sendMessage("在试图执行命令时发生了一个错误, 请联系管理员"), cmd, CommandStatus.Failed())
                    }
                }
            }
        }
        return ExecutedResult(EmptyMessageChain, cmd)
    }

    /**
     * 执行后台命令
     *
     * @param content 纯文本命令 (后台)
     */
    suspend fun dispatchConsoleCommand(content: String): String {
        try {
            val cmd = getConsoleCommand(getCommandName(content))
            if (cmd != null) {
                val splitMessage = content.split(" ")
                val splitCommand = splitMessage.subList(1, splitMessage.size)
                BotVariables.logger.debug("[命令] 后台尝试执行命令: " + cmd.getProps().name)
                return cmd.execute(splitCommand)
            }
        } catch (t: Throwable) {
            BotVariables.logger.warning("[命令] 在试图执行命令时发生了一个错误, 原文: $content", t)
            return ""
        }
        return ""
    }

    /**
     * 处理会话
     * @return 是否为监听会话
     */
    @ExperimentalTime
    private suspend fun handleSession(event: MessageEvent, time: LocalDateTime): Boolean {
        val sender = event.sender
        if (!isCommandPrefix(event.message.contentToString())) {
            val session: Session? = SessionManager.getSessionByEvent(event)
            if (session != null) {
                val command = session.command
                if (command is SuspendCommand) {
                    command.handleInput(event, BotUser.getUserSafely(sender.id), session)
                }

                return session is DaemonSession
            }
        }

        BotVariables.logger.debug(
                "[会话] 处理会话耗时 ${time.getLastingTimeAsString(unit = TimeUnit.SECONDS, msMode = true)}"
        )
        return true
    }

    fun getCommand(cmdPrefix: String): ChatCommand? {
        val command = commands.parallelStream().filter {
            commandEquals(it, cmdPrefix)
        }.findFirst()

        return if (command.isPresent) command.get() else null
    }

    private fun getConsoleCommand(cmdPrefix: String): ConsoleCommand? {
        for (command in consoleCommands) {
            if (commandEquals(command, cmdPrefix)) {
                return command
            }
        }
        return null
    }

    private fun getCommandName(command: String): String {
        var cmdPrefix = command
        for (string: String in BotVariables.cfg.commandPrefix) {
            cmdPrefix = cmdPrefix.replace(string, "")
        }

        return cmdPrefix.split(" ")[0]
    }

    private fun isCommandPrefix(message: String): Boolean {
        if (message.isNotEmpty()) {
            BotVariables.cfg.commandPrefix.forEach {
                if (message.startsWith(it)) {
                    return true
                }
            }
        }

        return false
    }

    private fun commandEquals(cmd: ChatCommand, cmdName: String): Boolean {
        val props = cmd.getProps()
        when {
            props.name.contentEquals(cmdName) -> {
                return true
            }
            props.aliases.isNotEmpty() -> {
                val aliases = props.aliases.parallelStream().filter { it!!.contentEquals(cmdName) }.findFirst()
                if (aliases.isPresent) {
                    return true
                }
            }
            else -> {
                return false
            }
        }
        return false
    }

    private fun commandEquals(cmd: ConsoleCommand, cmdName: String): Boolean {
        val props = cmd.getProps()

        when {
            props.name.contentEquals(cmdName) -> {
                return true
            }
            props.aliases.isNotEmpty() -> {
                val name = props.aliases.parallelStream().filter { alias -> alias!!.contentEquals(cmdName) }.findFirst()
                if (name.isPresent) {
                    return true
                }
            }
            else -> {
                return false
            }
        }
        return false
    }

    fun MessageChain.doFilter(): MessageChain {
        if (BotVariables.cfg.filterWords.isNullOrEmpty()) {
            return this
        }

        val revampChain = LinkedList<SingleMessage>()
        this.forEach { revampChain.add(it) }

        var count = 0

        for (i in revampChain.indices) {
            if (revampChain[i] is PlainText) {
                var context = revampChain[i].content
                BotVariables.cfg.filterWords.forEach {
                    if (context.contains(it)) {
                        count++
                        context = context.replace(it.toRegex(), " ")
                    }

                    if (count > 5) {
                        return EmptyMessageChain
                    }
                }
                revampChain[i] = PlainText(context)
            }
        }

        return revampChain.asMessageChain()
    }

    fun countCommands(): Int = commands.size + consoleCommands.size

    fun getCommands() = commands

    data class ExecutedResult(val msg: MessageChain, val cmd: ChatCommand?, val result: CommandStatus = CommandStatus.Success())

    sealed class CommandStatus(val name: String) {
        class Success: CommandStatus("成功")
        class NoPermission: CommandStatus("无权限")
        class Failed: CommandStatus("失败")
        class Disabled: CommandStatus("命令被禁用")
        class MoveToSession: CommandStatus("移交会话处理")

        fun isOk(): Boolean = this == Success()
    }
}