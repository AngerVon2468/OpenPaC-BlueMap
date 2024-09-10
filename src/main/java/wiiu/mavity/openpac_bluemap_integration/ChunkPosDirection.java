package wiiu.mavity.openpac_bluemap_integration;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public enum ChunkPosDirection {
    LEFT(-1, 0),
    FORWARD(0, 1),
    RIGHT(1, 0),
    BACKWARD(0, -1);

    public final int x, z;
    public final ChunkPos value;

    ChunkPosDirection(int x, int z) {
        this.x = x;
        this.z = z;
        this.value = new ChunkPos(x, z);
    }

    public ChunkPosDirection getLeft() {
        return rotate(-1);
    }

    public ChunkPosDirection getRight() {
        return rotate(1);
    }

    public ChunkPosDirection getOpposite() {
        return rotate(2);
    }

    public ChunkPosDirection rotate(int clockwiseDistance) {
        return values()[(ordinal() + clockwiseDistance) & 3];
    }

    public ChunkPos add(ChunkPos pos) {
        return new ChunkPos(pos.x + x, pos.z + z);
    }

    public int getEdge(ChunkPos pos) {
        return switch (this) {
            case LEFT -> pos.getMinBlockX();
            case FORWARD -> pos.getMaxBlockZ() + 1;
            case RIGHT -> pos.getMaxBlockX() + 1;
            case BACKWARD -> pos.getMinBlockZ();
        };
    }

    public static BlockPos getCorner(ChunkPos pos, ChunkPosDirection a, ChunkPosDirection b) {
        if (a == b || a.getOpposite() == b) {
            throw new IllegalArgumentException("Corner can't be accessed with opposite directions");
        }
        return a.x != 0
                ? new BlockPos(a.getEdge(pos), 0, b.getEdge(pos))
                : new BlockPos(b.getEdge(pos), 0, a.getEdge(pos));
    }
}