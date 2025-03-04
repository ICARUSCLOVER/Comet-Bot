package io.github.starwishsama.comet.commands.chats

import io.github.starwishsama.comet.BotVariables
import io.github.starwishsama.comet.api.annotations.CometCommand
import io.github.starwishsama.comet.api.command.CommandProps
import io.github.starwishsama.comet.api.command.interfaces.ChatCommand
import io.github.starwishsama.comet.enums.UserLevel
import io.github.starwishsama.comet.objects.BotUser
import io.github.starwishsama.comet.objects.draw.items.ArkNightOperator
import io.github.starwishsama.comet.objects.draw.pool.ArkNightPool
import io.github.starwishsama.comet.utils.BotUtil
import io.github.starwishsama.comet.utils.BotUtil.sendMessage
import io.github.starwishsama.comet.utils.DrawUtil
import io.github.starwishsama.comet.utils.StringUtil.convertToChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mamoe.mirai.contact.isOperator
import net.mamoe.mirai.message.GroupMessageEvent
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.message.data.At
import net.mamoe.mirai.message.data.EmptyMessageChain
import net.mamoe.mirai.message.data.MessageChain
import net.mamoe.mirai.message.upload
import org.apache.commons.lang3.StringUtils

@CometCommand
@Suppress("SpellCheckingInspection")
class ArkNightCommand : ChatCommand {
    private val pool = ArkNightPool()

    override suspend fun execute(event: MessageEvent, args: List<String>, user: BotUser): MessageChain {
        if (BotUtil.hasNoCoolDown(event.sender.id, 15)) {
            if (args.isNotEmpty()) {
                when (args[0]) {
                    "单次寻访", "1", "单抽" -> {
                        val list = pool.getArkDrawResult(user)
                        return if (BotVariables.cfg.arkDrawUseImage) {
                            generatePictureGachaResult(event, user, list)
                        } else {
                            pool.getArkDrawResultAsString(user, list).sendMessage()
                        }
                    }
                    "十连寻访", "10", "十连" -> {
                        val list: List<ArkNightOperator> = pool.getArkDrawResult(user, 10)
                        return if (BotVariables.cfg.arkDrawUseImage) {
                            generatePictureGachaResult(event, user, list)
                        } else {
                            pool.getArkDrawResultAsString(user, list).sendMessage()
                        }
                    }
                    "一井", "300", "来一井" -> {
                        val list: List<ArkNightOperator> = pool.getArkDrawResult(user, 300)
                        return if (BotVariables.cfg.arkDrawUseImage) {
                            generatePictureGachaResult(event, user, list)
                        } else {
                            pool.getArkDrawResultAsString(user, list).sendMessage()
                        }
                    }
                    else -> {
                        return if (user.isBotAdmin() || (event is GroupMessageEvent && event.sender.isOperator())) {
                            if (StringUtils.isNumeric(args[0])) {
                                val gachaTime: Int = try {
                                    args[0].toInt()
                                } catch (e: NumberFormatException) {
                                    return getHelp().convertToChain()
                                }

                                val list: List<ArkNightOperator> = pool.getArkDrawResult(user, gachaTime)
                                return if (BotVariables.cfg.arkDrawUseImage) {
                                    generatePictureGachaResult(event, user, list)
                                } else {
                                    pool.getArkDrawResultAsString(user, list).sendMessage()
                                }
                            } else {
                                getHelp().convertToChain()
                            }
                        } else {
                            "次数抽卡功能已停用, 请使用单抽/十连/一井的方式抽卡!".sendMessage()
                        }
                    }
                }
            } else {
                return getHelp().convertToChain()
            }
        }
        return EmptyMessageChain
    }

    override fun getProps(): CommandProps =
        CommandProps(
                "arknight",
                arrayListOf("ark", "xf", "方舟寻访"),
                "明日方舟寻访模拟器",
                "nbot.commands.arknight",
                UserLevel.USER
        )

    override fun getHelp(): String = """
         ============ 命令帮助 ============
         /ark 单次寻访/十连寻访/[次数]
    """.trimIndent()

    private suspend fun generatePictureGachaResult(event: MessageEvent, user: BotUser, ops: List<ArkNightOperator>): MessageChain {
        event.reply("请稍等...")

        return if (ops.isNotEmpty()) {
            // 只获取最后十个
            val result = DrawUtil.combineArkOpImage(if (ops.size <= 10) ops else ops.subList(ops.size - 11, ops.size - 1))
            if (result.lostOps.isNotEmpty())
                event.quoteReply(sendMessage("由于缺失资源文件, 以下干员无法显示 :(\n" +
                        buildString {
                            result.lostOps.forEach {
                                append("${it.name},")
                            }
                        }.removeSuffix(",")))
            val gachaImage = withContext(Dispatchers.IO) { result.image.upload(event.subject) }

            val reply = gachaImage.plus("\n").plus(pool.getArkDrawResultAsString(user, ops))

            if (event is GroupMessageEvent) At(event.sender).plus("\n").plus(reply) else reply
        } else {
            (DrawUtil.overTimeMessage + "\n剩余次数: ${user.commandTime}").convertToChain()
        }
    }
}