package mx.astro.simulation.application;

import mx.astro.simulation.domain.JobReport;
import mx.astro.simulation.domain.Snapshot;
import mx.astro.simulation.infrastructure.out.ExportJobEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the in-memory post-processing analyser against deterministic
 * synthetic snapshots — no kernel needed. Covers the four diagnostics it
 * derives: per-snapshot energetics, Lagrangian radii, binary detection,
 * and escaper detection.
 *
 * <p>Reference physics:
 * <ul>
 *   <li>Hénon units: G = M = 1; pair binding {@code E = -G m₁ m₂ / r}.</li>
 *   <li>Leapfrog is symplectic ⇒ on a stationary state the analyser must
 *       see {@code dE/E ≈ 0} and {@code dL/L ≈ 0}.</li>
 *   <li>A bound pair within the BINARY_DISTANCE_CUTOFF (0.10) shows up as a
 *       {@link JobReport.BinaryEvent}.</li>
 *   <li>An unbound body past {@code 5·r_h} is flagged as an
 *       {@link JobReport.EscaperEvent} (first-time only).</li>
 * </ul>
 */
class InMemoryJobReportAnalyserTest {

    private final InMemoryJobReportAnalyser analyser = new InMemoryJobReportAnalyser();

    // ================================================================
    // Smoke / contract
    // ================================================================

    @Test
    @DisplayName("Empty snapshot list throws IllegalArgumentException")
    void emptySnapshots_throws() {
        ExportJobEntity job = jobEntity(10);
        assertThrows(IllegalArgumentException.class,
                () -> analyser.analyse(job, List.of()));
    }

    @Test
    @DisplayName("Single-snapshot input yields timeline_n=1, no binaries/escapers, zero drift")
    void singleSnapshot_minimalReport() {
        Snapshot s = uniformCubeSnapshot(/*n*/ 10, /*step*/ 0, /*time*/ 0.0);
        ExportJobEntity job = jobEntity(10);
        JobReport report = analyser.analyse(job, List.of(s));

        assertEquals(1, report.timeline().size());
        assertEquals(0, report.binaries().size());
        assertEquals(0, report.escapers().size());
        assertEquals(0.0, report.conservation().get("dE_over_E_initial"));
        assertEquals(0.0, report.conservation().get("dL_over_L_initial"));
    }

    // ================================================================
    // Energy & momentum conservation
    // ================================================================

    @Test
    @DisplayName("Identical snapshots → dE/E and dL/L are exactly 0")
    void identicalSnapshots_perfectConservation() {
        Snapshot s = uniformCubeSnapshot(/*n*/ 20, /*step*/ 0, /*time*/ 0.0);
        Snapshot s2 = new Snapshot(100L, 0.5, s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(), s.mass());
        Snapshot s3 = new Snapshot(200L, 1.0, s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(), s.mass());

        ExportJobEntity job = jobEntity(20);
        JobReport report = analyser.analyse(job, List.of(s, s2, s3));

        assertEquals(0.0, report.conservation().get("dE_over_E_initial"), 1e-12);
        assertEquals(0.0, report.conservation().get("dL_over_L_initial"), 1e-12);
        assertEquals(0.0, report.conservation().get("worst_dE_over_E"), 1e-12);
    }

    @Test
    @DisplayName("Energy = K + U; virial Q reported per snapshot")
    void energeticsArePopulated() {
        Snapshot s = twoBodyCircular();
        ExportJobEntity job = jobEntity(2);
        JobReport r = analyser.analyse(job, List.of(s));

        var pt = r.timeline().get(0);
        assertNotEquals(0.0, pt.K(),  "K must be > 0 for moving particles");
        assertTrue(pt.U() < 0,        "U must be < 0 for bound pair, was " + pt.U());
        assertEquals(pt.K() + pt.U(), pt.E(), 1e-12,
                "E must equal K + U bit-for-bit");
        assertTrue(Double.isFinite(pt.Q()), "Q must be finite for bound state");
    }

    // ================================================================
    // Binary detection
    // ================================================================

