package com.izzy2lost.nin64

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsConfigTest {
    @Test
    fun defaultsIncludeEveryConfigurableTarget() {
        val touchTargets = TouchLayout.default().controls.map { it.target }.toSet()
        val gamepadMapping = GamepadMapping.default()

        enumValues<N64Target>().forEach { target ->
            assertTrue("missing touch control for $target", target in touchTargets)
            if (target != N64Target.ANALOG_STICK) {
                assertTrue("missing gamepad binding for $target", gamepadMapping.targetBindings[target].orEmpty().isNotEmpty())
            }
        }
        assertTrue(gamepadMapping.analogXAxes.isNotEmpty())
        assertTrue(gamepadMapping.analogYAxes.isNotEmpty())
    }

    @Test
    fun touchLayoutRoundTripsThroughJson() {
        val original = TouchLayout.default().copy(
            controls = TouchLayout.default().controls.map {
                if (it.id == "a_button") {
                    it.copy(target = N64Target.B_BUTTON, x = 0.61f, y = 0.62f, size = 0.14f, opacity = 0.74f, visible = false)
                } else {
                    it
                }
            }
        )

        val parsed = TouchLayout.fromJson(original.toJson())

        assertEquals(original, parsed)
    }

    @Test
    fun gamepadMappingRoundTripsThroughJson() {
        val original = GamepadMapping.default()
            .withAnalogAxes(11, 14)
            .withBinding(N64Target.A_BUTTON, GamepadBinding.key(99))
            .withBinding(N64Target.C_RIGHT, GamepadBinding.axis(22, -1))

        val parsed = GamepadMapping.fromJson(original.toJson())

        assertEquals(original, parsed)
    }

    @Test
    fun perGameJsonOverridesGlobalJson() {
        val globalTouch = TouchLayout.default().copy(
            controls = TouchLayout.default().controls.map {
                if (it.id == "start") it.copy(x = 0.20f) else it
            }
        )
        val perGameTouch = TouchLayout.default().copy(
            controls = TouchLayout.default().controls.map {
                if (it.id == "start") it.copy(x = 0.80f) else it
            }
        )
        val globalGamepad = GamepadMapping.default().withBinding(N64Target.A_BUTTON, GamepadBinding.key(1))
        val perGameGamepad = GamepadMapping.default().withBinding(N64Target.A_BUTTON, GamepadBinding.key(2))

        val resolved = ControlsRepository.resolve(
            globalTouchJson = globalTouch.toJson(),
            globalGamepadJson = globalGamepad.toJson(),
            perGameTouchJson = perGameTouch.toJson(),
            perGameGamepadJson = perGameGamepad.toJson(),
        )

        assertEquals(0.80f, resolved.touchLayout.controls.first { it.id == "start" }.x, 0.001f)
        assertEquals(listOf(GamepadBinding.key(2)), resolved.gamepadMapping.targetBindings[N64Target.A_BUTTON])
        assertNotEquals(globalTouch, resolved.touchLayout)
        assertNotEquals(globalGamepad, resolved.gamepadMapping)
    }

    @Test
    fun nullPerGameJsonFallsBackToGlobalJson() {
        val globalTouch = TouchLayout.default().copy(
            controls = TouchLayout.default().controls.map {
                if (it.id == "start") it.copy(y = 0.25f) else it
            }
        )
        val globalGamepad = GamepadMapping.default().withBinding(N64Target.B_BUTTON, GamepadBinding.key(42))

        val resolved = ControlsRepository.resolve(
            globalTouchJson = globalTouch.toJson(),
            globalGamepadJson = globalGamepad.toJson(),
            perGameTouchJson = null,
            perGameGamepadJson = null,
        )

        assertEquals(0.25f, resolved.touchLayout.controls.first { it.id == "start" }.y, 0.001f)
        assertEquals(listOf(GamepadBinding.key(42)), resolved.gamepadMapping.targetBindings[N64Target.B_BUTTON])
    }

    @Test
    fun perGameKeysMatchPlannedStorageShape() {
        assertEquals("per_game.mario.touch_layout", ControlsRepository.perGameTouchLayoutKey("mario"))
        assertEquals("per_game.mario.gamepad_mapping", ControlsRepository.perGameGamepadMappingKey("mario"))
    }

    @Test
    fun cheatCrcNormalizationMatchesMupenDatabaseShape() {
        assertEquals("80F41131-384645F6", CheatDatabase.normalizeCrc("80F41131 384645F6"))
        assertEquals("80F41131-384645F6", CheatDatabase.normalizeCrc("80F41131-384645F6-C:4A"))
    }

    @Test
    fun cheatCodeLinesDropOptionLabels() {
        assertEquals("8113D058 0000", CheatDatabase.normalizeCodeLine("8113D058 0000"))
        assertEquals(
            "8013CD0E 0001",
            CheatDatabase.normalizeCodeLine("8013CD0E ???? 0001:\"1 Lap\",0002:\"2 Laps\""),
        )
    }

    @Test
    fun selectableCheatOptionsAreParsedAndResolved() {
        val codeLine = CheatDatabase.parseCodeLine("8013CD0E ???? 0001:\"1 Lap\",0002:\"2 Laps\"")

        assertEquals(listOf("1 Lap", "2 Laps"), codeLine?.options?.map { it.label })
        assertEquals("8013CD0E 0002", codeLine?.resolvedText("0002"))
    }
}
