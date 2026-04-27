/*
 * Copyright (c) 2025 senseiwells
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package net.casual.arcade.replay.viewer

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSets
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSets
import com.mojang.authlib.GameProfile
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import kotlinx.coroutines.*
import net.casual.arcade.events.GlobalEventHandler
import net.casual.arcade.events.ListenerRegistry.Companion.register
import net.casual.arcade.events.server.player.PlayerServerboundPacketEvent
import net.casual.arcade.host.GlobalPackHost
import net.casual.arcade.replay.ducks.PackTracker
import net.casual.arcade.replay.io.reader.ReplayReader
import net.casual.arcade.replay.mixins.viewer.ClientboundMoveEntityPacketAccessor
import net.casual.arcade.replay.mixins.viewer.EntityInvoker
import net.casual.arcade.replay.recorder.rejoin.RejoinedReplayPlayer
import net.casual.arcade.replay.util.ReplayMarker
import net.casual.arcade.replay.viewer.ReplayViewerUtils.getViewingReplay
import net.casual.arcade.replay.viewer.ReplayViewerUtils.sendReplayPacket
import net.casual.arcade.replay.viewer.ReplayViewerUtils.startViewingReplay
import net.casual.arcade.replay.viewer.ReplayViewerUtils.stopViewingReplay
import net.casual.arcade.utils.ArcadeUtils
import net.casual.arcade.utils.DateTimeUtils.formatHHMMSS
import net.casual.arcade.utils.component.*
import net.casual.arcade.utils.player.server
import net.minecraft.client.Minecraft
import net.minecraft.core.UUIDUtil
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.network.protocol.game.*
import net.minecraft.network.protocol.game.ClientboundGameEventPacket.CHANGE_GAME_MODE
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Abilities
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import java.io.InputStream
import java.util.*
import java.util.function.Supplier
import kotlin.io.path.nameWithoutExtension
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public class ReplayViewer internal constructor(
    supplier: (ReplayViewer) -> ReplayReader,
    public val connection: ServerGamePacketListenerImpl
) {
    private val reader = supplier.invoke(this)
    private val markers by lazy { this.reader.readMarkers() }

    private val packs = ReplayViewerPackProvider(this)

    private var started = false
    private var teleported = false

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val coroutineContext: ExecutorCoroutineDispatcher = newSingleThreadContext("replay-viewer")
    private val coroutineScope = CoroutineScope(this.coroutineContext + Job())

    private var tickSpeed = 20.0F
    private var tickFrozen = false

    private val chunks = LongSets.synchronize(LongOpenHashSet())
    private val entities = IntSets.synchronize(IntOpenHashSet())
    private val players = Collections.synchronizedList(ArrayList<UUID>())
    private val objectives = Collections.synchronizedCollection(ArrayList<String>())
    private val playerEntityIds = Object2IntOpenHashMap<UUID>().also { it.defaultReturnValue(-1) }
    private val playerProfiles = HashMap<UUID, GameProfile>()
    private val entityTransforms = Int2ObjectOpenHashMap<EntityTransform>()
    private var spectatingEntityId: Int = -1
    private var seekingSpectateId: Int = -1

    public data class EntityTransform(val position: Vec3, val yRot: Float, val xRot: Float)

    private val bossbar = ServerBossEvent(UUID.randomUUID(), Component.empty(), BossBarColor.BLUE, BossBarOverlay.PROGRESS)

    private val previousPacks = ArrayList<ClientboundResourcePackPushPacket>()

    private var lastSentProgress = Duration.INFINITE
    public var progress: Duration = Duration.ZERO
        private set
    private var target = Duration.ZERO
    private var seekingTo: Duration? = null

    private var position = Vec3.ZERO

    private val entityAddCache = Int2ObjectOpenHashMap<Packet<*>>()
    private val ringBuffer = ArrayDeque<StateSnapshot>()
    private var nextRingTime = Duration.ZERO

    private data class StateSnapshot(
        val timestamp: Duration,
        val entities: IntOpenHashSet,
        val entityTransforms: Int2ObjectOpenHashMap<EntityTransform>,
        val entityAddPackets: Int2ObjectOpenHashMap<Packet<*>>,
        val players: ArrayList<UUID>,
        val playerEntityIds: Object2IntOpenHashMap<UUID>,
        val playerProfiles: HashMap<UUID, GameProfile>,
        val spectatingEntityId: Int,
        val position: Vec3
    )

    public var useItemHandler: ((ReplayViewer) -> Unit)? = null
    public var attackHandler: ((ReplayViewer) -> Unit)? = null
    public var onReadyHandler: ((ReplayViewer) -> Unit)? = null
    public var onStopHandler: ((ReplayViewer) -> Unit)? = null
    public var onSpectateChangeHandler: ((ReplayViewer) -> Unit)? = null
    public var viewerGameMode: GameType = GameType.SPECTATOR

    public fun sendToViewer(packet: Packet<*>) {
        this.send(packet)
    }

    public val gameProtocol: ProtocolInfo<ClientGamePacketListener> = GameProtocols.CLIENTBOUND_TEMPLATE.bind(
        RegistryFriendlyByteBuf.decorator(this.server.registryAccess())
    )

    public val server: MinecraftServer
        get() = this.player.server

    public val player: ServerPlayer
        get() = this.connection.player

    public var speedMultiplier: Float = 1.0F
        private set
    public var paused: Boolean = false
        private set

    public fun start() {
        if (this.started) {
            return
        }
        if (this.connection.getViewingReplay() != null) {
            ArcadeUtils.logger.error("Player ${this.player.scoreboardName} tried watching 2 replays at once?!")
            return
        }

        GlobalPackHost.add(this.packs)

        this.started = true
        this.setForReplayView()

        this.restart()
    }

    public fun stop() {
        this.onStopHandler?.invoke(this)
        this.close()

        this.removeReplayState()
        this.addBackToServer()
    }

    public fun close() {
        GlobalPackHost.remove(this.packs)

        this.coroutineScope.coroutineContext.cancelChildren()
        this.coroutineScope.launch {
            reader.close()
        }
        this.coroutineContext.close()
        this.connection.stopViewingReplay()

        ReplayViewers.remove(this.player.uuid)
    }

    public fun getProgressMillis(): Long = this.progress.inWholeMilliseconds

    public fun jumpToMillis(millis: Long): Boolean = this.jumpTo(millis.milliseconds)

    public fun jumpTo(timestamp: Duration): Boolean {
        if (timestamp.isNegative() || timestamp > this.reader.duration) {
            return false
        }

        this.coroutineScope.launch {
            val backward = timestamp < progress
            val ringSnap = if (backward) findRingSnapshot(timestamp) else null
            if (ringSnap != null) {
                ringSeek(ringSnap)
                target = ringSnap.timestamp
            } else if (reader.jumpTo(timestamp) || progress > timestamp) {
                showTargetProgress(timestamp)
                seekRestart()
                target = timestamp
            } else {
                target = timestamp
            }
        }
        return true
    }

    private fun findRingSnapshot(target: Duration): StateSnapshot? {
        var best: StateSnapshot? = null
        for (snap in this.ringBuffer) {
            if (snap.timestamp <= target) best = snap else break
        }
        return best
    }

    private fun maybePushRingSnapshot(time: Duration) {
        if (time < this.nextRingTime) return
        val snap = StateSnapshot(
            timestamp = time,
            entities = synchronized(this.entities) { IntOpenHashSet(this.entities) },
            entityTransforms = synchronized(this.entityTransforms) { Int2ObjectOpenHashMap(this.entityTransforms) },
            entityAddPackets = Int2ObjectOpenHashMap(this.entityAddCache),
            players = synchronized(this.players) { ArrayList(this.players) },
            playerEntityIds = synchronized(this.playerEntityIds) { Object2IntOpenHashMap(this.playerEntityIds) },
            playerProfiles = synchronized(this.playerProfiles) { HashMap(this.playerProfiles) },
            spectatingEntityId = this.spectatingEntityId,
            position = this.position
        )
        this.ringBuffer.addLast(snap)
        this.nextRingTime = time + RING_CADENCE
        val oldest = time - RING_WINDOW
        while (this.ringBuffer.isNotEmpty() && this.ringBuffer.first().timestamp < oldest) {
            this.ringBuffer.removeFirst()
        }
    }

    private fun ringSeek(snap: StateSnapshot) {
        this.applyRingSnapshotDiff(snap)
        this.showTargetProgress(snap.timestamp)
        this.reader.jumpTo(snap.timestamp)
        this.launchStream()
    }

    private fun applyRingSnapshotDiff(snap: StateSnapshot) {
        val currentEntities = synchronized(this.entities) { IntOpenHashSet(this.entities) }

        val toRemove = IntArrayList()
        val iter = currentEntities.iterator()
        while (iter.hasNext()) {
            val id = iter.nextInt()
            if (!snap.entities.contains(id)) toRemove.add(id)
        }
        if (!toRemove.isEmpty) {
            this.send(ClientboundRemoveEntitiesPacket(toRemove))
        }

        val snapIter = snap.entities.iterator()
        while (snapIter.hasNext()) {
            val id = snapIter.nextInt()
            if (!currentEntities.contains(id)) {
                val addPkt = snap.entityAddPackets.get(id)
                if (addPkt != null) this.send(addPkt)
            }
        }

        val snapTeleIter = snap.entities.iterator()
        while (snapTeleIter.hasNext()) {
            val id = snapTeleIter.nextInt()
            val t = snap.entityTransforms.get(id) ?: continue
            this.send(ClientboundTeleportEntityPacket(
                id, PositionMoveRotation(t.position, Vec3.ZERO, t.yRot, t.xRot), setOf(), false
            ))
        }

        val currentPlayers = synchronized(this.players) { HashSet(this.players) }
        val snapPlayers = HashSet(snap.players)
        val playersToRemove = currentPlayers.filter { it !in snapPlayers }
        if (playersToRemove.isNotEmpty()) {
            this.send(ClientboundPlayerInfoRemovePacket(playersToRemove))
        }
        val playersToAdd = snap.players.filter { it !in currentPlayers }
        val entries = playersToAdd.mapNotNull { uuid ->
            val profile = snap.playerProfiles[uuid] ?: return@mapNotNull null
            ClientboundPlayerInfoUpdatePacket.Entry(
                uuid, profile, false, 0, GameType.SPECTATOR, null, true, 0, null
            )
        }
        if (entries.isNotEmpty()) {
            this.send(ReplayViewerUtils.createClientboundPlayerInfoUpdatePacket(
                EnumSet.of(Action.ADD_PLAYER), entries
            ))
        }

        synchronized(this.entities) {
            this.entities.clear()
            val it = snap.entities.iterator()
            while (it.hasNext()) this.entities.add(it.nextInt())
        }
        synchronized(this.entityTransforms) {
            this.entityTransforms.clear()
            this.entityTransforms.putAll(snap.entityTransforms)
        }
        synchronized(this.players) {
            this.players.clear()
            this.players.addAll(snap.players)
        }
        synchronized(this.playerEntityIds) {
            this.playerEntityIds.clear()
            this.playerEntityIds.putAll(snap.playerEntityIds)
        }
        synchronized(this.playerProfiles) {
            this.playerProfiles.clear()
            this.playerProfiles.putAll(snap.playerProfiles)
        }
        this.entityAddCache.clear()
        this.entityAddCache.putAll(snap.entityAddPackets)
        this.position = snap.position

        if (snap.spectatingEntityId != -1 && snap.entities.contains(snap.spectatingEntityId)) {
            this.spectatingEntityId = -1
            this.spectateReplayEntity(snap.spectatingEntityId)
        } else if (this.spectatingEntityId != -1) {
            this.spectatingEntityId = -1
            val buf = FriendlyByteBuf(Unpooled.buffer(5))
            buf.writeVarInt(VIEWER_ID)
            this.send(ClientboundSetCameraPacket.STREAM_CODEC.decode(buf))
            this.send(ClientboundPlayerPositionPacket(
                0, PositionMoveRotation(snap.position, Vec3.ZERO, 0.0f, 0.0f), setOf()
            ))
        }
    }

    public fun jumpToMarker(name: String?, offset: Duration): Boolean {
        val markers = this.markers[name]
        if (markers.isEmpty()) {
            return false
        }
        val marker = markers.firstOrNull { it.timestamp > this.progress } ?: markers.first()
        return this.jumpTo(marker.timestamp + offset)
    }

    public fun getMarkers(): List<ReplayMarker> {
        return this.markers.values().sortedBy { it.timestamp }
    }

    public fun setSpeed(speed: Float) {
        if (speed <= 0) {
            throw IllegalArgumentException("Cannot set non-positive speed multiplier!")
        }
        this.speedMultiplier = speed
        this.sendTickingState()
    }

    public fun setPaused(paused: Boolean): Boolean {
        if (this.paused == paused) {
            return false
        }
        this.paused = paused
        this.sendTickingState()
        this.lastSentProgress = Duration.INFINITE
        this.updateProgress(this.progress)
        return true
    }

    public fun showProgress(): Boolean {
        if (!this.bossbar.isVisible) {
            this.bossbar.isVisible = true
            this.send(ClientboundBossEventPacket.createAddPacket(this.bossbar))
            return true
        }
        return false
    }

    public fun hideProgress(): Boolean {
        if (this.bossbar.isVisible) {
            this.bossbar.isVisible = false
            this.send(ClientboundBossEventPacket.createRemovePacket(this.bossbar.id))
            return true
        }
        return false
    }

    public fun getResourcePackUrl(hash: String): String {
        return this.packs.url(hash)
    }

    public fun getResourcePack(hash: String): InputStream? {
        return this.reader.readResourcePack(hash)
    }

    public fun resetCamera() {
        this.send(ClientboundPlayerPositionPacket(
            0, PositionMoveRotation(this.position, Vec3.ZERO, 0.0F, 0.0F), setOf()
        ))
    }

    public fun markForTeleportation() {
        this.teleported = false
    }

    public fun getReplayPlayers(): List<UUID> {
        synchronized(this.playerEntityIds) {
            return ArrayList(this.playerEntityIds.keys)
        }
    }

    public fun getReplayPlayerEntityId(uuid: UUID): Int {
        synchronized(this.playerEntityIds) {
            return this.playerEntityIds.getInt(uuid)
        }
    }

    public fun getReplayPlayerProfile(uuid: UUID): GameProfile? {
        synchronized(this.playerProfiles) {
            return this.playerProfiles[uuid]
        }
    }

    public fun spectateReplayEntity(entityId: Int) {
        this.spectatingEntityId = entityId
        val buf = FriendlyByteBuf(Unpooled.buffer(5))
        buf.writeVarInt(entityId)
        this.send(ClientboundSetCameraPacket.STREAM_CODEC.decode(buf))
        this.onSpectateChangeHandler?.invoke(this)
    }

    public fun stopSpectating() {
        if (this.spectatingEntityId == -1) {
            return
        }
        val spectated = this.spectatingEntityId
        this.spectatingEntityId = -1

        val target = synchronized(this.entityTransforms) { this.entityTransforms.get(spectated) }
        if (target != null) {
            this.position = target.position
            this.send(ClientboundPlayerPositionPacket(
                0, PositionMoveRotation(target.position, Vec3.ZERO, target.yRot, target.xRot), setOf()
            ))
        }

        val buf = FriendlyByteBuf(Unpooled.buffer(5))
        buf.writeVarInt(VIEWER_ID)
        this.send(ClientboundSetCameraPacket.STREAM_CODEC.decode(buf))
        this.onSpectateChangeHandler?.invoke(this)
    }

    private fun putTransform(entityId: Int, newPos: Vec3, yRot: Float, xRot: Float) {
        synchronized(this.entityTransforms) {
            this.entityTransforms.put(entityId, EntityTransform(newPos, yRot, xRot))
        }
    }

    private fun handleServerboundPacket(packet: Packet<*>): Boolean {
        // This is run async!
        when (packet) {
            is ServerboundChatCommandPacket -> ReplayViewerCommands.handleCommand(packet.command, this)
            is ServerboundChatCommandSignedPacket -> ReplayViewerCommands.handleCommand(packet.command, this)
            is ServerboundUseItemPacket -> {
                val handler = this.useItemHandler
                if (handler != null) {
                    this.server.execute { handler(this) }
                } else {
                    return false
                }
            }
            is ServerboundSwingPacket -> {
                val handler = this.attackHandler
                if (handler != null) {
                    this.server.execute { handler(this) }
                } else {
                    return false
                }
            }
            is ServerboundPlayerInputPacket -> {
                if (packet.input.shift() && this.spectatingEntityId != -1) {
                    this.server.execute { this.stopSpectating() }
                }
                return false
            }
            else -> return false
        }
        return true
    }

    private fun restart() {
        if (!this.started) {
            return
        }
        this.ringBuffer.clear()
        this.nextRingTime = Duration.ZERO
        this.entityAddCache.clear()
        this.removeReplayState()
        this.launchStream()
    }

    private fun seekRestart() {
        if (!this.started) {
            return
        }
        // Remove players and entities so snapshot can re-add them cleanly,
        // but keep chunks loaded to avoid the "loading terrain" screen.
        synchronized(this.players) {
            this.send(ClientboundPlayerInfoRemovePacket(ArrayList(this.players)))
        }
        synchronized(this.entities) {
            this.send(ClientboundRemoveEntitiesPacket(IntArrayList(this.entities)))
        }
        synchronized(this.playerEntityIds) { this.playerEntityIds.clear() }
        synchronized(this.playerProfiles) { this.playerProfiles.clear() }
        synchronized(this.entityTransforms) { this.entityTransforms.clear() }
        this.entityAddCache.clear()
        this.ringBuffer.clear()
        this.nextRingTime = Duration.ZERO
        if (this.spectatingEntityId != -1) {
            this.seekingSpectateId = this.spectatingEntityId
            this.spectatingEntityId = -1
        }
        this.launchStream()
    }

    private fun launchStream() {
        this.coroutineScope.coroutineContext.cancelChildren()
        this.target = Duration.ZERO

        this.sendViewerPlayerInfo()
        if (this.bossbar.isVisible) {
            this.send(ClientboundBossEventPacket.createAddPacket(this.bossbar))
        }

        this.coroutineScope.launch {
            // Un-lazy the markers
            markers

            try {
                streamReplay { this.isActive }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArcadeUtils.logger.error("Exception while viewing replay", e)
                server.execute {
                    stop()
                    player.sendSystemMessage(
                        Component.literal("Exception while viewing replay, see logs for more info").red()
                    )
                }
            }
        }
    }

    private suspend fun streamReplay(active: Supplier<Boolean>) {
        var lastTime = Duration.ZERO
        val iterator = this.reader.readPackets().iterator()
        while (active.get() && iterator.hasNext()) {
            val element = iterator.next()

            element.use { (protocol, packet, time) ->
                if (protocol == ConnectionProtocol.PLAY && time > this.target) {
                    delay((time - lastTime) / this.speedMultiplier.toDouble())
                }

                while (time > this.target && this.shouldPauseStreaming()) {
                    delay(50)
                }

                this.playbackPacket(protocol, packet, time, active)

                if (protocol == ConnectionProtocol.PLAY) {
                    val seekTo = this.seekingTo
                    when {
                        seekTo == null -> {
                            this.updateProgress(time)
                            this.maybePushRingSnapshot(time)
                        }
                        time >= seekTo -> {
                            this.seekingTo = null
                            val spectateId = this.seekingSpectateId
                            if (spectateId != -1) {
                                this.seekingSpectateId = -1
                                synchronized(this.entities) {
                                    if (this.entities.contains(spectateId)) {
                                        this.spectateReplayEntity(spectateId)
                                    }
                                }
                            }
                            this.updateProgress(time)
                            this.maybePushRingSnapshot(time)
                        }
                    }
                    lastTime = time
                }
            }
        }
    }

    private fun playbackPacket(
        protocol: ConnectionProtocol,
        packet: Packet<*>,
        time: Duration,
        active: Supplier<Boolean>
    ) {
        // We don't reconfigure the client, so we just ignore config packets,
        // this should probably be reworked at some point...
        if (protocol == ConnectionProtocol.CONFIGURATION && packet !is ClientboundResourcePackPushPacket) {
            return
        }

        if (this.shouldSendPacket(packet, time)) {
            val modified = modifyPacketForViewer(packet)
            this.onSendPacket(modified)
            if (!active.get()) {
                return
            }
            // During seek ff, suppress all client sends. State is tracked via onSendPacket;
            // client view was pre-synced to target state via ring-diff or will be re-synced on resume.
            if (this.seekingTo != null) {
                return
            }
            this.send(modified)
            this.afterSendPacket(modified)
        }
    }

    private fun showTargetProgress(timestamp: Duration) {
        this.seekingTo = timestamp
        this.lastSentProgress = timestamp
        this.progress = timestamp
        this.sendBossbarProgress(timestamp)
    }

    private fun updateProgress(progress: Duration) {
        if (abs((this.lastSentProgress - progress).inWholeMilliseconds) < 500) {
            return
        }
        this.lastSentProgress = progress
        this.progress = progress
        this.sendBossbarProgress(progress)
    }

    private fun sendBossbarProgress(timestamp: Duration) {
        val title = Component.empty()
            .append(Component.literal(this.reader.path.nameWithoutExtension).lime())
            .append(" ")
            .append(Component.literal(timestamp.formatHHMMSS()).yellow().bold())
        if (this.paused) {
            title.append(Component.literal(" ⏸").teal())
        }
        this.bossbar.name = title
        this.bossbar.progress = timestamp.div(this.reader.duration).toFloat()
        if (this.bossbar.isVisible) {
            this.send(ClientboundBossEventPacket.createUpdateProgressPacket(this.bossbar))
            this.send(ClientboundBossEventPacket.createUpdateNamePacket(this.bossbar))
        }
    }

    private fun shouldPauseStreaming(): Boolean {
        return this.paused || (this.server.isSingleplayer && Minecraft.getInstance().isPaused)
    }

    private fun sendTickingState() {
        this.send(this.getTickingStatePacket())
    }

    private fun getTickingStatePacket(): ClientboundTickingStatePacket {
        return ClientboundTickingStatePacket(this.tickSpeed * this.speedMultiplier, this.paused || this.tickFrozen)
    }

    private fun setForReplayView() {
        this.removeFromServer()
        this.connection.startViewingReplay(this)

        this.removeServerState()
        ReplayViewerCommands.sendCommandPacket(this::send)
    }

    private fun addBackToServer() {
        val player = this.player
        val server = player.server
        val playerList = server.playerList
        val level = player.level()

        playerList.broadcastAll(
            ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(listOf(player))
        )
        playerList.players.add(player)

        RejoinedReplayPlayer.place(player, this.connection, afterLogin = {
            this.synchronizeClientLevel()
        })
        playerList.sendPlayerPermissionLevel(player)

        (player as EntityInvoker).removeRemovalReason()
        level.addNewPlayer(player)

        for (pack in this.previousPacks) {
            this.connection.send(pack)
        }

        player.inventoryMenu.sendAllDataToRemote()
        this.connection.send(ClientboundSetHealthPacket(
            player.health,
            player.foodData.foodLevel,
            player.foodData.saturationLevel
        ))
        this.connection.send(ClientboundSetExperiencePacket(
            player.experienceProgress,
            player.totalExperience,
            player.experienceLevel
        ))
    }

    private fun removeFromServer() {
        val player = this.player
        val playerList = player.server.playerList
        playerList.broadcastAll(ClientboundPlayerInfoRemovePacket(listOf(player.uuid)))
        player.level().removePlayerImmediately(player, Entity.RemovalReason.CHANGED_DIMENSION)
        playerList.players.remove(player)
    }

    private fun removeServerState() {
        val player = this.player
        val server = player.server
        this.send(ClientboundPlayerInfoRemovePacket(server.playerList.players.map { it.uuid }))
        player.chunkTrackingView.forEach {
            this.send(ClientboundForgetLevelChunkPacket(it))
        }
        for (slot in DisplaySlot.entries) {
            this.send(ClientboundSetDisplayObjectivePacket(slot, null))
        }
        for (objective in server.scoreboard.objectives) {
            this.send(ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE))
        }
        for (bossbar in server.customBossEvents.events) {
            if (bossbar.players.contains(player)) {
                this.send(ClientboundBossEventPacket.createRemovePacket(bossbar.id))
            }
        }

        this.previousPacks.addAll((this.connection as PackTracker).`replay$getPacks`())
        this.send(ClientboundResourcePackPopPacket(Optional.empty()))
    }

    private fun removeReplayState() {
        synchronized(this.players) {
            this.send(ClientboundPlayerInfoRemovePacket(ArrayList(this.players)))
        }
        synchronized(this.entities) {
            this.send(ClientboundRemoveEntitiesPacket(IntArrayList(this.entities)))
        }
        synchronized(this.playerEntityIds) {
            this.playerEntityIds.clear()
        }
        synchronized(this.playerProfiles) {
            this.playerProfiles.clear()
        }
        synchronized(this.entityTransforms) {
            this.entityTransforms.clear()
        }
        this.spectatingEntityId = -1
        synchronized(this.chunks) {
            for (chunk in this.chunks.iterator()) {
                this.connection.send(ClientboundForgetLevelChunkPacket(ChunkPos.unpack(chunk)))
            }
        }
        synchronized(this.objectives) {
            for (objective in this.objectives) {
                val dummy = Objective(
                    Scoreboard(),
                    objective,
                    ObjectiveCriteria.DUMMY,
                    Component.empty(),
                    ObjectiveCriteria.RenderType.INTEGER,
                    false,
                    null
                )
                this.send(ClientboundSetObjectivePacket(dummy, ClientboundSetObjectivePacket.METHOD_REMOVE))
            }
        }

        this.send(ClientboundResourcePackPopPacket(Optional.empty()))

        if (this.bossbar.isVisible) {
            this.send(ClientboundBossEventPacket.createRemovePacket(this.bossbar.id))
        }
    }

    private fun sendViewerPlayerInfo() {
        this.players.add(this.player.uuid)

        val entry = ClientboundPlayerInfoUpdatePacket.Entry(
            this.player.uuid,
            this.player.gameProfile,
            false,
            0,
            this.viewerGameMode,
            null,
            true,
            0,
            null
        )
        this.send(
            ReplayViewerUtils.createClientboundPlayerInfoUpdatePacket(
            EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_GAME_MODE),
            listOf(entry)
        ))
    }

    private fun shouldSendPacket(packet: Packet<*>, time: Duration): Boolean {
        val nonSpectator = this.viewerGameMode != GameType.SPECTATOR
        return when (packet) {
            is ClientboundGameEventPacket -> packet.event != CHANGE_GAME_MODE
            is ClientboundPlayerAbilitiesPacket -> !nonSpectator
            is ClientboundContainerSetContentPacket -> !nonSpectator
            is ClientboundContainerSetSlotPacket -> !nonSpectator
            is ClientboundSetHealthPacket -> !nonSpectator
            is ClientboundSetExperiencePacket -> !nonSpectator
            is ClientboundPlayerPositionPacket -> {
                if (!packet.relatives.containsAll(setOf(Relative.X, Relative.Y, Relative.Z))) {
                    this.position = packet.change.position
                }
                val teleported = this.teleported
                this.teleported = true
                !teleported
            }
            else -> true
        }
    }

    private fun onSendPacket(packet: Packet<*>) {
        // We keep track of some state to revert later
        when (packet) {
            is ClientboundLevelChunkWithLightPacket -> this.chunks.add(ChunkPos.pack(packet.x, packet.z))
            is ClientboundForgetLevelChunkPacket -> this.chunks.remove(packet.pos.pack())
            is ClientboundAddEntityPacket -> {
                this.entities.add(packet.id)
                this.entityAddCache.put(packet.id, packet)
                this.putTransform(
                    packet.id,
                    Vec3(packet.x, packet.y, packet.z),
                    packet.yRot,
                    packet.xRot
                )
                val uuid = packet.uuid
                val isPlayer = synchronized(this.players) { this.players.contains(uuid) }
                if (isPlayer) {
                    synchronized(this.playerEntityIds) {
                        this.playerEntityIds.put(uuid, packet.id)
                    }
                }
            }
            is ClientboundMoveEntityPacket -> {
                val entityId = (packet as ClientboundMoveEntityPacketAccessor).entityId
                val previous = synchronized(this.entityTransforms) { this.entityTransforms.get(entityId) }
                    ?: return
                val newPos = if (packet.hasPosition()) {
                    Vec3(
                        previous.position.x + packet.xa / 4096.0,
                        previous.position.y + packet.ya / 4096.0,
                        previous.position.z + packet.za / 4096.0
                    )
                } else previous.position
                val newYRot = if (packet.hasRotation()) packet.yRot * 360f / 256f else previous.yRot
                val newXRot = if (packet.hasRotation()) packet.xRot * 360f / 256f else previous.xRot
                this.putTransform(entityId, newPos, newYRot, newXRot)
            }
            is ClientboundTeleportEntityPacket -> {
                val change = packet.change()
                this.putTransform(packet.id(), change.position(), change.yRot(), change.xRot())
            }
            is ClientboundEntityPositionSyncPacket -> {
                val values = packet.values()
                this.putTransform(packet.id(), values.position(), values.yRot(), values.xRot())
            }
            is ClientboundRemoveEntitiesPacket -> {
                this.entities.removeAll(packet.entityIds)
                synchronized(this.entityTransforms) {
                    for (i in 0 until packet.entityIds.size) {
                        this.entityTransforms.remove(packet.entityIds.getInt(i))
                    }
                }
                for (i in 0 until packet.entityIds.size) {
                    this.entityAddCache.remove(packet.entityIds.getInt(i))
                }
                if (this.spectatingEntityId != -1 && packet.entityIds.contains(this.spectatingEntityId)) {
                    this.server.execute { this.stopSpectating() }
                }
                synchronized(this.playerEntityIds) {
                    val iter = this.playerEntityIds.object2IntEntrySet().iterator()
                    while (iter.hasNext()) {
                        if (packet.entityIds.contains(iter.next().intValue)) {
                            iter.remove()
                        }
                    }
                }
            }
            is ClientboundSetObjectivePacket -> {
                if (packet.method == ClientboundSetObjectivePacket.METHOD_REMOVE) {
                    this.objectives.remove(packet.objectiveName)
                } else {
                    this.objectives.add(packet.objectiveName)
                }
            }
            is ClientboundPlayerInfoUpdatePacket -> {
                for (entry in packet.newEntries()) {
                    this.players.add(entry.profileId)
                    val profile = entry.profile
                    if (profile != null) {
                        synchronized(this.playerProfiles) {
                            this.playerProfiles.put(entry.profileId, profile)
                        }
                    }
                }
            }
            is ClientboundPlayerInfoRemovePacket -> {
                this.players.removeAll(packet.profileIds.toSet())
                synchronized(this.playerEntityIds) {
                    for (uuid in packet.profileIds) {
                        this.playerEntityIds.removeInt(uuid)
                    }
                }
                synchronized(this.playerProfiles) {
                    for (uuid in packet.profileIds) {
                        this.playerProfiles.remove(uuid)
                    }
                }
            }
            is ClientboundRespawnPacket -> this.teleported = false
        }
    }

    private fun sendViewerAbilities() {
        if (this.viewerGameMode != GameType.SPECTATOR) {
            val abilities = Abilities()
            abilities.mayfly = true
            abilities.flying = true
            abilities.invulnerable = true
            this.send(ClientboundPlayerAbilitiesPacket(abilities))
        }
    }

    private fun afterSendPacket(packet: Packet<*>) {
        when (packet) {
            is ClientboundLoginPacket -> {
                this.synchronizeClientLevel()
                this.send(ClientboundGameEventPacket(CHANGE_GAME_MODE, this.viewerGameMode.id.toFloat()))
                this.sendViewerAbilities()
                this.onReadyHandler?.invoke(this)
            }
            is ClientboundRespawnPacket -> {
                this.send(ClientboundGameEventPacket(CHANGE_GAME_MODE, this.viewerGameMode.id.toFloat()))
                this.sendViewerAbilities()
            }
        }
    }

    private fun modifyPacketForViewer(packet: Packet<*>): Packet<*> {
        if (packet is ClientboundLoginPacket) {
            // Give the viewer a different ID to not conflict
            // with any entities in the replay
            return ClientboundLoginPacket(
                VIEWER_ID,
                packet.hardcore,
                packet.levels,
                packet.maxPlayers,
                packet.chunkRadius,
                packet.simulationDistance,
                packet.reducedDebugInfo,
                packet.showDeathScreen,
                packet.doLimitedCrafting,
                packet.commonPlayerSpawnInfo,
                packet.enforcesSecureChat
            )
        }
        if (packet is ClientboundPlayerInfoUpdatePacket) {
            val copy = ArrayList(packet.entries())
            if (packet.actions().contains(Action.INITIALIZE_CHAT)) {
                val iter = copy.listIterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    iter.set(ClientboundPlayerInfoUpdatePacket.Entry(
                        entry.profileId,
                        entry.profile,
                        entry.listed,
                        entry.latency,
                        entry.gameMode,
                        entry.displayName,
                        entry.showHat,
                        entry.listOrder,
                        null
                    ))
                }
            }

            val index = packet.entries().indexOfFirst { it.profileId == this.player.uuid }
            if (index >= 0) {
                val previous = copy[index]
                copy[index] = ClientboundPlayerInfoUpdatePacket.Entry(
                    VIEWER_UUID,
                    previous.profile,
                    previous.listed,
                    previous.latency,
                    previous.gameMode,
                    previous.displayName,
                    previous.showHat,
                    previous.listOrder,
                    null
                )
            }
            return ReplayViewerUtils.createClientboundPlayerInfoUpdatePacket(packet.actions(), copy)
        }
        if (packet is ClientboundAddEntityPacket && packet.uuid == this.player.uuid) {
            return ClientboundAddEntityPacket(
                packet.id,
                VIEWER_UUID,
                packet.x,
                packet.y,
                packet.z,
                packet.xRot,
                packet.yRot,
                packet.type,
                packet.data,
                packet.movement,
                packet.yHeadRot.toDouble()
            )
        }
        if (packet is ClientboundPlayerChatPacket) {
            // We don't want to deal with chat validation...
            val message = packet.unsignedContent ?: Component.literal(packet.body.content)
            val decorated = packet.chatType.decorate(message)
            return ClientboundSystemChatPacket(decorated, false)
        }
        if (packet is ClientboundTickingStatePacket) {
            this.tickSpeed = packet.tickRate
            this.tickFrozen = packet.isFrozen
            return this.getTickingStatePacket()
        }

        return packet
    }

    private fun synchronizeClientLevel() {
        this.send(ClientboundRespawnPacket(
            this.player.createCommonSpawnInfo(this.player.level()),
            ClientboundRespawnPacket.KEEP_ALL_DATA
        ))
    }

    internal fun send(packet: Packet<*>) {
        this.connection.sendReplayPacket(packet)
    }

    internal companion object {
        private const val VIEWER_ID = Int.MAX_VALUE - 10
        private val VIEWER_UUID: UUID = UUIDUtil.createOfflinePlayerUUID("-ViewingProfile-")
        private val RING_WINDOW = 10_000.milliseconds
        private val RING_CADENCE = 500.milliseconds

        fun registerEvents() {
            GlobalEventHandler.Server.register<PlayerServerboundPacketEvent>(::onPlayerServerboundPacket)
        }

        private fun onPlayerServerboundPacket(event: PlayerServerboundPacketEvent) {
            val (player, packet) = event
            val viewer = player.connection.getViewingReplay() ?: return
            if (viewer.handleServerboundPacket(packet) || !ReplayViewerPackets.serverboundBypass(packet)) {
                event.cancel()
            }
        }
    }
}