    @Test
    @DisplayName("Two equal masses on a tight circular orbit are detected as a binary")
    void twoBodyCircular_detectedAsBinary() {
        Snapshot s = twoBodyCircular();
        ExportJobEntity job = jobEntity(2);
        JobReport r = analyser.analyse(job, List.of(s));

        assertEquals(1, r.binaries().size(), "expected exactly one bound pair");
        var b = r.binaries().get(0);
        assertEquals(0, b.bodyA());
        assertEquals(1, b.bodyB());
        assertTrue(b.bindingEnergy() < 0, "binding energy must be negative for a bound pair");
        assertTrue(b.eccentricity() < 0.1,
                "circular orbit should have e ≈ 0, was " + b.eccentricity());
        assertTrue(b.separation() > 0 && b.separation() <= 0.10,
                "separation should be within the binary cutoff");
    }

    @Test
    @DisplayName("Unbound (hyperbolic) pair is NOT detected as a binary even if close")
    void hyperbolicPair_notBinary() {
        // Two masses very close (within cutoff) but moving apart fast enough to be unbound.
        double r = 0.05;
        double[] x  = {  r/2, -r/2 };
        double[] y  = {    0,    0 };
        double[] z  = {    0,    0 };
        double[] vx = {  5.0, -5.0 };  // 10× the circular speed → hyperbolic
        double[] vy = {    0,    0 };
        double[] vz = {    0,    0 };
        double[] m  = {  0.5,  0.5 };
        Snapshot s = new Snapshot(0, 0.0, x, y, z, vx, vy, vz, m);

        ExportJobEntity job = jobEntity(2);
        JobReport report = analyser.analyse(job, List.of(s));

        assertEquals(0, report.binaries().size(),
                "hyperbolic encounter must not be classified as a binary");
    }

    @Test
    @DisplayName("Pair separation above BINARY_DISTANCE_CUTOFF (0.10) is skipped early")
    void wideSeparation_notBinary() {
        // Bound (slow) pair but with separation 0.5 — outside the cutoff.
        double r = 0.5;
        double[] x  = {  r/2, -r/2 };
        double[] y  = {    0,    0 };
        double[] z  = {    0,    0 };
        // Use circular-orbit velocity for separation 0.5: v = sqrt(G(m1+m2)/(4r)) = 0.5.
        double[] vx = {    0,    0 };
        double[] vy = { 0.5, -0.5 };
        double[] vz = {    0,    0 };
        double[] m  = {  0.5,  0.5 };
        Snapshot s = new Snapshot(0, 0.0, x, y, z, vx, vy, vz, m);

        ExportJobEntity job = jobEntity(2);
        JobReport report = analyser.analyse(job, List.of(s));

        assertEquals(0, report.binaries().size(),
                "pair beyond cutoff distance should not be considered");
    }

    // ================================================================
    // Lagrangian radii
    // ================================================================

    @Test
    @DisplayName("Ordered r10 ≤ r50 ≤ r90 on a non-trivial mass distribution")
    void lagrangianRadii_areOrdered() {
        // 100 particles uniformly distributed in a sphere of radius 1.
        Snapshot s = uniformBallSnapshot(100, /*radius*/ 1.0, /*seed*/ 7L);

        ExportJobEntity job = jobEntity(100);
        JobReport r = analyser.analyse(job, List.of(s));
        var pt = r.timeline().get(0);

        assertTrue(pt.r10() <= pt.r50(), "r10 must be ≤ r50, got " + pt.r10() + " > " + pt.r50());
        assertTrue(pt.r50() <= pt.r90(), "r50 must be ≤ r90, got " + pt.r50() + " > " + pt.r90());
        assertTrue(pt.r10() > 0,         "r10 must be > 0 for non-collapsed cluster");
        assertTrue(pt.r90() < 1.1,       "r90 must stay near the sphere radius, got " + pt.r90());
    }

    // ================================================================
    // Escaper detection
    // ================================================================

