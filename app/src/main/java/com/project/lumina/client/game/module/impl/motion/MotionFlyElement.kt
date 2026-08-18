package com.project.lumina.client.game.module.impl.motion

import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.Element
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.module.api.setting.stringValue
import com.project.lumina.client.util.AssetManager
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MotionFlyElement(iconResId: Int = AssetManager.getAsset("ic_flash_black_24dp")) : Element(
    name = "MotionFly",
    category = CheatCategory.Motion,
    iconResId,
    displayNameResId = AssetManager.getString("module_motion_fly_display_name")
) {

    private val profile by stringValue(
        this,
        "Profile",
        "Stealth",
        listOf("Stealth", "Balanced", "Boost")
    )
    private val hoverMode by stringValue(
        this,
        "Hover",
        "Glide",
        listOf("Glide", "Hover", "Freeze")
    )
    private val spoofMode by stringValue(
        this,
        "Spoof",
        "Air",
        listOf("Air", "Ground", "Mixed")
    )

    private val horizontalSpeed by floatValue("Horizontal Speed", 0.42f, 0.1f..8.0f)
    private val verticalSpeed by floatValue("Vertical Speed", 0.32f, 0.05f..4.0f)
    private val glideSpeed by floatValue("Glide Speed", 0.032f, 0.0f..0.4f)
    private val acceleration by floatValue("Acceleration", 0.28f, 0.05f..1.0f)
    private val friction by floatValue("Friction", 0.72f, 0.4f..0.95f)
    private val noise by floatValue("Noise", 0.012f, 0.0f..0.08f)
    private val sprintBoost by floatValue("Sprint Boost", 1.18f, 1.0f..2.0f)
    private val motionInterval by floatValue("Delay", 0.0f, 0.0f..100.0f)

    private val syncDelta by boolValue("Sync Delta", true)
    private val hideFlyFlags by boolValue("Hide Fly Flags", true)
    private val fakeAbilities by boolValue("Fake Abilities", false)
    private val stopOnDisable by boolValue("Stop On Disable", true)

    private var lastMotionTime = 0L
    private var canFly = false
    private var currentX = 0f
    private var currentY = 0f
    private var currentZ = 0f
    private var hoverPhase = 0f
    private var mixedSpoofTicks = 0
    private var mixedOnGround = false

    private val flyPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.MEMBER
        commandPermission = CommandPermission.ANY
        uniqueEntityId = -1
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.MAY_FLY,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed = 0.05f
        })
    }

    private val resetPacket = UpdateAbilitiesPacket().apply {
        playerPermission = PlayerPermission.MEMBER
        commandPermission = CommandPermission.ANY
        uniqueEntityId = -1
        abilityLayers.add(AbilityLayer().apply {
            layerType = AbilityLayer.Type.BASE
            abilitiesSet.addAll(Ability.entries.toTypedArray())
            abilityValues.addAll(
                arrayOf(
                    Ability.BUILD,
                    Ability.MINE,
                    Ability.DOORS_AND_SWITCHES,
                    Ability.OPEN_CONTAINERS,
                    Ability.ATTACK_PLAYERS,
                    Ability.ATTACK_MOBS,
                    Ability.FLY_SPEED,
                    Ability.WALK_SPEED
                )
            )
            walkSpeed = 0.1f
            flySpeed = 0.05f
        })
    }

    override fun onEnabled() {
        super.onEnabled()
        resetMotion()
    }

    override fun onDisabled() {
        if (isSessionCreated && stopOnDisable) {
            session.clientBound(SetEntityMotionPacket().apply {
                runtimeEntityId = session.localPlayer.runtimeEntityId
                motion = Vector3f.from(0f, 0f, 0f)
            })
        }
        applyFlyAbilities(false)
        resetMotion()
        super.onDisabled()
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet

        if (packet is RequestAbilityPacket && isEnabled && hideFlyFlags) {
            if (packet.ability == Ability.FLYING || packet.ability == Ability.MAY_FLY) {
                interceptablePacket.intercept()
                return
            }
        }

        if (packet !is PlayerAuthInputPacket || !isEnabled) return

        if (hideFlyFlags) {
            packet.inputData.remove(PlayerAuthInputData.START_FLYING)
            packet.inputData.remove(PlayerAuthInputData.STOP_FLYING)
        }

        applyFlyAbilities(fakeAbilities)
        spoofCollision(packet)

        val now = System.currentTimeMillis()
        if (motionInterval > 0f && now - lastMotionTime < motionInterval) return

        val speedScale = when (profile) {
            "Stealth" -> 0.55f
            "Boost" -> 1.65f
            else -> 1.0f
        }
        val noiseScale = when (profile) {
            "Stealth" -> 1.35f
            "Boost" -> 0.45f
            else -> 1.0f
        }

        val sprinting = packet.inputData.contains(PlayerAuthInputData.SPRINTING) ||
            packet.inputData.contains(PlayerAuthInputData.SPRINT_DOWN)
        val horizCap = horizontalSpeed * speedScale * if (sprinting) sprintBoost else 1f
        val vertCap = verticalSpeed * speedScale

        val inputX = packet.motion.x
        val inputZ = packet.motion.y
        val yaw = Math.toRadians(packet.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        val targetX = (inputX * cosYaw - inputZ * sinYaw) * horizCap
        val targetZ = (inputZ * cosYaw + inputX * sinYaw) * horizCap
        val moving = inputX != 0f || inputZ != 0f

        val accel = acceleration.coerceIn(0.05f, 1f)
        if (moving) {
            currentX += (targetX - currentX) * accel
            currentZ += (targetZ - currentZ) * accel
        } else {
            currentX *= friction
            currentZ *= friction
            if (currentX * currentX + currentZ * currentZ < 0.0004f) {
                currentX = 0f
                currentZ = 0f
            }
        }

        val wantUp = packet.inputData.contains(PlayerAuthInputData.WANT_UP) ||
            packet.inputData.contains(PlayerAuthInputData.JUMPING) ||
            packet.inputData.contains(PlayerAuthInputData.JUMP_DOWN)
        val wantDown = packet.inputData.contains(PlayerAuthInputData.WANT_DOWN) ||
            packet.inputData.contains(PlayerAuthInputData.SNEAKING) ||
            packet.inputData.contains(PlayerAuthInputData.SNEAK_DOWN)

        val targetY = when {
            wantUp && !wantDown -> vertCap
            wantDown && !wantUp -> -vertCap
            else -> hoverTarget()
        }
        currentY += (targetY - currentY) * accel

        hoverPhase += 0.17f + Random.nextFloat() * 0.05f
        val jitter = noise * noiseScale
        val motionX = currentX + signedNoise(jitter)
        val motionY = currentY + signedNoise(jitter * 0.55f) + kotlin.math.sin(hoverPhase) * jitter * 0.35f
        val motionZ = currentZ + signedNoise(jitter)

        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            motion = Vector3f.from(motionX, motionY, motionZ)
        })

        if (syncDelta) {
            val previous = packet.delta
            packet.delta = if (previous != null) {
                blendDelta(previous, motionX, motionY, motionZ)
            } else {
                Vector3f.from(motionX, motionY, motionZ)
            }
        }

        lastMotionTime = now
    }

    private fun hoverTarget(): Float {
        return when (hoverMode) {
            "Freeze" -> signedNoise(noise * 0.15f)
            "Hover" -> -0.0035f + signedNoise(0.004f)
            else -> -glideSpeed.coerceAtLeast(0.008f)
        }
    }

    private fun spoofCollision(packet: PlayerAuthInputPacket) {
        when (spoofMode) {
            "Ground" -> {
                packet.inputData.add(PlayerAuthInputData.VERTICAL_COLLISION)
                packet.inputData.remove(PlayerAuthInputData.JUMPING)
                packet.inputData.remove(PlayerAuthInputData.START_JUMPING)
            }
            "Mixed" -> {
                mixedSpoofTicks++
                if (mixedSpoofTicks >= Random.nextInt(8, 18)) {
                    mixedSpoofTicks = 0
                    mixedOnGround = !mixedOnGround
                }
                if (mixedOnGround) {
                    packet.inputData.add(PlayerAuthInputData.VERTICAL_COLLISION)
                } else {
                    packet.inputData.remove(PlayerAuthInputData.VERTICAL_COLLISION)
                }
            }
            else -> {
                packet.inputData.remove(PlayerAuthInputData.VERTICAL_COLLISION)
            }
        }
    }

    private fun applyFlyAbilities(enabled: Boolean) {
        if (!isSessionCreated || canFly == enabled) return
        val id = session.localPlayer.uniqueEntityId
        flyPacket.uniqueEntityId = id
        resetPacket.uniqueEntityId = id
        session.clientBound(if (enabled) flyPacket else resetPacket)
        canFly = enabled
    }

    private fun blendDelta(previous: Vector3f, x: Float, y: Float, z: Float): Vector3f {
        val px = previous.x
        val py = previous.y
        val pz = previous.z
        val prevLen = sqrt(px * px + py * py + pz * pz)
        val nextLen = sqrt(x * x + y * y + z * z)
        if (prevLen < 0.001f || nextLen < 0.001f) {
            return Vector3f.from(x, y, z)
        }
        val mix = 0.65f
        return Vector3f.from(
            px * (1f - mix) + x * mix,
            py * (1f - mix) + y * mix,
            pz * (1f - mix) + z * mix
        )
    }

    private fun signedNoise(amplitude: Float): Float {
        if (amplitude <= 0f) return 0f
        return (Random.nextFloat() * 2f - 1f) * amplitude
    }

    private fun resetMotion() {
        lastMotionTime = 0L
        currentX = 0f
        currentY = 0f
        currentZ = 0f
        hoverPhase = 0f
        mixedSpoofTicks = 0
        mixedOnGround = false
    }
}
