package com.example.voicectl.exec

/** 原型 COORDS / CMD_LIST 的 Kotlin 落地：坐标库、步骤模板、指令编排三者分离 */
object CommandLibrary {
    data class CoordinateEntry(val key: String, val label: String, val x: Int, val y: Int)

    val common: List<CoordinateEntry> = listOf(
        CoordinateEntry("shop_open", "打开商店 / 装备栏", 900, 2100),
        CoordinateEntry("shop_close", "关闭商店", 1020, 2100),
        CoordinateEntry("sell_btn", "出售当前装备", 760, 1500),
        CoordinateEntry("cat_tab", "装备类别标签（保命装）", 500, 1000),
        CoordinateEntry("buy_btn", "购买所选装备", 900, 1500),
        CoordinateEntry("switch_plan", "方案切换按钮", 1020, 2050),
    )

    val slots: List<CoordinateEntry> = listOf(
        CoordinateEntry("slot_1", "1 号位（装备栏槽位）", 760, 1750),
        CoordinateEntry("slot_2", "2 号位", 820, 1750),
        CoordinateEntry("slot_3", "3 号位", 880, 1750),
        CoordinateEntry("slot_4", "4 号位", 940, 1750),
        CoordinateEntry("slot_5", "5 号位", 1000, 1750),
        CoordinateEntry("slot_6", "6 号位", 1060, 1750),
    )

    val equips: List<CoordinateEntry> = listOf(
        CoordinateEntry("cell_mingdao", "名刀格", 400, 900),
        CoordinateEntry("cell_fuhuo", "复活甲格", 500, 900),
        CoordinateEntry("cell_huiyue", "辉月格", 600, 900),
        CoordinateEntry("cell_xixue_shu", "吸血书格", 700, 900),
        CoordinateEntry("cell_xixue_dao", "吸血刀格", 400, 1030),
        CoordinateEntry("cell_bingjia", "冰甲格", 500, 1030),
        CoordinateEntry("cell_xuemo", "血魔格", 600, 1030),
    )

    val allCoordinates: List<CoordinateEntry> = common + slots + equips
    val coordinateByKey: Map<String, CoordinateEntry> = allCoordinates.associateBy { it.key }

    private fun equipSteps(cell: String, label: String): List<ExecStep> = listOf(
        ExecStep.Tap(900, 2100, "shop_open"),
        ExecStep.Wait(200),
        ExecStep.Tap(760, 1750, "slot_1"),
        ExecStep.Tap(760, 1500, "sell_btn"),
        ExecStep.Wait(150),
        ExecStep.Tap(500, 1000, "cat_tab"),
        ExecStep.Tap(coordinateByKey[cell]!!.x, coordinateByKey[cell]!!.y, cell),
        ExecStep.Tap(900, 1500, "buy_btn"),
        ExecStep.Verify("equip_owned"),
        ExecStep.Tap(1020, 2100, "shop_close"),
    )

    private fun positionSteps(slotKey: String): List<ExecStep> = listOf(
        ExecStep.Tap(coordinateByKey[slotKey]!!.x, coordinateByKey[slotKey]!!.y, slotKey),
        ExecStep.Verify("slot_selected"),
    )

    val commandActions: List<CommandAction> = listOf(
        CommandAction(0, "换名刀", equipSteps("cell_mingdao", "名刀")),
        CommandAction(1, "换复活甲", equipSteps("cell_fuhuo", "复活甲")),
        CommandAction(2, "换辉月", equipSteps("cell_huiyue", "辉月")),
        CommandAction(3, "换吸血书", equipSteps("cell_xixue_shu", "吸血书")),
        CommandAction(4, "换吸血刀", equipSteps("cell_xixue_dao", "吸血刀")),
        CommandAction(5, "换冰甲", equipSteps("cell_bingjia", "冰甲")),
        CommandAction(6, "换血魔", equipSteps("cell_xuemo", "血魔")),
        CommandAction(7, "换1号位", positionSteps("slot_1")),
        CommandAction(8, "换2号位", positionSteps("slot_2")),
        CommandAction(9, "换3号位", positionSteps("slot_3")),
        CommandAction(10, "换4号位", positionSteps("slot_4")),
        CommandAction(11, "换5号位", positionSteps("slot_5")),
        CommandAction(12, "换6号位", positionSteps("slot_6")),
        CommandAction(
            13, "切换位置", listOf(
                ExecStep.Tap(1020, 2050, "switch_plan"),
                ExecStep.Verify("plan_switched"),
            ),
        ),
        CommandAction(14, "未识别", emptyList()),
    )

    fun actionFor(commandId: Int): CommandAction =
        commandActions.getOrElse(commandId) { commandActions.last() }

    /** 面板 14 项快捷入口（7 装备 + 6 号位 + 切换位置） */
    val panelItems: List<Pair<String, Int>> = listOf(
        "名刀" to 0, "复活甲" to 1, "辉月" to 2, "吸血书" to 3, "吸血刀" to 4,
        "冰甲" to 5, "血魔" to 6, "1号位" to 7, "2号位" to 8, "3号位" to 9,
        "4号位" to 10, "5号位" to 11, "6号位" to 12, "切换位置" to 13,
    )
}
