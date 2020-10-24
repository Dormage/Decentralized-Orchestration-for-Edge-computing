package manager

import data.BlockVote
import data.Message
import data.VoteRequest
import data.VoteType
import logging.Logger
import org.apache.commons.codec.digest.DigestUtils

/**
 * Created by Mihael Valentin Berčič
 * on 04/10/2020 at 17:17
 * using IntelliJ IDEA
 */
class CommitteeManager(private val networkManager: NetworkManager) {

    private val crypto = networkManager.crypto
    private val vdfManager = networkManager.vdf
    private val dashboardManager = networkManager.dashboard

    fun voteRequest(message: Message<VoteRequest>) {
        val voteRequest = message.body
        val block = voteRequest.block
        val producer = voteRequest.producer

        val blockVote = BlockVote(block.hash, crypto.sign(block.hash), VoteType.FOR)
        dashboardManager.newVote(blockVote, DigestUtils.sha256Hex(crypto.publicKey))
        val messageToSend = networkManager.generateMessage(blockVote)

        val isValidProof = vdfManager.verifyProof(block.difficulty, block.precedentHash, block.vdfProof)
        if (!isValidProof) Logger.error(block)
        if (isValidProof) producer.sendMessage("/vote", messageToSend)
    }

}