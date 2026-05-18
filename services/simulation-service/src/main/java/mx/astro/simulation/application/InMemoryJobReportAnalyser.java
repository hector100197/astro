package mx.astro.simulation.application;

import mx.astro.simulation.domain.JobReport;
import mx.astro.simulation.domain.JobReport.BinaryEvent;
import mx.astro.simulation.domain.JobReport.EscaperEvent;
import mx.astro.simulation.domain.JobReport.SnapshotPoint;
import mx.astro.simulation.domain.Snapshot;
import mx.astro.simulation.infrastructure.out.ExportJobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the post-processing report from snapshots that are still in JVM
 * memory at the end of an integration run.
 *
 * <p>This avoids the libhdf5 conflict that would arise if we tried to read
 * the HDF5 file back from another process (or even another libhdf5 instance
 * in the same JVM): the kernel's libhdf5 holds the file in a state that is
 * unreadable by anything else for the entire JVM lifetime. Working in-memory
 * sidesteps the issue entirely.
 *
 * <p>Binary detection is O(N²) per snapshot, gated by a distance cutoff so
 * the actual pair-energy work is small in practice. For N=3000 with 100
 * snapshots: ~few seconds.
 */
@Service
@Profile("!mock")
public class InMemoryJobReportAnalyser {

    private static final Logger log = LoggerFactory.getLogger(InMemoryJobReportAnalyser.class);

    private static final double G = 1.0;
    /** Distance above which a pair is too loose to be a binary. */
    private static final double BINARY_DISTANCE_CUTOFF = 0.10;
    /** Bodies past 5 r_h that are also unbound (E_i > 0) are escapers. */
    private static final double ESCAPER_RADIUS_FACTOR = 5.0;

