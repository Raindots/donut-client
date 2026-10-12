package dev.donut.client.autobuild;

import java.util.Random;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure format math does not depend on bootstrapping Minecraft's registries. */
final class PackedSchematicTest {
    @Test void decodesEveryBitWidthAcrossSignedLongBoundaries() {
        Random random = new Random(824319L);
        for (int bits = 2; bits <= 16; bits++) {
            int[] values = new int[513];
            long[] words = new long[(values.length * bits + 63) / 64];
            for (int index = 0; index < values.length; index++) {
                values[index] = random.nextInt(1 << bits);
                long bit = (long) index * bits;
                int word = (int) (bit / 64), offset = (int) (bit % 64);
                words[word] |= (long) values[index] << offset;
                if (offset + bits > 64) words[word + 1] |= (long) values[index] >>> (64 - offset);
            }
            for (int index = 0; index < values.length; index++)
                assertEquals(values[index], LitematicLoader.packedIndex(words, bits, index), "bits=" + bits + ", index=" + index);
        }
    }

    @Test void rejectsOutOfBoundsPackedReadsAndInvalidPaletteSizes() {
        assertThrows(IllegalArgumentException.class, () -> LitematicLoader.packedIndex(new long[0], 2, 0));
        assertThrows(IllegalArgumentException.class, () -> LitematicLoader.packedIndex(new long[1], 3, 21));
        assertThrows(IllegalArgumentException.class, () -> LitematicLoader.packedIndex(new long[1], 1, 0));
        assertThrows(IllegalArgumentException.class, () -> LitematicLoader.packedIndex(new long[1], 2, -1));
        assertThrows(IllegalArgumentException.class, () -> LitematicLoader.bitsForPalette(0));
        assertThrows(IllegalArgumentException.class, () -> LitematicLoader.bitsForPalette(65537));
        assertEquals(2, LitematicLoader.bitsForPalette(1));
        assertEquals(2, LitematicLoader.bitsForPalette(4));
        assertEquals(3, LitematicLoader.bitsForPalette(5));
        assertEquals(16, LitematicLoader.bitsForPalette(65536));
    }

    @Test void transformsMirrorBeforeRotationAndNormalizesQuarterTurns() {
        BlockPos origin = new BlockPos(20, 64, -10), local = new BlockPos(2, 3, 5);
        assertEquals(new BlockPos(22, 67, -5), new PlacementTransform(origin, 0, false).apply(local));
        assertEquals(new BlockPos(15, 67, -8), new PlacementTransform(origin, 1, false).apply(local));
        assertEquals(new BlockPos(18, 67, -15), new PlacementTransform(origin, 2, false).apply(local));
        assertEquals(new BlockPos(25, 67, -12), new PlacementTransform(origin, -1, false).apply(local));
        assertEquals(new BlockPos(15, 67, -12), new PlacementTransform(origin, 5, true).apply(local));
    }
}
