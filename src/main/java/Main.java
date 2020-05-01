import abstraction.ProtocolTasks;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import common.Block;
import common.BlockChain;
import configuration.Configuration;
import logging.Logger;
import network.NetworkManager;
import protocols.BlockPropagation;
import utils.Crypto;
import utils.Utils;
import utils.VDF;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Created by Mihael Berčič
 * on 26/03/2020 12:35
 * using IntelliJ IDEA
 */


public class Main {

    /**
     * Logger use:
     * Java -> Logger.INSTANCE.debug(...)
     * Kotlin -> Logger.debug(...)
     */

    public static Gson gson = new GsonBuilder()
            //.setPrettyPrinting() // For debugging...
            .create();

    public static void main(String[] args) throws UnknownHostException {
        Logger.INSTANCE.debug("Assembly without compile test...");
        boolean isPathSpecified = args.length != 0;

        Logger.INSTANCE.debug("Starting...");
        Logger.INSTANCE.info("Path for config file specified: " + isPathSpecified);
        Logger.INSTANCE.info("Using " + (isPathSpecified ? "custom" : "default") + " configuration file...");

        String fileText = Utils.Companion.readFile(isPathSpecified ? args[0] : "./config.json");

        Configuration configuration = gson.fromJson(fileText, Configuration.class);
        Crypto crypto = new Crypto(".");
        VDF vdf = new VDF();
        BlockChain blockChain = new BlockChain(crypto,vdf,configuration);
        NetworkManager networkManager = new NetworkManager(configuration, crypto, blockChain);
        blockChain.injectDependency(networkManager);
        //the bootstrap node should start block production
        if(InetAddress.getLocalHost().getHostAddress().equals(configuration.getTrustedNodeIP())) {
            blockChain.addBlock(new Block(crypto.getPublicKey(), 200000));
        }

        //start producing blocks
        while (InetAddress.getLocalHost().getHostAddress().equals(configuration.getTrustedNodeIP())) { //only trusted node for now
            if(blockChain.getLastBlock()!=null) {//oh god
                if (blockChain.getLastBlock().getConsensus_nodes().contains(crypto.getPublicKey())) {//we are amongst the block producers
                    String proof = null;
                    try {
                        Block previous_block = blockChain.getLastBlock();
                        proof = vdf.runVDF(previous_block.getDifficulty(), previous_block.getHash());
                        Block new_block = new Block(previous_block, proof, crypto);
                        String outcome = blockChain.addBlock(new_block);
                        Logger.INSTANCE.info("New Block forged " + outcome);
                        networkManager.initiate(ProtocolTasks.newBlock, new_block);
                    } catch (IOException e) {
                        Logger.INSTANCE.error(e.getMessage());
                    } catch (InterruptedException e) {
                        Logger.INSTANCE.error(e.getMessage());
                    }
                }
            }
        }
    }
}