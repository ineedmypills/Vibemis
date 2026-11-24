package com.ineedmypills.vibemis.binding.input

import com.ineedmypills.vibemis.GameMenu

fun interface GameInputDevice {
    /**
     * @return list of device specific game menu options, e.g. configure a controller's mouse mode
     */
    fun getGameMenuOptions(): List<GameMenu.MenuOption>
}
