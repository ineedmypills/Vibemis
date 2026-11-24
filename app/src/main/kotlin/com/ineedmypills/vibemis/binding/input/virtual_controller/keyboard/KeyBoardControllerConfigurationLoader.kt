package com.ineedmypills.vibemis.binding.input.virtual_controller.keyboard

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.view.KeyEvent
import com.ineedmypills.vibemis.Game
import com.ineedmypills.vibemis.preferences.PreferenceConfiguration
import com.ineedmypills.vibemis.GameMenu
import com.ineedmypills.vibemis.VibemisLog
import com.ineedmypills.vibemis.R
import com.ineedmypills.vibemis.binding.input.KeyboardTranslator
import com.ineedmypills.vibemis.nvstream.NvConnection
import java.lang.reflect.Field
import com.ineedmypills.vibemis.nvstream.input.KeyboardPacket
import com.ineedmypills.vibemis.utils.KeyConfigHelper
import com.ineedmypills.vibemis.utils.KeyMapper
import org.json.JSONException
import org.json.JSONObject
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

object KeyBoardControllerConfigurationLoader {
    const val OSC_PREFERENCE = "keyboard_axi_list"
    const val OSC_PREFERENCE_VALUE = "OSC_Keyboard"

    private val MODIFIER_KEY_CODES = setOf(
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT
    )

    @JvmStatic
    fun isModifierKey(keyCode: Int): Boolean {
        return MODIFIER_KEY_CODES.contains(keyCode)
    }

    @JvmStatic
    fun screenScale(units: Int, height: Int): Int {
        return (height.toFloat() / 72f * units.toFloat()).toInt()
    }

    @JvmStatic
    fun screenScaleSwitch(result: Int, height: Int): Int {
        return result * 72 / height
    }