    /** Build a JobReport from already-collected snapshots. The list must be
     *  in time order; it is not modified. */
    public JobReport analyse(ExportJobEntity job, List<Snapshot> snaps) {
        log.info("Analysing job {} in-memory: {} snapshots, N={}",
                job.getId(), snaps.size(), job.getNBodies());
        if (snaps.isEmpty()) throw new IllegalArgumentException("no snapshots to analyse");

        List<SnapshotPoint> timeline = new ArrayList<>(snaps.size());
        List<BinaryEvent> binaries = new ArrayList<>();
        List<EscaperEvent> escapers = new ArrayList<>();
        boolean[] alreadyEscaped = new boolean[job.getNBodies()];
        double eps2 = job.getSoftening() * job.getSoftening();

        double E0 = Double.NaN, L0 = Double.NaN, worstE = 0.0;

        for (Snapshot s : snaps) {
            EnergyResult er = computeEnergetics(s, eps2);
            double[] lag = lagrangianRadii(s);

            List<BinaryEvent> snapBin = detectBinaries(s, eps2, er.kineticPerBody);
            binaries.addAll(snapBin);

            int newEscapers = 0;
            double rh = lag[1] > 0 ? lag[1] : 1.0;
            for (int i = 0; i < s.n(); i++) {
                if (alreadyEscaped[i]) continue;
                if (er.energyPerBody[i] <= 0) continue;
                double dx = s.x()[i] - er.cx, dy = s.y()[i] - er.cy, dz = s.z()[i] - er.cz;
                double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (r < ESCAPER_RADIUS_FACTOR * rh) continue;
                double speed = Math.sqrt(s.vx()[i]*s.vx()[i] + s.vy()[i]*s.vy()[i] + s.vz()[i]*s.vz()[i]);
                escapers.add(new EscaperEvent(
                        i, s.time(), s.stepIndex(), r, speed, er.energyPerBody[i]));
                alreadyEscaped[i] = true;
                newEscapers++;
            }

            timeline.add(new SnapshotPoint(
                    s.time(), s.stepIndex(),
                    er.K, er.U, er.E,
                    new double[]{er.Px, er.Py, er.Pz},
                    new double[]{er.Lx, er.Ly, er.Lz},
                    er.virial,
                    lag[0], lag[1], lag[2],
                    snapBin.size(), newEscapers));

            if (Double.isNaN(E0)) {
                E0 = er.E;
                L0 = Math.sqrt(er.Lx*er.Lx + er.Ly*er.Ly + er.Lz*er.Lz);
            } else if (Math.abs(E0) > 1e-12) {
                double rel = Math.abs((er.E - E0) / E0);
                if (rel > worstE) worstE = rel;
            }
        }

        SnapshotPoint last = timeline.get(timeline.size() - 1);
        double Lf = Math.sqrt(last.L()[0]*last.L()[0] + last.L()[1]*last.L()[1] + last.L()[2]*last.L()[2]);
        Map<String, Double> cons = new LinkedHashMap<>();
        cons.put("dE_over_E_initial", Math.abs(E0) > 0 ? Math.abs((last.E() - E0) / E0) : 0.0);
        cons.put("dL_over_L_initial", L0 > 0 ? Math.abs((Lf - L0) / L0) : 0.0);
        cons.put("worst_dE_over_E", worstE);

        log.info("Job {}: timeline={}, binaries={}, escapers={}, dE/E={}",
                job.getId(), timeline.size(), binaries.size(), escapers.size(),
                String.format("%.3e", cons.get("dE_over_E_initial")));

        return new JobReport(
                job.getId().toString(),
                job.getScenarioName(),
                job.getNBodies(),
                job.getNSteps(),
                job.getDt(),
                job.getSoftening(),
                job.getSeed(),
                timeline, binaries, escapers, cons);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static final class EnergyResult {
        double K, U, E, virial;
        double Px, Py, Pz, Lx, Ly, Lz;
        double cx, cy, cz;
        double[] energyPerBody;
        double kineticPerBody;
    }

    private EnergyResult computeEnergetics(Snapshot s, double eps2) {
        final int n = s.n();
        final double[] x = s.x(), y = s.y(), z = s.z();
        final double[] vx = s.vx(), vy = s.vy(), vz = s.vz();
        final double[] m = s.mass();

        double K = 0, Px = 0, Py = 0, Pz = 0, Lx = 0, Ly = 0, Lz = 0;
        double cx = 0, cy = 0, cz = 0, M = 0;
        double[] kPerBody = new double[n];

        for (int i = 0; i < n; i++) {
            double v2 = vx[i]*vx[i] + vy[i]*vy[i] + vz[i]*vz[i];
            double ki = 0.5 * m[i] * v2;
            kPerBody[i] = ki;
            K  += ki;
            Px += m[i] * vx[i]; Py += m[i] * vy[i]; Pz += m[i] * vz[i];
            Lx += m[i] * (y[i]*vz[i] - z[i]*vy[i]);
            Ly += m[i] * (z[i]*vx[i] - x[i]*vz[i]);
            Lz += m[i] * (x[i]*vy[i] - y[i]*vx[i]);
            cx += m[i] * x[i]; cy += m[i] * y[i]; cz += m[i] * z[i];
            M  += m[i];
        }
        if (M > 0) { cx /= M; cy /= M; cz /= M; }

        double U = 0;
        double[] uPerBody = new double[n];
        for (int i = 0; i < n; i++) {
            double xi = x[i], yi = y[i], zi = z[i], mi = m[i];
            for (int j = i + 1; j < n; j++) {
                double dx = xi - x[j], dy = yi - y[j], dz = zi - z[j];
                double r = Math.sqrt(dx*dx + dy*dy + dz*dz + eps2);
                double pe = -G * mi * m[j] / r;
                U += pe;
                uPerBody[i] += pe;
                uPerBody[j] += pe;
            }
        }
        double[] ePerBody = new double[n];
        for (int i = 0; i < n; i++) ePerBody[i] = kPerBody[i] + uPerBody[i];

        EnergyResult r = new EnergyResult();
        r.K = K; r.U = U; r.E = K + U;
        r.virial = (U != 0) ? -2.0 * K / U : Double.NaN;
        r.Px = Px; r.Py = Py; r.Pz = Pz;
        r.Lx = Lx; r.Ly = Ly; r.Lz = Lz;
        r.cx = cx; r.cy = cy; r.cz = cz;
        r.energyPerBody = ePerBody;
        r.kineticPerBody = n > 0 ? K / n : 0;
        return r;
    }

    private double[] lagrangianRadii(Snapshot s) {
        int n = s.n();
        double cx = 0, cy = 0, cz = 0, M = 0;
        for (int i = 0; i < n; i++) {
            cx += s.mass()[i] * s.x()[i];
            cy += s.mass()[i] * s.y()[i];
            cz += s.mass()[i] * s.z()[i];
            M  += s.mass()[i];
        }
        if (M <= 0) return new double[]{0, 0, 0};
        cx /= M; cy /= M; cz /= M;
        double[][] pairs = new double[n][2];
        for (int i = 0; i < n; i++) {
            double dx = s.x()[i] - cx, dy = s.y()[i] - cy, dz = s.z()[i] - cz;
            pairs[i][0] = Math.sqrt(dx*dx + dy*dy + dz*dz);
            pairs[i][1] = s.mass()[i];
        }
        java.util.Arrays.sort(pairs, (a, b) -> Double.compare(a[0], b[0]));
        double t10 = 0.10 * M, t50 = 0.50 * M, t90 = 0.90 * M;
        double cum = 0, r10 = 0, r50 = 0, r90 = 0;
        boolean got10 = false, got50 = false;
        for (int i = 0; i < n; i++) {
            cum += pairs[i][1];
            if (!got10 && cum >= t10) { r10 = pairs[i][0]; got10 = true; }
            if (!got50 && cum >= t50) { r50 = pairs[i][0]; got50 = true; }
            if (cum >= t90) { r90 = pairs[i][0]; break; }
        }
        return new double[]{r10, r50, r90};
    }

    private List<BinaryEvent> detectBinaries(Snapshot s, double eps2, double meanK) {
        int n = s.n();
        List<BinaryEvent> out = new ArrayList<>();
        double cutoffSq = BINARY_DISTANCE_CUTOFF * BINARY_DISTANCE_CUTOFF;
        for (int i = 0; i < n; i++) {
            double xi = s.x()[i], yi = s.y()[i], zi = s.z()[i], mi = s.mass()[i];
            double vxi = s.vx()[i], vyi = s.vy()[i], vzi = s.vz()[i];
            for (int j = i + 1; j < n; j++) {
                double dx = xi - s.x()[j], dy = yi - s.y()[j], dz = zi - s.z()[j];
                double r2 = dx*dx + dy*dy + dz*dz;
                if (r2 > cutoffSq) continue;
                double mj = s.mass()[j];
                double mu = (mi * mj) / (mi + mj);
                double dvx = vxi - s.vx()[j], dvy = vyi - s.vy()[j], dvz = vzi - s.vz()[j];
                double v2 = dvx*dvx + dvy*dvy + dvz*dvz;
                double r = Math.sqrt(r2 + eps2);
                double Epair = 0.5 * mu * v2 - G * mi * mj / r;
                if (Epair >= 0) continue;

                double a = -G * mi * mj / (2.0 * Epair);
                double Lx = (yi - s.y()[j]) * dvz - (zi - s.z()[j]) * dvy;
                double Ly = (zi - s.z()[j]) * dvx - (xi - s.x()[j]) * dvz;
                double Lz = (xi - s.x()[j]) * dvy - (yi - s.y()[j]) * dvx;
                double L2 = Lx*Lx + Ly*Ly + Lz*Lz;
                double mTot = mi + mj;
                double e = Math.sqrt(Math.max(0,
                        1.0 + (2.0 * (Epair / mu) * L2) / (G * G * mTot * mTot)));
                double period = 2.0 * Math.PI * Math.sqrt(Math.pow(Math.abs(a), 3) / (G * mTot));
                boolean hard = Math.abs(Epair) > meanK;

                out.add(new BinaryEvent(
                        s.time(), s.stepIndex(), i, j,
                        Math.sqrt(r2), a, e, period, Epair, hard));
            }
        }
        return out;
    }
}
