package com.rpgworldgen.gen;

/**
 * Thrown when a builder needs a chunk that is not loaded yet.
 *
 * <p>The scheduler catches this, queues background chunk generation, and retries
 * the work item on a later tick — instead of blocking the server thread on
 * synchronous chunk generation (which trips the watchdog on fresh worlds).
 */
public class ChunkNotReadyException extends RuntimeException {
    private final int cx;
    private final int cz;

    public ChunkNotReadyException(int cx, int cz) {
        super("Chunk not ready: " + cx + "," + cz);
        this.cx = cx;
        this.cz = cz;
    }

    public int getChunkX() { return cx; }
    public int getChunkZ() { return cz; }
}
