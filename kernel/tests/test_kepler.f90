! test_kepler.f90 — Two-body Kepler problem validation
!
! Sets up a circular orbit of period 2π in Hénon-like units,
! integrates for 100 periods, asserts that the period drift is < 0.01%.
!
! TODO Sem 3: implement using pFUnit framework.

program test_kepler
  use, intrinsic :: iso_fortran_env, only: real64, int32, output_unit
  use nbody_core, only: leapfrog_step
  implicit none

  ! Two-body setup:
  !   m1 = m2 = 0.5, separation r = 1, circular orbit, period T = 2π / ω
  ! TODO: write the setup and the assertion.

  write(output_unit, '(A)') "TODO: Kepler test stub. Implement in Sem 3."
end program test_kepler
