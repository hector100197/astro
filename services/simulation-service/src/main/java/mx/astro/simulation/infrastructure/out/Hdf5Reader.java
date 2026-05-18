package mx.astro.simulation.infrastructure.out;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import io.jhdf.api.Group;
import io.jhdf.api.Node;
import mx.astro.simulation.domain.Snapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Pure-Java HDF5 reader (jHDF) for replaying saved runs in the live viewer.
 *
 * <p>Supports the multi-snapshot layout this project's Fortran kernel writes:
 *
 * <pre>
 *   /Header/@NumPart, @Format
 *   /Snapshots/00000/Coordinates  (3 × N)
 *                   /Velocities   (3 × N)
 *                   /Masses       (N)
 *                   @Time, @Step
 *   /Snapshots/00001/...
 * </pre>
 *
 * <p>Returns the snapshots as ordered Java {@link Snapshot} objects (sorted
 * by group name so the timeline is correct). Single-snapshot files in the
 * older /PartType1 layout are also supported via {@link #readSingle}.
 */
@Component
@Profile("!mock")
public class Hdf5Reader {

    /** Read all snapshots from a multi-snapshot file, in time order. */
    public List<Snapshot> readMultiSnapshots(Path file) {
        try (HdfFile hdf = new HdfFile(file)) {
            Object snapsNode = hdf.getByPath("Snapshots");
            if (!(snapsNode instanceof Group snaps)) {
                throw new IllegalStateException("File has no /Snapshots group: " + file);
            }
            // Sort children by name (group names are zero-padded, so lex sort = step order).
            TreeMap<String, Node> sorted = new TreeMap<>(snaps.getChildren());

            List<Snapshot> out = new ArrayList<>(sorted.size());
            int idx = 0;
            for (var entry : sorted.entrySet()) {
                if (!(entry.getValue() instanceof Group snap)) continue;
                out.add(snapshotFromGroup(snap, idx++));
            }
            return out;
        }
    }

    /** Read a single-snapshot file (the /PartType1 layout) into one Snapshot. */
    public Snapshot readSingle(Path file) {
        try (HdfFile hdf = new HdfFile(file)) {
            Group part = (Group) hdf.getByPath("PartType1");
            return snapshotFromPartType(part, 0L, 0.0);
        }
    }

    private Snapshot snapshotFromGroup(Group snap, int stepIdx) {
        double simTime  = readScalarDoubleAttr(snap, "Time", 0.0);
        long stepNumber = readScalarIntAttr(snap, "Step", stepIdx);

        Dataset coords = (Dataset) snap.getChild("Coordinates");
        Dataset vels   = (Dataset) snap.getChild("Velocities");
        Dataset masses = (Dataset) snap.getChild("Masses");

        return buildSnapshot(coords, vels, masses, stepNumber, simTime);
    }

    private Snapshot snapshotFromPartType(Group part, long step, double t) {
        Dataset coords = (Dataset) part.getChild("Coordinates");
        Dataset vels   = (Dataset) part.getChild("Velocities");
        Dataset masses = (Dataset) part.getChild("Masses");
        return buildSnapshot(coords, vels, masses, step, t);
    }

    private Snapshot buildSnapshot(Dataset coords, Dataset vels, Dataset masses,
                                   long step, double simTime) {
        // Coords/vels are 2D [3, N] (Fortran column-major) or [N, 3] (C row-major);
        // jHDF returns them as nested double[][] following the on-disk shape.
        Object coordRaw = coords.getData();
        Object velRaw   = vels.getData();
        double[] mass = (double[]) masses.getData();
        int n = mass.length;

        double[] x = new double[n], y = new double[n], z = new double[n];
        double[] vx = new double[n], vy = new double[n], vz = new double[n];

        if (coordRaw instanceof double[][] c2) {
            // Determine orientation from dimensions
            if (c2.length == 3 && c2[0].length == n) {
                // [3, N] — first index is xyz, second is body
                System.arraycopy(c2[0], 0, x, 0, n);
                System.arraycopy(c2[1], 0, y, 0, n);
                System.arraycopy(c2[2], 0, z, 0, n);
            } else if (c2.length == n && c2[0].length == 3) {
                // [N, 3] — first index is body, second is xyz
                for (int i = 0; i < n; i++) {
                    x[i] = c2[i][0]; y[i] = c2[i][1]; z[i] = c2[i][2];
                }
            } else {
                throw new IllegalStateException(
                        "Unexpected Coordinates shape: " + c2.length + " × " + c2[0].length);
            }
        } else {
            throw new IllegalStateException("Coordinates dataset is not double[][]");
        }
        if (velRaw instanceof double[][] v2) {
            if (v2.length == 3 && v2[0].length == n) {
                System.arraycopy(v2[0], 0, vx, 0, n);
                System.arraycopy(v2[1], 0, vy, 0, n);
                System.arraycopy(v2[2], 0, vz, 0, n);
            } else if (v2.length == n && v2[0].length == 3) {
                for (int i = 0; i < n; i++) {
                    vx[i] = v2[i][0]; vy[i] = v2[i][1]; vz[i] = v2[i][2];
                }
            } else {
                throw new IllegalStateException(
                        "Unexpected Velocities shape: " + v2.length + " × " + v2[0].length);
            }
        }

        return new Snapshot(step, simTime, x, y, z, vx, vy, vz, mass);
    }

    private double readScalarDoubleAttr(Group g, String attrName, double dflt) {
        var a = g.getAttribute(attrName);
        if (a == null) return dflt;
        Object v = a.getData();
        if (v instanceof double[] arr && arr.length > 0) return arr[0];
        if (v instanceof Double d) return d;
        return dflt;
    }

    private long readScalarIntAttr(Group g, String attrName, long dflt) {
        var a = g.getAttribute(attrName);
        if (a == null) return dflt;
        Object v = a.getData();
        if (v instanceof int[] arr && arr.length > 0) return arr[0];
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l;
        return dflt;
    }
}
