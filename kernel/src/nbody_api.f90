! nbody_api.f90 — C-bindings for Java FFM and Python f2py.
!
! Exposes:
!   nbody_step(...)               — single leapfrog step
!   nbody_init_plummer(...)       — generate Plummer initial conditions
!   nbody_write_snapshot_h5(...)  — write a snapshot to HDF5 at the given path
!
! All routines use ISO_C_BINDING for predictable ABI.

module nbody_api
  use, intrinsic :: iso_c_binding
  use, intrinsic :: iso_fortran_env, only: real64, int32
  use nbody_core, only: leapfrog_step, brute_force_acceleration, plummer_init
  use nbody_io,   only: write_snapshot_file, open_run, append_snapshot, close_run
  implicit none
  private

  public :: c_nbody_step, c_nbody_init_plummer, c_nbody_write_snapshot_h5
  public :: c_nbody_open_run, c_nbody_append_snapshot, c_nbody_close_run

contains

  subroutine c_nbody_step(x, y, z, vx, vy, vz, m, n, dt, eps) bind(C, name="nbody_step")
    integer(c_int), intent(in), value :: n
    real(c_double), intent(in), value :: dt, eps
    real(c_double), intent(inout)     :: x(n), y(n), z(n)
    real(c_double), intent(inout)     :: vx(n), vy(n), vz(n)
    real(c_double), intent(in)        :: m(n)

    call leapfrog_step(x, y, z, vx, vy, vz, m, n, dt, eps)
  end subroutine c_nbody_step

  subroutine c_nbody_init_plummer(x, y, z, vx, vy, vz, m, n, seed) bind(C, name="nbody_init_plummer")
    integer(c_int), intent(in), value :: n, seed
    real(c_double), intent(out)       :: x(n), y(n), z(n)
    real(c_double), intent(out)       :: vx(n), vy(n), vz(n)
    real(c_double), intent(out)       :: m(n)

    call plummer_init(x, y, z, vx, vy, vz, m, n, seed)
  end subroutine c_nbody_init_plummer

  !> C-callable wrapper for {@code write_snapshot_file}. Path is a NUL-terminated
  !> C string; we convert it to a Fortran character variable on entry.
  !>
  !> Returns 0 on success, non-zero on HDF5 failure.
  function c_nbody_write_snapshot_h5(c_path, x, y, z, vx, vy, vz, m, n, sim_time) &
           bind(C, name="nbody_write_snapshot_h5") result(status)
    character(kind=c_char), dimension(*), intent(in) :: c_path
    integer(c_int), intent(in), value                :: n
    real(c_double), intent(in), value                :: sim_time
    real(c_double), intent(in)                       :: x(n), y(n), z(n)
    real(c_double), intent(in)                       :: vx(n), vy(n), vz(n)
    real(c_double), intent(in)                       :: m(n)
    integer(c_int) :: status

    character(len=:), allocatable :: f_path
    integer :: i, plen

    ! Find NUL terminator
    plen = 0
    do i = 1, 4096
      if (c_path(i) == c_null_char) exit
      plen = plen + 1
    end do
    allocate(character(len=plen) :: f_path)
    do i = 1, plen
      f_path(i:i) = c_path(i)
    end do

    status = write_snapshot_file(f_path, x, y, z, vx, vy, vz, m, n, sim_time)
    deallocate(f_path)
  end function c_nbody_write_snapshot_h5

  !> Open a multi-snapshot HDF5 run. Returns 0 on success, non-zero on error.
  !> Caller must close before opening a new one — only one run may be open
  !> at a time per shared library instance (per JVM).
  function c_nbody_open_run(c_path, n) bind(C, name="nbody_open_run") result(status)
    character(kind=c_char), dimension(*), intent(in) :: c_path
    integer(c_int), intent(in), value                :: n
    integer(c_int) :: status

    character(len=:), allocatable :: f_path
    integer :: i, plen

    plen = 0
    do i = 1, 4096
      if (c_path(i) == c_null_char) exit
      plen = plen + 1
    end do
    allocate(character(len=plen) :: f_path)
    do i = 1, plen
      f_path(i:i) = c_path(i)
    end do

    status = open_run(f_path, n)
    deallocate(f_path)
  end function c_nbody_open_run

  !> Append one snapshot to the currently-open run.
  function c_nbody_append_snapshot(snapshot_idx, x, y, z, vx, vy, vz, m, n, sim_time) &
           bind(C, name="nbody_append_snapshot") result(status)
    integer(c_int), intent(in), value :: snapshot_idx, n
    real(c_double), intent(in), value :: sim_time
    real(c_double), intent(in)        :: x(n), y(n), z(n)
    real(c_double), intent(in)        :: vx(n), vy(n), vz(n)
    real(c_double), intent(in)        :: m(n)
    integer(c_int) :: status

    status = append_snapshot(snapshot_idx, x, y, z, vx, vy, vz, m, n, sim_time)
  end function c_nbody_append_snapshot

  !> Close the currently-open run. Idempotent (no-op if nothing open).
  function c_nbody_close_run() bind(C, name="nbody_close_run") result(status)
    integer(c_int) :: status
    status = close_run()
  end function c_nbody_close_run

end module nbody_api
