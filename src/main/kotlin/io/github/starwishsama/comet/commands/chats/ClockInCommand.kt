package io.github.starwishsama.comet.commands.chats

import io.github.starwishsama.comet.api.annotations.CometCommand
import io.github.starwishsama.comet.api.command.CommandProps
import io.github.starwishsama.comet.api.command.interfaces.ChatCommand
import io.github.starwishsama.comet.enums.UserLevel
import io.github.starwishsama.comet.managers.ClockInManager
import io.github.starwishsama.comet.objects.BotUser
import io.github.starwishsama.comet.objects.checkin.ClockInData
import io.github.starwishsama.comet.utils.BotUtil.sendMessage
import io.github.starwishsama.comet.utils.StringUtil.convertToChain
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.message.GroupMessageEvent
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.message.data.EmptyMessageChain
import net.mamoe.mirai.message.data.MessageChain
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@CometCommand
class ClockInCommand : ChatCommand {
    override suspend fun execute(event: MessageEvent, args: List<String>, user: BotUser): MessageChain {
        if (event is GroupMessageEvent) {
            val id = event.group.id
            val data = ClockInManager.getNearestClockIn(id)
            return if (data != null) {
                if (isClockIn(data, event)) {
                    clockIn(event.sender, event, data)
                } else {
                    "你已经打卡过了!".sendMessage()
                }
            } else {
                "没有正在进行的打卡".sendMessage()
            }
        }
        return EmptyMessageChain
    }

    override fun getProps(): CommandProps =
        CommandProps("clockin", arrayListOf("打卡", "dk"), "打卡命令", "nbot.commands.clockin", UserLevel.USER)

    override fun getHelp(): String = ""

    override fun hasPermission(user: BotUser, e: MessageEvent): Boolean = user.compareLevel(getProps().level)

    private fun isClockIn(data: ClockInData, event: GroupMessageEvent): Boolean {
        if (data.checkedUsers.isNotEmpty()) {
            data.checkedUsers.forEach { member ->
                run {
                    return member.id == event.sender.id
                }
            }
        }
        return false
    }

    private fun clockIn(sender: Member, msg: GroupMessageEvent, data: ClockInData): MessageChain {
        val checkInTime = LocalDateTime.now()
        if (Duration.between(data.endTime, checkInTime).toMinutes() <= 5) {
            var result =
                "Bot > ${msg.sender.nameCardOrNick} 打卡成功!\n打卡时间: ${
                    checkInTime.format(
                        DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd HH:mm:ss"
                        )
                    )
                }"
            result += if (checkInTime.isAfter(data.endTime)) {
                data.lateUsers.add(sender)
                "\n签到状态: 迟到"
            } else {
                "\n签到状态: 成功"
            }

            data.checkedUsers.add(sender)
            return result.convertToChain()
        } else {
            return data.viewData().text.sendMessage()
        }
    }
}