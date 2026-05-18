# Physics and validation

## The N-body problem

For N point masses interacting via Newtonian gravity, each body $i$ obeys:

$$
\ddot{\mathbf{r}}_i = -G \sum_{j \neq i} \frac{m_j (\mathbf{r}_i - \mathbf{r}_j)}{|\mathbf{r}_i - \mathbf{r}_j|^3 + \varepsilon^2}^{3/2}
$$

where $\varepsilon$ is the **Plummer softening length**, used to regularize close encounters that would otherwise diverge.

## Unit system: Hénon

Internally we use Hénon (a.k.a. *N-body*) units:

$$
G = M_{\text{total}} = -4E_{\text{total}} = 1
$$

This sets the natural time scale to the half-mass crossing time. Conversion to astronomical units (Msun, parsec, Myr) is performed at the UI / I/O layer only. The kernel itself never touches astronomical units.

## Integrator: leapfrog (kick-drift-kick form)

Symplectic, second-order, time-reversible. The canonical choice for N-body. For each step of size Δt:

```
1. v_{i,1/2} = v_{i,0} + (Δt/2) · a_{i,0}      # half kick
2. r_{i,1}   = r_{i,0} + Δt · v_{i,1/2}        # full drift
3. compute a_{i,1} from new positions
4. v_{i,1}   = v_{i,1/2} + (Δt/2) · a_{i,1}    # half kick
```

This is the kick-drift-kick variant. Equivalent to drift-kick-drift up to half-step phase shift.

**Why not Euler or RK4?** Euler is non-symplectic and energy diverges quickly. RK4 is non-symplectic too — it conserves energy to high order short-term but drifts secularly over long integrations. Leapfrog has bounded energy error (oscillates around the true value, never diverges).

## Force calculation: brute force O(N²)

For N ≤ 50 000 on modern multi-core CPUs, direct summation with OpenMP parallelization over the outer loop is competitive:

```fortran
!$omp parallel do reduction(+:ax, ay, az)
do i = 1, N
  do j = 1, N
    if (i /= j) then
      dx = x(j) - x(i); dy = y(j) - y(i); dz = z(j) - z(i)
      r2 = dx*dx + dy*dy + dz*dz + eps2
      inv_r3 = 1.0_real64 / (r2 * sqrt(r2))
      ax(i) = ax(i) + m(j) * dx * inv_r3
      ay(i) = ay(i) + m(j) * dy * inv_r3
      az(i) = az(i) + m(j) * dz * inv_r3
    end if
  end do
end do
```

For N > 10⁴, Barnes-Hut O(N log N) becomes attractive (V2).

## Initial conditions

### Plummer sphere (default)

Density profile:

$$
\rho(r) = \frac{3 M}{4\pi a^3} \left(1 + \frac{r^2}{a^2}\right)^{-5/2}
$$

with `a = 1` (Plummer scale length) in Hénon units. Sampled via inversion of the cumulative mass function.

Velocities sampled from the local Maxwell-Boltzmann distribution that satisfies hydrostatic equilibrium.

### Other (V2)

- King model (W₀ parameter)
- Hernquist sphere
- Imported from Gaia DR3 (real cluster snapshot)

## Validation tests (CI-enforced from day 1)

| # | Test                           | Tolerance            | Status |
|---|--------------------------------|----------------------|--------|
| 1 | Kepler 2-body period           | 0.01% over 100 orbits | TODO |
| 2 | Energy conservation (Plummer N=100) | drift < 0.1% over 10⁴ steps | TODO |
| 3 | Linear momentum conservation   | < 10⁻¹⁰ (machine ε)  | TODO |
| 4 | Angular momentum conservation  | < 10⁻¹⁰              | TODO |
| 5 | Virial theorem (relaxed Plummer) | \|2K+U\|/\|U\| < 0.05 | TODO |

V2 aspirational: comparison against NBODY6 on a small reference cluster.

## Diagnostics emitted per snapshot

- Total kinetic energy K
- Total potential energy U
- Total energy E = K + U (drift relative to initial value)
- Total linear momentum P = Σ m_i v_i
- Total angular momentum L = Σ r_i × m_i v_i
- Virial ratio Q = 2K / |U|

These flow to the validation panel in the frontend in real time and are persisted to PostgreSQL `validation_metrics` and HDF5 `/Validation/`.

## References

- Aarseth, S. J. (2003). *Gravitational N-Body Simulations*. Cambridge University Press. [The reference text.]
- Hénon, M. (1971). "Numerical experiments on the stability of spherical stellar systems." *Astron. Astrophys.* **24**, 229.
- Springel, V. (2005). "The cosmological simulation code GADGET-2." *MNRAS* **364**, 1105.
- Plummer, H. C. (1911). "On the problem of distribution in globular star clusters." *MNRAS* **71**, 460.
- Hairer, E., Lubich, C., Wanner, G. (2006). *Geometric Numerical Integration* (2nd ed.). Springer. [Symplectic integrators.]
