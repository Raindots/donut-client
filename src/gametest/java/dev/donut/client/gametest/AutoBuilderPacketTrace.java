package dev.donut.client.gametest;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

/** Records the production outbound path only while the engine fixture owns its connection. */
public final class AutoBuilderPacketTrace {
    private static ClientCommonPacketListenerImpl connection;
    private static int movements, maximumMovements, tickEnds, placements, lastSequence;
    private static String violation;

    private AutoBuilderPacketTrace() { }

    public static void begin(ClientCommonPacketListenerImpl listener) {
        stop(); connection=listener;
    }

    public static void stop() {
        connection=null; movements=0; maximumMovements=0; tickEnds=0; placements=0; lastSequence=0; violation=null;
    }

    public static void disconnected(ClientCommonPacketListenerImpl listener) {
        if (connection==listener) stop();
    }

    public static void sent(ClientCommonPacketListenerImpl listener, Packet<?> packet) {
        if (listener!=connection) return;
        if (packet instanceof ServerboundMovePlayerPacket) {
            maximumMovements=Math.max(maximumMovements,++movements);
            if (movements>1 && violation==null) violation="Multiple regular movement packets before client tick end " + tickEnds;
        } else if (packet instanceof ServerboundClientTickEndPacket) {
            tickEnds++; movements=0;
        } else if (packet instanceof ServerboundUseItemOnPacket placement) {
            int sequence=placement.getSequence();
            if ((sequence<=0 || sequence<=lastSequence) && violation==null)
                violation="Invalid placement sequence " + sequence + " after " + lastSequence;
            lastSequence=sequence; placements++;
        }
    }

    public static void assertHealthy(int minimumPlacements) {
        if (connection==null) throw new AssertionError("AutoBuilder packet regression recorder lost its test connection");
        if (violation!=null) throw new AssertionError(violation + "; " + summary());
        if (placements<minimumPlacements || tickEnds==0 || maximumMovements!=1)
            throw new AssertionError("AutoBuilder packet regression did not exercise enough production traffic: " + summary());
    }

    public static String summary() {
        return placements + " placements, " + tickEnds + " client tick ends, maximum " + maximumMovements
                + " regular movement packet per tick, final sequence " + lastSequence;
    }
}
