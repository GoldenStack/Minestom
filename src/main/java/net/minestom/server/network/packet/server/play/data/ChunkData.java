package net.minestom.server.network.packet.server.play.data;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.coordinate.CoordConversionUtils;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.utils.block.BlockUtils;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static net.minestom.server.network.NetworkBuffer.*;

public record ChunkData(@NotNull CompoundBinaryTag heightmaps, byte @NotNull [] data,
                        @NotNull Map<Integer, Block> blockEntities) implements NetworkBuffer.Writer {
    public ChunkData {
        blockEntities = blockEntities.entrySet()
                .stream()
                .filter((entry) -> entry.getValue().registry().isBlockEntity())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public ChunkData(@NotNull NetworkBuffer reader) {
        this((CompoundBinaryTag) reader.read(NBT), reader.read(BYTE_ARRAY),
                readBlockEntities(reader));
    }

    @Override
    public void write(@NotNull NetworkBuffer writer) {
        // Heightmaps
        writer.write(NBT, this.heightmaps);
        // Data
        writer.write(BYTE_ARRAY, data);
        // Block entities
        writer.write(VAR_INT, blockEntities.size());
        for (var entry : blockEntities.entrySet()) {
            final int index = entry.getKey();
            final Block block = entry.getValue();
            final var registry = block.registry();

            final Point point = CoordConversionUtils.blockIndexToGlobal(index, 0, 0);
            writer.write(BYTE, (byte) ((point.blockX() & 15) << 4 | point.blockZ() & 15)); // xz
            writer.write(SHORT, (short) point.blockY()); // y

            writer.write(VAR_INT, registry.blockEntityId());
            final CompoundBinaryTag nbt = BlockUtils.extractClientNbt(block);
            assert nbt != null;
            writer.write(NBT, nbt); // block nbt
        }
    }

    private static Map<Integer, Block> readBlockEntities(@NotNull NetworkBuffer reader) {
        final Map<Integer, Block> blockEntities = new HashMap<>();
        final int size = reader.read(VAR_INT);
        for (int i = 0; i < size; i++) {
            final byte xz = reader.read(BYTE);
            final short y = reader.read(SHORT);
            final int blockEntityId = reader.read(VAR_INT);
            final CompoundBinaryTag nbt = (CompoundBinaryTag) reader.read(NBT);
            // TODO create block object
        }
        return blockEntities;
    }
}