    @JvmStatic
    fun createDiaitalPadButton(
        elementId: String,
        keyCodeLeft: Int,
        keyCodeRight: Int,
        keyCodeUp: Int,
        keyCodeDown: Int,
        controller: KeyBoardController,
        context: Context
    ): KeyboardDigitalPadButton {
        val button = KeyboardDigitalPadButton(controller, context, elementId)
        button.addDigitalPadListener { direction ->
            val leftEvent = KeyEvent(
                if (direction and KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_LEFT != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCodeLeft
            ).apply { source = 3 }
            controller.sendKeyEvent(leftEvent)

            val rightEvent = KeyEvent(
                if (direction and KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_RIGHT != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCodeRight
            ).apply { source = 3 }
            controller.sendKeyEvent(rightEvent)

            val upEvent = KeyEvent(
                if (direction and KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_UP != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCodeUp
            ).apply { source = 3 }
            controller.sendKeyEvent(upEvent)

            val downEvent = KeyEvent(
                if (direction and KeyboardDigitalPadButton.DIGITAL_PAD_DIRECTION_DOWN != 0) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCodeDown
            ).apply { source = 3 }
            controller.sendKeyEvent(downEvent)
        }
        return button
    }

    @JvmStatic
    fun createKeyBoardAnalogStickButton(
        controller: KeyBoardController,
        elementId: String,
        context: Context,
        keylist: IntArray
    ): KeyBoardAnalogStickButton {
        val analogStick = KeyBoardAnalogStickButton(controller, elementId, context, keylist)
        analogStick.setListener { code, isPress ->
            val keyEvent = KeyEvent(if (isPress) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
            keyEvent.source = 2
            controller.sendKeyEvent(keyEvent)
        }
        return analogStick
    }

    private fun createKeyBoardAnalogStickButton2(
        controller: KeyBoardController,
        elementId: String,
        context: Context,
        keylist: IntArray
    ): KeyBoardAnalogStickButtonFree {
        val analogStick = KeyBoardAnalogStickButtonFree(controller, elementId, context, keylist)
        analogStick.setListener { code, isPress ->
            val keyEvent = KeyEvent(if (isPress) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code)
            keyEvent.source = 2
            controller.sendKeyEvent(keyEvent)
        }
        return analogStick
    }

    @JvmStatic
    fun createDigitalButton(
        elementId: String,
        keyShort: Int,
        type: Int,
        layer: Int,
        text: String,
        icon: Int,
        sticky: Boolean,
        controller: KeyBoardController,
        context: Context
    ): KeyBoardDigitalButton {
        val button = KeyBoardDigitalButton(controller, elementId, layer, context)
        button.setText(text)
        button.setIcon(icon)

        if (elementId.startsWith("m_s_") || elementId.startsWith("key_s_")) {
            button.setEnableSwitchDown(true)
        }

        if (sticky) {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                override fun onClick() {
                    if (button.isSticky) {
                        button.isSticky = false
                        return
                    }
                    val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyShort).apply { source = type }
                    controller.sendKeyEvent(keyEvent)
                }

                override fun onLongClick() {
                    button.isSticky = true
                    controller.vibrate(-1)
                }

                override fun onRelease() {
                    if (button.isSticky) {
                        return
                    }
                    val keyEvent = KeyEvent(KeyEvent.ACTION_UP, keyShort).apply { source = type }
                    controller.sendKeyEvent(keyEvent)
                }
            })
        } else {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                override fun onClick() {
                    val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyShort).apply { source = type }
                    controller.sendKeyEvent(keyEvent)
                }

                override fun onLongClick() {}

                override fun onRelease() {
                    val keyEvent = KeyEvent(KeyEvent.ACTION_UP, keyShort).apply { source = type }
                    controller.sendKeyEvent(keyEvent)
                }
            })
        }

        return button
    }

    @JvmStatic
    fun createCustomButton(
        elementId: String,
        keys: ShortArray,
        layer: Int,
        text: String?,
        icon: Int,
        sticky: Boolean,
        controller: KeyBoardController,
        conn: NvConnection,
        context: Context
    ): KeyBoardDigitalButton {
        val button = KeyBoardDigitalButton(controller, elementId, layer, context)
        button.setText(text ?: "")
        button.setIcon(icon)

        if (sticky) {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                override fun onClick() {
                    if (button.isSticky) {
                        button.isSticky = false
                        return
                    }
                    var modifier: Byte = 0
                    for (key in keys) {
                        conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier, 0.toByte())
                        modifier = modifier or KeyboardTranslator.getModifier(key)
                    }
                }

                override fun onLongClick() {
                    button.isSticky = true
                    controller.vibrate(-1)
                }

                override fun onRelease() {
                    if (button.isSticky) {
                        return
                    }
                    var modifier: Byte = 0
                    for (i in keys.indices.reversed()) {
                        modifier = modifier and KeyboardTranslator.getModifier(keys[i]).inv()
                        conn.sendKeyboardInput(keys[i], KeyboardPacket.KEY_UP, modifier, 0.toByte())
                    }
                }
            })
        } else {
            button.addDigitalButtonListener(object : KeyBoardDigitalButton.DigitalButtonListener {
                private var modifier: Byte = 0

                override fun onClick() {
                    controller.vibrate(KeyEvent.ACTION_DOWN)
                    modifier = 0
                    for (key in keys) {
                        conn.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, modifier, 0.toByte())
                        modifier = modifier or KeyboardTranslator.getModifier(key)
                    }
                }

                override fun onLongClick() {}

                override fun onRelease() {
                    controller.vibrate(KeyEvent.ACTION_UP)
                    for (i in keys.indices.reversed()) {
                        val key = keys[i]
                        modifier = modifier and KeyboardTranslator.getModifier(key).inv()
                        conn.sendKeyboardInput(key, KeyboardPacket.KEY_UP, modifier, 0.toByte())
                    }
                }
            })
        }
        return button
    }

    @JvmStatic
    fun createDigitalTouchButton(
        elementId: String,
        keyShort: Int,
        type: Int,
        layer: Int,
        text: String,
        icon: Int,
        controller: KeyBoardController,
        context: Context
    ): KeyBoardTouchPadButton {
        val button = KeyBoardTouchPadButton(controller, elementId, layer, context)
        button.setText(text)
        button.setIcon(icon)
        button.addDigitalButtonListener(object : KeyBoardTouchPadButton.DigitalButtonListener {
            override fun onClick() {
                val code = if (keyShort == 9) 3 else 1
                val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, code).apply { source = type }
                controller.sendKeyEvent(keyEvent)
            }

            override fun onLongClick() {}

            override fun onMove(x: Int, y: Int) {
                controller.sendMouseMove(x, y)
            }

            override fun onRelease() {
                val code = if (keyShort == 9) 3 else 1
                val keyEvent = KeyEvent(KeyEvent.ACTION_UP, code).apply { source = type }
                controller.sendKeyEvent(keyEvent)
            }
        })
        return button
    }

    @JvmStatic
    fun createDefaultLayout(controller: KeyBoardController, context: Context, conn: NvConnection) {
        val screen = context.resources.displayMetrics
        val config = PreferenceConfiguration.readPreferences(context)
        val height = screen.heightPixels
        val rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9

        var BUTTON_SIZE = 10
        var w = screenScale(BUTTON_SIZE, height)
        val maxW = screen.widthPixels / 18

        if (w > maxW) {
            BUTTON_SIZE = screenScaleSwitch(maxW, height)
            w = screenScale(BUTTON_SIZE, height)
        }

        val result = try {
            context.assets.open("config/keyboard.json").use { isStream ->
                isStream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }

        if (TextUtils.isEmpty(result)) {
            return
        }

        try {
            val jsonObject = JSONObject(result)
            val jsonObject1 = jsonObject.getJSONObject("data")

            val keystrokeList = jsonObject1.getJSONArray("keystroke")
            val dpadList = jsonObject1.getJSONArray("dpad")
            val rockerList = jsonObject1.getJSONArray("rocker")
            val mouseList = jsonObject1.getJSONArray("mouse")

            for (i in 0 until dpadList.length()) {
                val obj = dpadList.getJSONObject(i)
                val code = obj.optString("elementId")
                val keyCodeLeft = obj.optInt("leftCode")
                val keyCodeRight = obj.optInt("rightCode")
                val keyCodeUp = obj.optInt("upCode")
                val keyCodeDown = obj.optInt("downCode")
                controller.addElement(
                    createDiaitalPadButton(code, keyCodeLeft, keyCodeRight, keyCodeUp, keyCodeDown, controller, context),
                    screenScale(92, height) + rightDisplacement,
                    screenScale(41, height),
                    (w * 2.5).toInt(),
                    (w * 2.5).toInt()
                )
            }

            for (i in 0 until rockerList.length()) {
                val obj = rockerList.getJSONObject(i)
                val code = obj.optString("elementId")
                val keyCodeLeft = obj.optInt("leftCode")
                val keyCodeRight = obj.optInt("rightCode")
                val keyCodeUp = obj.optInt("upCode")
                val keyCodeDown = obj.optInt("downCode")
                val keyCodeMiddle = obj.optInt("middleCode")
                val keys = intArrayOf(keyCodeUp, keyCodeDown, keyCodeLeft, keyCodeRight, keyCodeMiddle)

                val stickElement = if (config.enableNewAnalogStick) {
                    createKeyBoardAnalogStickButton2(controller, code, context, keys)
                } else {
                    createKeyBoardAnalogStickButton(controller, code, context, keys)
                }
                controller.addElement(
                    stickElement,
                    screenScale(4, height),
                    screenScale(41, height),
                    (w * 2.5).toInt(),
                    (w * 2.5).toInt()
                )
            }

            for (i in 0 until mouseList.length()) {
                val obj = mouseList.getJSONObject(i)
                obj.put("type", 1)
                keystrokeList.put(obj)
            }

            val buttonSum = 14.0
            var i = 0
            while (i < keystrokeList.length()) {
                val obj = keystrokeList.getJSONObject(i)
                val name = obj.optString("name")
                val type = obj.optInt("type")
                val code = obj.optInt("code")
                val switchButton = obj.optInt("switchButton")

                var elementId = if (type == 0) "key_$code" else "m_$code"
                if (switchButton == 1) {
                    elementId = if (type == 0) "key_s_$code" else "m_s_$code"
                }

                val lastIndex = (i / buttonSum).toInt()
                val x = screenScale(1 + (i % buttonSum).toInt() * BUTTON_SIZE, height)
                val y = screenScale(BUTTON_SIZE + lastIndex * BUTTON_SIZE, height)

                if (TextUtils.equals("m_9", elementId) || TextUtils.equals("m_10", elementId) || TextUtils.equals("m_11", elementId)) {
                    controller.addElement(
                        createDigitalTouchButton(elementId, code, type, 1, name, -1, controller, context),
                        x, y, w, w
                    )
                } else {
                    controller.addElement(
                        createDigitalButton(
                            elementId, code, type, 1, name, -1,
                            config.stickyModifierKey && isModifierKey(code),
                            controller, context
                        ),
                        x, y, w, w
                    )
                }
                VibemisLog.info("x:$x,y:$y,W&H:$w,${screenScale(BUTTON_SIZE, height)}")
                i++
            }

            val preferences = context.getSharedPreferences(GameMenu.PREF_NAME, Activity.MODE_PRIVATE)
            val value = preferences.getString(GameMenu.KEY_NAME, "") ?: ""

            if (!TextUtils.isEmpty(value)) {
                try {
                    val shortcutFile = KeyConfigHelper.parseShortcutFile(value)
                    val data = shortcutFile?.data
                    if (!data.isNullOrEmpty()) {
                        for (idx in data.indices) {
                            val sc = data[idx]
                            val id = if (sc.id.isNullOrEmpty()) idx.toString() else sc.id
                            val name = sc.name
                            val keys = sc.keys
                            val vkKeyCodes = ShortArray(keys.size)

                            for (j in keys.indices) {
                                val code = keys[j]
                                val keycode = when {
                                    code.startsWith("0x") -> code.substring(2).toInt(16)
                                    code.startsWith("VK_") -> {
                                        val field: Field = KeyMapper::class.java.getDeclaredField(code)
                                        field.getInt(null)
                                    }
                                    else -> throw IllegalArgumentException("Unknown key code: $code")
                                }
                                vkKeyCodes[j] = keycode.toShort()
                            }

                            val sticky = sc.sticky
                            val lastIndex = ((idx + i) / buttonSum).toInt()
                            val x = screenScale((1 + ((idx + i) % buttonSum) * BUTTON_SIZE).toInt(), height)
                            val y = screenScale(BUTTON_SIZE + lastIndex * BUTTON_SIZE, height)

                            controller.addElement(
                                createCustomButton(
                                    "custom_$id", vkKeyCodes, 1,
                                    name, -1, sticky, controller, conn, context
                                ),
                                x, y, w, w
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    (context as? Game)?.showTransientOverlayMessage(context.getString(R.string.wrong_import_format))
                }
            }
        } catch (e: JSONException) {
            throw RuntimeException(e)
        }

        controller.setOpacity(config.oscOpacity)
    }

    @JvmStatic
    fun saveProfile(controller: KeyBoardController, context: Context) {
        val name = PreferenceConfiguration.getDefaultSharedPreferences(context)
            .getString(OSC_PREFERENCE, OSC_PREFERENCE_VALUE) ?: OSC_PREFERENCE_VALUE
        val prefEditor = context.getSharedPreferences(name, Activity.MODE_PRIVATE).edit()

        for (element in controller.getElements()) {
            val prefKey = "" + element.elementId
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString())
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
        prefEditor.apply()
    }

    @JvmStatic
    fun loadFromPreferences(controller: KeyBoardController, context: Context) {
        val name = PreferenceConfiguration.getDefaultSharedPreferences(context)
            .getString(OSC_PREFERENCE, OSC_PREFERENCE_VALUE) ?: OSC_PREFERENCE_VALUE
        val pref = context.getSharedPreferences(name, Activity.MODE_PRIVATE)

        for (element in controller.getElements()) {
            val prefKey = "" + element.elementId
            val jsonConfig = pref.getString(prefKey, null)
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(JSONObject(jsonConfig))
                } catch (e: JSONException) {
                    e.printStackTrace()
                    pref.edit().remove(prefKey).apply()
                }
            }
        }
    }
}