    @Test
    @DisplayName("Body at r > 5·r_h with positive total energy is flagged as escaper")
    void unboundFarBody_flaggedAsEscaper() {
        // Tight 19-body core at origin + 1 body far away moving outward.
        // r_h of the core is ~0.5; we put body 19 at r = 10 (≫ 5·r_h).
        Snapshot s = coreWithEscaper(/*coreN*/ 19, /*coreR*/ 0.5, /*escR*/ 10.0, /*escV*/ 2.0);

        ExportJobEntity job = jobEntity(20);
        JobReport r = analyser.analyse(job, List.of(s));

        assertEquals(1, r.escapers().size(), "expected exactly one escaper");
        var e = r.escapers().get(0);
        assertEquals(19, e.bodyIndex(), "the far body should be the escaper");
        assertTrue(e.escapeRadius() > 5.0 * 0.5,
                "escape radius must be beyond 5·r_h, was " + e.escapeRadius());
        assertTrue(e.escapeEnergy() > 0,
                "escape energy must be positive (unbound), was " + e.escapeEnergy());
    }

    @Test
    @DisplayName("Escaper is reported only at first-time-unbound (no duplicates across snapshots)")
    void escaper_reportedOnce() {
        // Same configuration replayed in two snapshots — body 19 stays far + unbound.
        Snapshot s1 = coreWithEscaper(19, 0.5, 10.0, 2.0);
        Snapshot s2 = new Snapshot(1, 1.0,
                s1.x(), s1.y(), s1.z(), s1.vx(), s1.vy(), s1.vz(), s1.mass());

        ExportJobEntity job = jobEntity(20);
        JobReport r = analyser.analyse(job, List.of(s1, s2));

        assertEquals(1, r.escapers().size(),
                "escaper must be reported only on its first detection");
    }

    @Test
    @DisplayName("Bound body near the core is not an escaper (negative energy)")
    void boundBody_notEscaper() {
        Snapshot s = uniformBallSnapshot(20, 0.5, 13L);
        ExportJobEntity job = jobEntity(20);
        JobReport r = analyser.analyse(job, List.of(s));

        assertEquals(0, r.escapers().size(),
                "tightly bound cluster should yield no escapers");
    }

    // ================================================================
    // Fixtures
    // ================================================================

    /**
     * Builds a {@link Snapshot} with a tight two-body bound pair in the xy-plane
     * (separation 0.05, equal masses 0.5, circular orbit).
     */
    private static Snapshot twoBodyCircular() {
        double R = 0.025;   // body radius from CoM → separation 2R = 0.05 < cutoff 0.10
        double mass = 0.5;
        // Two equal masses m at ±R: F_grav = G·m²/(2R)², F_cent = m·v²/R.
        // Setting them equal gives v = √(G·m/(4R)).
        // With G=1, m=0.5, R=0.025 → v = √(0.5/0.1) = √5 ≈ 2.236.
        double v = Math.sqrt(mass / (4.0 * R));
        return new Snapshot(0, 0.0,
                new double[]{ R, -R },
                new double[]{ 0,  0 },
                new double[]{ 0,  0 },
                new double[]{ 0,  0 },
                new double[]{ v, -v },
                new double[]{ 0,  0 },
                new double[]{ mass, mass });
    }

    /**
     * N particles on a tight cubic grid around the origin, with small thermal
     * velocities (energy is non-trivial, system is approximately at rest).
     */
    private static Snapshot uniformCubeSnapshot(int n, long step, double time) {
        double[] x  = new double[n], y  = new double[n], z  = new double[n];
        double[] vx = new double[n], vy = new double[n], vz = new double[n];
        double[] m  = new double[n];
        int side = (int) Math.ceil(Math.cbrt(n));
        // Spacing 0.5 keeps every pair beyond BINARY_DISTANCE_CUTOFF (0.10), so
        // no accidental binaries are detected on a "blank" configuration.
        double spacing = 0.5;
        for (int i = 0; i < n; i++) {
            int ix = i % side, iy = (i / side) % side, iz = i / (side * side);
            x[i] = (ix - side / 2.0) * spacing;
            y[i] = (iy - side / 2.0) * spacing;
            z[i] = (iz - side / 2.0) * spacing;
            vx[i] = vy[i] = vz[i] = 0.0;
            m[i] = 1.0 / n;
        }
        return new Snapshot(step, time, x, y, z, vx, vy, vz, m);
    }

