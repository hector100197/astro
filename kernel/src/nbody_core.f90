! nbody_core.f90 — Core integrators, force calculators, initial conditions
!
! Conventions:
!   - SoA layout: x(N), y(N), z(N), vx(N), vy(N), vz(N), m(N) all contiguous
!   - Hénon units (G = M_total = -4 E_total = 1)
!   - Real kind: real64 (double precision) throughout
!
! TODO milestones:
!   Sem 3: leapfrog_step, brute_force_acceleration, plummer_init
!   Sem 4: HDF5 snapshot writer integration
!   V2: hermite4_step, barnes_hut_acceleration, king_init, hernquist_init

module nbody_core
  use, intrinsic :: iso_fortran_env, only: real64, int32, int64
  implicit none
  private

  public :: leapfrog_step, brute_force_acceleration, plummer_init

  real(real64), parameter :: G = 1.0_real64

contains

  !> Leapfrog kick-drift-kick integrator step.
  !> Symplectic, second-order, time-reversible.
  subroutine leapfrog_step(x, y, z, vx, vy, vz, m, n, dt, eps)
    integer(int32), intent(in)    :: n
    real(real64),   intent(inout) :: x(n), y(n), z(n)
    real(real64),   intent(inout) :: vx(n), vy(n), vz(n)
    real(real64),   intent(in)    :: m(n)
    real(real64),   intent(in)    :: dt, eps

    real(real64), allocatable :: ax(:), ay(:), az(:)

    allocate(ax(n), ay(n), az(n))

    ! Kick (half step)
    call brute_force_acceleration(x, y, z, m, n, eps, ax, ay, az)
    vx = vx + 0.5_real64 * dt * ax
    vy = vy + 0.5_real64 * dt * ay
    vz = vz + 0.5_real64 * dt * az

    ! Drift (full step)
    x = x + dt * vx
    y = y + dt * vy
    z = z + dt * vz

    ! Kick (half step)
    call brute_force_acceleration(x, y, z, m, n, eps, ax, ay, az)
    vx = vx + 0.5_real64 * dt * ax
    vy = vy + 0.5_real64 * dt * ay
    vz = vz + 0.5_real64 * dt * az

    deallocate(ax, ay, az)
  end subroutine leapfrog_step

  !> Brute-force O(N²) gravitational acceleration with Plummer softening.
  !> Parallelized over the outer loop with OpenMP.
  subroutine brute_force_acceleration(x, y, z, m, n, eps, ax, ay, az)
    integer(int32), intent(in)  :: n
    real(real64),   intent(in)  :: x(n), y(n), z(n), m(n), eps
    real(real64),   intent(out) :: ax(n), ay(n), az(n)

    integer(int32) :: i, j
    real(real64)   :: dx, dy, dz, r2, inv_r3, eps2

    eps2 = eps * eps
    ax = 0.0_real64
    ay = 0.0_real64
    az = 0.0_real64

    !$omp parallel do private(i, j, dx, dy, dz, r2, inv_r3) schedule(static)
    do i = 1, n
      do j = 1, n
        if (i /= j) then
          dx = x(j) - x(i)
          dy = y(j) - y(i)
          dz = z(j) - z(i)
          r2 = dx*dx + dy*dy + dz*dz + eps2
          inv_r3 = 1.0_real64 / (r2 * sqrt(r2))
          ax(i) = ax(i) + G * m(j) * dx * inv_r3
          ay(i) = ay(i) + G * m(j) * dy * inv_r3
          az(i) = az(i) + G * m(j) * dz * inv_r3
        end if
      end do
    end do
    !$omp end parallel do
  end subroutine brute_force_acceleration

  !> Plummer sphere initial condition (Aarseth 2003, Section 8.2).
  !>
  !> Hénon units: G = M_total = a = 1, so the half-mass radius is fixed.
  !> Each particle is given mass 1/N. Positions are sampled from the
  !> Plummer mass profile via inverse CDF on radius:
  !>   r = 1 / sqrt(X^(-2/3) - 1),    X ~ U(0, 1).
  !> Directions are uniform on the sphere.
  !>
  !> Velocities are sampled from the Plummer distribution function
  !> f(E) ∝ (-E)^(7/2) using von Neumann acceptance-rejection on
  !> q = v / v_escape:
  !>   accept q if  q² (1 - q²)^(7/2)  >  0.1 · u,   u ~ U(0, 1)
  !> with v_escape(r) = sqrt(2 / sqrt(1 + r²)).
  !>
  !> After sampling, the system is recentred (zero CoM position and
  !> zero CoM velocity) so it sits in its own rest frame.
  subroutine plummer_init(x, y, z, vx, vy, vz, m, n, seed)
    integer(int32), intent(in)  :: n, seed
    real(real64),   intent(out) :: x(n), y(n), z(n)
    real(real64),   intent(out) :: vx(n), vy(n), vz(n)
    real(real64),   intent(out) :: m(n)

    integer(int32)              :: i, seed_size
    integer(int32), allocatable :: seed_buf(:)
    real(real64)                :: u1, u2, rad, mu, phi, sin_th
    real(real64)                :: q, g, ve, accept_u
    real(real64)                :: xcom, ycom, zcom, vxcom, vycom, vzcom
    real(real64), parameter     :: pi = 3.141592653589793238_real64

    ! Seed Fortran's intrinsic RNG with the caller-provided integer.
    ! random_seed expects an array of size = compiler-defined; fill it
    ! with the same integer everywhere — adequate for reproducibility
    ! within a single compiler/version (full bit-exact reproducibility
    ! across compilers requires a custom RNG, deferred to V2).
    call random_seed(size = seed_size)
    allocate(seed_buf(seed_size))
    seed_buf = seed
    call random_seed(put = seed_buf)
    deallocate(seed_buf)

    ! Equal-mass particles, M_total = 1.
    m = 1.0_real64 / real(n, real64)

    do i = 1, n
      ! ---- Position: inverse CDF on radius ----
      call random_number(u1)
      ! Avoid u1 = 0 which gives infinite radius.
      if (u1 < 1.0e-12_real64) u1 = 1.0e-12_real64
      rad = 1.0_real64 / sqrt(u1**(-2.0_real64/3.0_real64) - 1.0_real64)

      call random_number(u1); mu = 1.0_real64 - 2.0_real64 * u1
      call random_number(u2); phi = 2.0_real64 * pi * u2
      sin_th = sqrt(max(0.0_real64, 1.0_real64 - mu*mu))

      x(i) = rad * sin_th * cos(phi)
      y(i) = rad * sin_th * sin(phi)
      z(i) = rad * mu

      ! ---- Velocity: von Neumann rejection on q ∈ [0, 1] ----
      ve = sqrt(2.0_real64 / sqrt(1.0_real64 + rad*rad))
      do
        call random_number(q)
        call random_number(accept_u)
        g = q*q * (1.0_real64 - q*q)**3.5_real64
        if (accept_u * 0.1_real64 < g) exit
      end do

      call random_number(u1); mu = 1.0_real64 - 2.0_real64 * u1
      call random_number(u2); phi = 2.0_real64 * pi * u2
      sin_th = sqrt(max(0.0_real64, 1.0_real64 - mu*mu))

      vx(i) = q * ve * sin_th * cos(phi)
      vy(i) = q * ve * sin_th * sin(phi)
      vz(i) = q * ve * mu
    end do

    ! ---- Recentre: zero CoM position and velocity ----
    xcom  = sum(m * x)  ; ycom  = sum(m * y)  ; zcom  = sum(m * z)
    vxcom = sum(m * vx) ; vycom = sum(m * vy) ; vzcom = sum(m * vz)

    x  = x  - xcom  ; y  = y  - ycom  ; z  = z  - zcom
    vx = vx - vxcom ; vy = vy - vycom ; vz = vz - vzcom
  end subroutine plummer_init

end module nbody_core
