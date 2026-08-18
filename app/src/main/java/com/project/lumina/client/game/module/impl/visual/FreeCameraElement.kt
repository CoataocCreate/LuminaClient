package com.project.lumina.client.game.module.impl.visual

import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.GameType
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import com.project.lumina.client.util.AssetManager
import kotlin.math.cos
import kotlin.math.sin

class FreeCameraElement(iconResId: Int = AssetManager.getAsset("ic_movie_open_black_24dp")) : Element(
    name = "FreeCam",
    category = CheatCategory.Visual,
    iconResId,
    displayNameResId = AssetManager.getString("module_free_camera_display_name")
) {

    // Configurable values
    private var flySpeed by floatValue("Speed", 0.5f, 0.1f..3.0f)
    private var verticalSpeed by floatValue("Vertical Speed", 0.5f, 0.1f..3.0f)

    // State tracking
    private var originalPosition: Vector3f? = null
    private var originalRotation: Vector3f? = null
    private var canFly = false

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onEnabled() {
        super.onEnabled()
        try {
            if (!isSessionCreated) return

            // Store original position and rotation so we can return later
            originalPosition = Vector3f.from(
                session.localPlayer.posX,
                session.localPlayer.posY,
                session.localPlayer.posZ
            )
            originalRotation = Vector3f.from(
                session.localPlayer.rotationPitch,
                session.localPlayer.rotationYaw,
                session.localPlayer.rotationYawHead
            )

            // Switch client to spectator mode for noclip + flight visuals
            session.clientBound(SetPlayerGameTypePacket().apply {
                gamemode = GameType.SPECTATOR.ordinal
            })

            // Grant flight + noclip abilities
            sendAbilitiesPacket(fly = true, noclip = true)
            canFly = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDisabled() {
        super.onDisabled()

        if (!isSessionCreated) return

        // Restore abilities to normal (no fly, no noclip)
        sendAbilitiesPacket(fly = false, noclip = false)
        canFly = false

        // Switch back to survival mode
        session.clientBound(SetPlayerGameTypePacket().apply {
            gamemode = GameType.SURVIVAL.ordinal
        })

        // Teleport the client camera back to original position using MovePlayerPacket
        val savedPos = originalPosition
        val savedRot = originalRotation
        if (savedPos != null && savedRot != null) {
            session.clientBound(MovePlayerPacket().apply {
                runtimeEntityId = session.localPlayer.runtimeEntityId
                position = savedPos
                rotation = savedRot
                mode = MovePlayerPacket.Mode.TELEPORT
                onGround = true
                tick = session.localPlayer.tickExists
            })
        }

        originalPosition = null
        originalRotation = null
    }

    // ── Packet Handling ─────────────────────────────────────────────────

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isSessionCreated) return
        val packet = interceptablePacket.packet

        // Block the server from revoking our flight ability
        if (isEnabled && packet is RequestAbilityPacket && packet.ability == Ability.FLYING) {
            interceptablePacket.intercept()
            return
        }

        // Block the server's ability updates while freecam is active
        // so it can't remove our fly/noclip
        if (isEnabled && packet is UpdateAbilitiesPacket) {
            interceptablePacket.intercept()
            return
        }

        // Block server-side MovePlayerPacket corrections while in freecam
        // to prevent the camera snapping back to server position
        if (isEnabled && packet is MovePlayerPacket &&
            packet.runtimeEntityId == session.localPlayer.runtimeEntityId
        ) {
            interceptablePacket.intercept()
            return
        }

        if (packet is PlayerAuthInputPacket) {
            handleAuthInput(packet, interceptablePacket)
        }
    }

    private fun handleAuthInput(packet: PlayerAuthInputPacket, interceptablePacket: InterceptablePacket) {
        // Re-enable flight if the module is on but canFly got reset
        if (isEnabled && !canFly) {
            sendAbilitiesPacket(fly = true, noclip = true)
            canFly = true
        }

        // Restore normal abilities when module is turned off
        if (!isEnabled && canFly) {
            sendAbilitiesPacket(fly = false, noclip = false)
            canFly = false
            return
        }

        if (!isEnabled) return

        // ── Directional camera movement ─────────────────────────────

        // Convert yaw (degrees) to radians for sin/cos
        val yawRad = Math.toRadians(packet.rotation.y.toDouble())
        val pitchRad = Math.toRadians(packet.rotation.x.toDouble())

        // Horizontal movement from joystick input (motion.y = forward/back, motion.x = strafe)
        val inputForward = packet.motion.y  // W/S or stick Y
        val inputStrafe = packet.motion.x   // A/D or stick X

        // Calculate look-direction-based horizontal movement
        var moveX = 0.0
        var moveZ = 0.0

        if (inputForward != 0f || inputStrafe != 0f) {
            // Forward/back along look direction (projected onto horizontal plane)
            moveX += (-sin(yawRad) * inputForward + cos(yawRad) * inputStrafe) * flySpeed
            moveZ += (cos(yawRad) * inputForward + sin(yawRad) * inputStrafe) * flySpeed
        }

        // Vertical movement from jump/sneak
        var moveY = 0.0
        if (packet.inputData.contains(PlayerAuthInputData.JUMPING) ||
            packet.inputData.contains(PlayerAuthInputData.WANT_UP)
        ) {
            moveY = verticalSpeed.toDouble()
        } else if (packet.inputData.contains(PlayerAuthInputData.SNEAKING) ||
            packet.inputData.contains(PlayerAuthInputData.WANT_DOWN)
        ) {
            moveY = -verticalSpeed.toDouble()
        }

        // Apply pitch-based vertical when moving forward (optional: look-fly)
        if (inputForward > 0f) {
            moveY += -sin(pitchRad) * inputForward * flySpeed
        }

        // Calculate the camera's new position
        val currentPos = packet.position
        val newPos = Vector3f.from(
            currentPos.x + moveX.toFloat(),
            currentPos.y + moveY.toFloat(),
            currentPos.z + moveZ.toFloat()
        )

        // ── Spoof position to server ────────────────────────────────
        // Send the ORIGINAL position to the server so it thinks
        // the player hasn't moved. This prevents server-side kicks.
        val spoofedPos = originalPosition ?: currentPos
        val spoofedPacket = PlayerAuthInputPacket().apply {
            position = spoofedPos
            rotation = packet.rotation
            motion = packet.motion
            delta = Vector3f.ZERO
            inputData.addAll(packet.inputData)
            // Remove fly/noclip signals from server-bound packet
            inputData.remove(PlayerAuthInputData.START_FLYING)
            inputData.remove(PlayerAuthInputData.JUMPING)
            inputData.remove(PlayerAuthInputData.WANT_UP)
            inputData.remove(PlayerAuthInputData.WANT_DOWN)
            tick = packet.tick
        }
        session.serverBound(spoofedPacket)

        // ── Move the client camera to the new position ──────────────
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            position = newPos
            rotation = packet.rotation
            mode = MovePlayerPacket.Mode.NORMAL
            onGround = false
            tick = packet.tick
        })

        // Intercept the original packet so it doesn't reach the server
        interceptablePacket.intercept()
    }

    // ── Ability Packet Builder ───────────────────────────────────────────

    /**
     * Builds and sends an UpdateAbilitiesPacket dynamically.
     * Built fresh each time so flySpeed changes take effect immediately.
     */
    private fun sendAbilitiesPacket(fly: Boolean, noclip: Boolean) {
        if (!isSessionCreated) return

        val abilitiesPacket = UpdateAbilitiesPacket().apply {
            uniqueEntityId = session.localPlayer.uniqueEntityId
            playerPermission = PlayerPermission.OPERATOR
            commandPermission = CommandPermission.OWNER
            abilityLayers.add(AbilityLayer().apply {
                layerType = AbilityLayer.Type.BASE
                abilitiesSet.addAll(Ability.entries.toTypedArray())

                val granted = mutableListOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.OPERATOR_COMMANDS,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
                if (fly) {
                    granted.add(Ability.MAY_FLY)
                    granted.add(Ability.FLYING)
                }
                if (noclip) {
                    granted.add(Ability.NO_CLIP)
                }
                abilityValues.addAll(granted)

                walkSpeed = 0.1f
                flySpeed = this@FreeCameraElement.flySpeed
            })
        }
        session.clientBound(abilitiesPacket)
    }
}