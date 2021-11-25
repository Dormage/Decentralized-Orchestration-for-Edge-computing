package network

import data.Configuration
import data.chain.Block
import data.communication.Message
import data.communication.TransmissionType
import data.docker.DockerContainer
import data.docker.DockerStatistics
import data.network.Endpoint
import logging.Dashboard
import logging.Logger
import utils.runAfter
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Created by Mihael Valentin Berčič
 * on 18/11/2021 at 12:32
 * using IntelliJ IDEA
 */
abstract class DockerProxy(configuration: Configuration) : MigrationStrategy(configuration) {

    private val networkLock = ReentrantLock(true)
    private val networkStatistics = ConcurrentHashMap<Long, MutableList<DockerStatistics>>()

    init {
        Thread(::listenForDockerStatistics).start()
    }

    private fun addNetworkStatistics(vararg statistics: DockerStatistics) {
        networkLock.withLock {
            statistics.forEach { dockerStatistics ->
                val list = networkStatistics.computeIfAbsent(dockerStatistics.slot) { mutableListOf() }
                list.add(dockerStatistics)
            }
        }
        Logger.info("Added ${statistics.size} statistics...")
    }

    fun getNetworkStatistics(slot: Long): List<DockerStatistics> {
        return networkLock.withLock { networkStatistics[slot]?.toList() ?: emptyList() }
    }

    fun sendDockerStatistics(block: Block, blockProducer: String, clusters: List<Cluster>) {
        val slot = block.slot
        val mapped = localContainers.values.map { it.copy(id = networkMappings[it.id] ?: it.id) }
        val localStatistics = DockerStatistics(localNode.publicKey, mapped, slot)
        val ourPublicKey = localNode.publicKey
        val isRepresentative = clusters.any { it.representative == ourPublicKey }
        val ourCluster = clusters.firstOrNull { it.representative == ourPublicKey || it.nodes.contains(ourPublicKey) }
        Logger.info("Sending docker statistics[$isRepresentative]: ${ourCluster?.nodes?.size ?: 0}")
        if (!isRepresentative) {
            if (ourCluster != null) send(Endpoint.NodeStatistics, TransmissionType.Unicast, arrayOf(localStatistics), ourCluster.representative)
            else addNetworkStatistics(localStatistics)
        } else runAfter(configuration.slotDuration / 2) {
            val statistics = getNetworkStatistics(slot).plus(localStatistics)
            send(Endpoint.NodeStatistics, TransmissionType.Unicast, statistics, blockProducer)
        }

    }

    @MessageEndpoint(Endpoint.NodeStatistics)
    fun dockerStatisticsReceived(message: Message) {
        val receivedStatistics = message.decodeAs<Array<DockerStatistics>>()
        addNetworkStatistics(*receivedStatistics)
    }

    /** Starts a process of `docker stats` and keeps the [localStatistics] up to date. */
    private fun listenForDockerStatistics() {
        val process = ProcessBuilder()
            .command("docker", "stats", "--no-trunc", "--format", "{{.ID}} {{.CPUPerc}} {{.MemPerc}} {{.PIDs}}")
            .redirectErrorStream(true)
            .start()

        val buffer = ByteBuffer.allocate(100_000)
        val escapeSequence = byteArrayOf(0x1B, 0x5B, 0x32, 0x4A, 0x1B, 0x5B, 0x48)
        var escapeIndex = 0
        process.inputStream.use { inputStream ->
            while (true) {
                try {
                    val byte = inputStream.read().toByte()
                    if (byte < 0) break
                    buffer.put(byte)
                    if (byte == escapeSequence[escapeIndex]) escapeIndex++ else escapeIndex = 0
                    if (escapeIndex != escapeSequence.size) continue
                    val length = buffer.position() - escapeSequence.size
                    if (length > 0) String(buffer.array(), 0, length).split("\n").map { line ->
                        if (line.isNotEmpty()) {
                            val fields = line.split(" ")
                            val containerId = fields[0]
                            if (fields.none { it.contains("-") || it.isEmpty() }) {
                                val cpuPercentage = fields[1].trim('%').toDouble()
                                val memoryPercentage = fields[2].trim('%').toDouble()
                                val processes = fields[3].toInt()
                                localContainers[containerId] = DockerContainer(containerId, cpuPercentage, memoryPercentage, processes)
                            } else localContainers[containerId]?.apply { updated = System.currentTimeMillis() }
                        }
                    }
                    buffer.clear()
                    escapeIndex = 0
                } catch (e: Exception) {
                    buffer.clear()
                    escapeIndex = 0
                    Dashboard.reportException(e)
                }
            }
        }

    }
}