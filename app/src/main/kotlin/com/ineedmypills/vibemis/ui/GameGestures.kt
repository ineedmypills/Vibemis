package com.ineedmypills.vibemis.ui

import com.ineedmypills.vibemis.binding.input.GameInputDevice

interface GameGestures {
    fun toggleKeyboard()

    fun showGameMenu(device: GameInputDevice?) {}
}