    /**
     * {@code n} particles uniformly distributed in a ball of given radius, with
     * thermal velocities sized so the cluster is bound (Q ≈ 1).
     */
    private static Snapshot uniformBallSnapshot(int n, double radius, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        double[] x  = new double[n], y  = new double[n], z  = new double[n];
        double[] vx = new double[n], vy = new double[n], vz = new double[n];
        double[] m  = new double[n];

        for (int i = 0; i < n; i++) {
            // Rejection sample uniform-in-volume.
            double rx, ry, rz;
            do {
                rx = rng.nextDouble() * 2 - 1;
                ry = rng.nextDouble() * 2 - 1;
                rz = rng.nextDouble() * 2 - 1;
            } while (rx * rx + ry * ry + rz * rz > 1.0);
            x[i] = rx * radius;
            y[i] = ry * radius;
            z[i] = rz * radius;
            // Thermal speeds — Maxwell-Boltzmann-ish. Small enough to stay bound for the
            // smoke tests; we only care about energy sign, not dynamics.
            double sigma = 0.1;
            vx[i] = rng.nextGaussian() * sigma;
            vy[i] = rng.nextGaussian() * sigma;
            vz[i] = rng.nextGaussian() * sigma;
            m[i]  = 1.0 / n;
        }
        return new Snapshot(0, 0.0, x, y, z, vx, vy, vz, m);
    }

    /**
     * A small tight core at the origin plus one body far away moving outward at high speed.
     * The far body has total energy > 0 and r ≫ 5·r_h ⇒ should be flagged as escaper.
     */
    private static Snapshot coreWithEscaper(int coreN, double coreR, double escR, double escV) {
        int n = coreN + 1;
        double[] x  = new double[n], y  = new double[n], z  = new double[n];
        double[] vx = new double[n], vy = new double[n], vz = new double[n];
        double[] m  = new double[n];

        java.util.Random rng = new java.util.Random(123);
        for (int i = 0; i < coreN; i++) {
            // Tight core: small radius, near-zero velocity.
            double th = rng.nextDouble() * 2 * Math.PI;
            double ph = Math.acos(2 * rng.nextDouble() - 1);
            double rad = coreR * Math.cbrt(rng.nextDouble());
            x[i] = rad * Math.sin(ph) * Math.cos(th);
            y[i] = rad * Math.sin(ph) * Math.sin(th);
            z[i] = rad * Math.cos(ph);
            vx[i] = rng.nextGaussian() * 0.01;
            vy[i] = rng.nextGaussian() * 0.01;
            vz[i] = rng.nextGaussian() * 0.01;
            m[i] = 1.0 / n;
        }
        // The escaper.
        x[coreN]  = escR;
        y[coreN]  = 0; z[coreN]  = 0;
        vx[coreN] = escV;
        vy[coreN] = 0; vz[coreN] = 0;
        m[coreN]  = 1.0 / n;
        return new Snapshot(0, 0.0, x, y, z, vx, vy, vz, m);
    }

    /**
     * Test jobs use a very small softening (1e-4) instead of the production default
     * (1e-2). Softening artificially inflates pair distances, which on tight pairs
     * (r ~ 0.05) leaks into a spurious apparent eccentricity of ~0.2 even for an
     * exactly circular orbit. With a softer eps the geometric quantities (a, e)
     * are dominated by the actual dynamics, which is what these tests want to assert.
     */
    private static ExportJobEntity jobEntity(int nBodies) {
        return jobEntity(nBodies, 1e-4);
    }

    private static ExportJobEntity jobEntity(int nBodies, double softening) {
        return new ExportJobEntity(
                UUID.randomUUID(), Instant.now(), "completed",
                "synthetic", nBodies, 100, 0.005, softening, 42L, 50);
    }

    private static List<Snapshot> dup(Snapshot s, int copies) {
        List<Snapshot> out = new ArrayList<>(copies);
        for (int i = 0; i < copies; i++) {
            out.add(new Snapshot(i, i * 0.5,
                    s.x(), s.y(), s.z(), s.vx(), s.vy(), s.vz(), s.mass()));
        }
        return out;
    }
}
