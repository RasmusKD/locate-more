package com.rasmus.locatemore;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.minecraft.world.level.biome.Climate;

/**
 * Proved column rejection by one climate dimension: the box-cover proof.
 *
 * <p>Multinoise picks the parameter point with minimal fitness, the sum of
 * squared per-dimension distances to the point's intervals plus its squared
 * offset. A value t of dimension d outside every target interval does NOT by
 * itself exclude the target: where no non-target point lies near the sample in
 * the other dimensions, the target still wins outside its declared interval.
 * Underground biomes are the vanilla case: lush caves declares humidity
 * 0.7..1 and depth 0.2..0.9 while every surface point sits at depth 0 or 1.
 * Measured on vanilla: at humidity 0.35 and depth 0.62 lush caves costs
 * 0.1225 and the best non-target (river) 0.1455, so lush caves generates
 * there, half its declared width below the interval.
 *
 * <p>The proof: fix t outside the target union and a target point T. Let J
 * range over the non-target points whose d interval contains t and whose
 * offset satisfies offJ^2 < dist_d(T, t)^2 + offT^2. If the boxes of those J,
 * taken in the five other dimensions, cover T's box there, then for any
 * sample (t, w) let w' be the projection of w onto T's box; some J contains
 * w', so per dimension dist(J, w) <= |w - w'| = dist(T, w), and
 * fitness_J = sum dist(J, w)^2 + offJ^2 <= sum dist(T, w)^2 + offJ^2
 * < dist_d(T, t)^2 + sum dist(T, w)^2 + offT^2 = fitness_T.
 * When that holds for every target point, a non-target strictly beats every
 * target at every value of the other five dimensions, so no target can be
 * picked: the rejection holds for every y sample of the column, on any
 * datapack, with no referee. All arithmetic is on vanilla's own quantized
 * longs, so the proof is exact.
 */
final class ProvenRejection {

    private ProvenRejection() {
    }

    /** Dimension order shared with ClimateAccess: 0 temperature, 1 humidity,
     * 2 continentalness, 3 erosion, 4 weirdness, 5 depth. */
    static Climate.Parameter parameter(Climate.ParameterPoint point, int dim) {
        return switch (dim) {
            case 0 -> point.temperature();
            case 1 -> point.humidity();
            case 2 -> point.continentalness();
            case 3 -> point.erosion();
            case 4 -> point.weirdness();
            default -> point.depth();
        };
    }

    /** Vanilla's fitness of a parameter point for a quantized six-value sample (dimension order above). */
    static long fitness(Climate.ParameterPoint point, long[] sample) {
        long sum = point.offset() * point.offset();
        for (int dim = 0; dim < 6; dim++) {
            long d = parameter(point, dim).distance(sample[dim]);
            sum += d * d;
        }
        return sum;
    }

    /** One parameter point flattened: min/max per dimension and the squared offset. */
    private record Box(long[] min, long[] max, long offsetSquared) {
        static Box of(Climate.ParameterPoint point) {
            long[] min = new long[6];
            long[] max = new long[6];
            for (int d = 0; d < 6; d++) {
                Climate.Parameter p = parameter(point, d);
                min[d] = p.min();
                max[d] = p.max();
            }
            return new Box(min, max, point.offset() * point.offset());
        }
    }

    /**
     * The quantized values of dimension {@code dim} at which the covering
     * argument proves that no target point can win, as sorted, disjoint
     * [min,max] pairs, flattened. Values inside a target interval are never
     * included; values no non-target interval reaches are never included.
     */
    static <B> long[] rejected(int dim, List<? extends Pair<Climate.ParameterPoint, B>> points,
            Predicate<? super B> isTarget) {
        List<Box> targets = new ArrayList<>();
        List<Box> others = new ArrayList<>();
        TreeSet<Long> edges = new TreeSet<>();
        for (Pair<Climate.ParameterPoint, B> pair : points) {
            Box box = Box.of(pair.getFirst());
            edges.add(box.min[dim]);
            edges.add(box.max[dim]);
            (isTarget.test(pair.getSecond()) ? targets : others).add(box);
        }
        if (targets.isEmpty() || others.isEmpty()) {
            return new long[0];
        }
        // Between consecutive edges the set of non-target intervals containing
        // t is constant, and so is "inside some target interval" (target edges
        // are edges too). Pieces: every edge on its own, and the open run up to
        // the next edge. Beyond the outermost edges no non-target interval
        // contains t, so nothing there is ever proved.
        List<long[]> pieces = new ArrayList<>();
        Long previous = null;
        for (long edge : edges) {
            if (previous != null && edge - previous > 1) {
                pieces.add(new long[]{previous + 1, edge - 1});
            }
            pieces.add(new long[]{edge, edge});
            previous = edge;
        }
        List<long[]> out = new ArrayList<>();
        for (long[] piece : pieces) {
            if (proves(dim, piece, targets, others)) {
                if (!out.isEmpty() && out.get(out.size() - 1)[1] + 1 == piece[0]) {
                    out.get(out.size() - 1)[1] = piece[1];
                } else {
                    out.add(new long[]{piece[0], piece[1]});
                }
            }
        }
        long[] flat = new long[out.size() * 2];
        for (int i = 0; i < out.size(); i++) {
            flat[i * 2] = out.get(i)[0];
            flat[i * 2 + 1] = out.get(i)[1];
        }
        return flat;
    }

    private static boolean proves(int dim, long[] piece, List<Box> targets, List<Box> others) {
        long t = piece[0];
        List<Box> containing = new ArrayList<>();
        for (Box other : others) {
            if (other.min[dim] <= t && t <= other.max[dim]) {
                containing.add(other);
            }
        }
        if (containing.isEmpty()) {
            return false;
        }
        List<long[][]> cover = new ArrayList<>();
        for (Box target : targets) {
            // The piece lies wholly on one side of every target interval;
            // its nearest value gives the smallest distance.
            long gap;
            if (piece[1] < target.min[dim]) {
                gap = target.min[dim] - piece[1];
            } else if (piece[0] > target.max[dim]) {
                gap = piece[0] - target.max[dim];
            } else {
                return false;
            }
            long budget = gap * gap + target.offsetSquared;
            long[][] box = project(target, dim);
            cover.clear();
            for (Box other : containing) {
                if (other.offsetSquared < budget && meets(other, target, dim)) {
                    cover.add(project(other, dim));
                }
            }
            if (!covered(box, cover, 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean meets(Box a, Box b, int dim) {
        for (int d = 0; d < 6; d++) {
            if (d != dim && (a.max[d] < b.min[d] || b.max[d] < a.min[d])) {
                return false;
            }
        }
        return true;
    }

    /** The box's intervals in the five dimensions other than {@code dim}, as [k][min,max]. */
    private static long[][] project(Box box, int dim) {
        long[][] out = new long[5][];
        int k = 0;
        for (int d = 0; d < 6; d++) {
            if (d != dim) {
                out[k++] = new long[]{box.min[d], box.max[d]};
            }
        }
        return out;
    }

    private static boolean intersects(long[][] a, long[][] b) {
        for (int k = 0; k < a.length; k++) {
            if (a[k][1] < b[k][0] || b[k][1] < a[k][0]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the closed integer box is covered by the union of the closed
     * boxes from index {@code from} on. Splits the box around the first box
     * that meets it; the split-off slabs are disjoint from that box and from
     * every earlier one, so each recurses on the remaining list only.
     */
    static boolean covered(long[][] box, List<long[][]> boxes, int from) {
        for (int i = from; i < boxes.size(); i++) {
            long[][] other = boxes.get(i);
            if (!intersects(other, box)) {
                continue;
            }
            long[][] rest = copy(box);
            for (int k = 0; k < rest.length; k++) {
                if (rest[k][0] < other[k][0]) {
                    long[][] slab = copy(rest);
                    slab[k][1] = other[k][0] - 1;
                    if (!covered(slab, boxes, i + 1)) {
                        return false;
                    }
                    rest[k][0] = other[k][0];
                }
                if (rest[k][1] > other[k][1]) {
                    long[][] slab = copy(rest);
                    slab[k][0] = other[k][1] + 1;
                    if (!covered(slab, boxes, i + 1)) {
                        return false;
                    }
                    rest[k][1] = other[k][1];
                }
            }
            return true;
        }
        return false;
    }

    private static long[][] copy(long[][] box) {
        long[][] out = new long[box.length][];
        for (int k = 0; k < box.length; k++) {
            out[k] = box[k].clone();
        }
        return out;
    }
}
