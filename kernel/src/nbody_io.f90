! nbody_io.f90 — HDF5 snapshot writer following GADGET-like layout.
!
! Two write modes:
!
!   1. SINGLE-SHOT  — write_snapshot_file(...): one HDF5 file per snapshot.
!      Used by the live mode at stream stop (final state).
!
!   2. MULTI-SNAPSHOT — open_run / append_snapshot / close_run trio:
!      one HDF5 file holding ALL timesteps of a run, written incrementally
!      as the integrator advances. Used by headless / batch mode.
!
! Multi-snapshot HDF5 layout:
!
!   /Header
!     @NumPart, @Format = "astro/v1-multi"
!   /Snapshots
!     /00000 group  (Coords N×3, Velocities N×3, Masses N, @Time, @Step)
!     /00001 group  (...)
!     ...
!
! All routines return 0 on success, non-zero on HDF5 failure.

module nbody_io
  use, intrinsic :: iso_fortran_env, only: real64, int32, int64
  use hdf5
  implicit none
  private

  public :: write_snapshot_file
  public :: open_run, append_snapshot, close_run

  ! Module-level state for the multi-snapshot stream. Holds the open file id
  ! and a permanent /Snapshots group while a run is in progress. Single-run
  ! at a time — concurrent runs must serialise through the caller (Java side
  ! coordinates this via the ExportJobService thread).
  integer(HID_T) :: open_file_id     = -1_HID_T
  integer(HID_T) :: open_snaps_group = -1_HID_T
  logical        :: hdf5_lib_open    = .false.

contains

  ! ============================================================
  !                    SINGLE-SHOT WRITER
  ! ============================================================

  function write_snapshot_file(path, x, y, z, vx, vy, vz, m, n, sim_time) result(status)
    character(len=*), intent(in) :: path
    integer(int32),   intent(in) :: n
    real(real64),     intent(in) :: x(n), y(n), z(n)
    real(real64),     intent(in) :: vx(n), vy(n), vz(n)
    real(real64),     intent(in) :: m(n), sim_time
    integer(int32) :: status

    integer(HID_T) :: file_id, header_id, parttype_id, dspace_1d, dspace_2d, dset_id, attr_id
    integer(HID_T) :: scalar_space, str_type
    integer(HSIZE_T) :: dims_1d(1), dims_2d(2), scalar_dims(1)
    integer :: hdferr
    integer :: i
    real(real64), allocatable :: coords(:,:), vels(:,:)

    status = 0
    call h5open_f(hdferr)
    if (hdferr < 0) then; status = 1; return; end if

    call h5fcreate_f(trim(path), H5F_ACC_TRUNC_F, file_id, hdferr)
    if (hdferr < 0) then; status = 2; goto 99; end if

    call h5gcreate_f(file_id, "Header", header_id, hdferr)
    if (hdferr < 0) then; status = 3; goto 90; end if

    scalar_dims = [1_HSIZE_T]
    call h5screate_simple_f(1, scalar_dims, scalar_space, hdferr)

    call h5acreate_f(header_id, "NumPart", H5T_NATIVE_INTEGER, scalar_space, attr_id, hdferr)
    call h5awrite_f(attr_id, H5T_NATIVE_INTEGER, n, scalar_dims, hdferr)
    call h5aclose_f(attr_id, hdferr)

    call h5acreate_f(header_id, "Time", H5T_NATIVE_DOUBLE, scalar_space, attr_id, hdferr)
    call h5awrite_f(attr_id, H5T_NATIVE_DOUBLE, sim_time, scalar_dims, hdferr)
    call h5aclose_f(attr_id, hdferr)

    call h5tcopy_f(H5T_FORTRAN_S1, str_type, hdferr)
    call h5tset_size_f(str_type, int(len("astro/v1"), SIZE_T), hdferr)
    call h5acreate_f(header_id, "Format", str_type, scalar_space, attr_id, hdferr)
    call h5awrite_f(attr_id, str_type, "astro/v1", scalar_dims, hdferr)
    call h5aclose_f(attr_id, hdferr)
    call h5tclose_f(str_type, hdferr)

    call h5sclose_f(scalar_space, hdferr)
    call h5gclose_f(header_id, hdferr)

    call h5gcreate_f(file_id, "PartType1", parttype_id, hdferr)
    if (hdferr < 0) then; status = 4; goto 90; end if

    allocate(coords(3, n), vels(3, n))
    do i = 1, n
      coords(1, i) = x(i);  coords(2, i) = y(i);  coords(3, i) = z(i)
      vels(1, i)   = vx(i); vels(2, i)   = vy(i); vels(3, i)   = vz(i)
    end do

    dims_2d = [3_HSIZE_T, int(n, HSIZE_T)]
    call h5screate_simple_f(2, dims_2d, dspace_2d, hdferr)

    call h5dcreate_f(parttype_id, "Coordinates", H5T_NATIVE_DOUBLE, dspace_2d, dset_id, hdferr)
    call h5dwrite_f(dset_id, H5T_NATIVE_DOUBLE, coords, dims_2d, hdferr)
    call h5dclose_f(dset_id, hdferr)

    call h5dcreate_f(parttype_id, "Velocities", H5T_NATIVE_DOUBLE, dspace_2d, dset_id, hdferr)
    call h5dwrite_f(dset_id, H5T_NATIVE_DOUBLE, vels, dims_2d, hdferr)
    call h5dclose_f(dset_id, hdferr)

    call h5sclose_f(dspace_2d, hdferr)

    dims_1d = [int(n, HSIZE_T)]
    call h5screate_simple_f(1, dims_1d, dspace_1d, hdferr)
    call h5dcreate_f(parttype_id, "Masses", H5T_NATIVE_DOUBLE, dspace_1d, dset_id, hdferr)
    call h5dwrite_f(dset_id, H5T_NATIVE_DOUBLE, m, dims_1d, hdferr)
    call h5dclose_f(dset_id, hdferr)
    call h5sclose_f(dspace_1d, hdferr)

    call h5gclose_f(parttype_id, hdferr)
    deallocate(coords, vels)

90  continue
    call h5fclose_f(file_id, hdferr)

99  continue
    call h5close_f(hdferr)
  end function write_snapshot_file

  ! ============================================================
  !                  MULTI-SNAPSHOT WRITER
  ! ============================================================

  !> Open a new HDF5 file for streaming snapshots. Writes Header, creates
  !> the /Snapshots umbrella group, leaves both file and group open in
  !> module state for subsequent {@link append_snapshot} calls.
  function open_run(path, n) result(status)
    character(len=*), intent(in) :: path
    integer(int32),   intent(in) :: n
    integer(int32) :: status

    integer(HID_T) :: header_id, scalar_space, attr_id, str_type
    integer(HSIZE_T) :: scalar_dims(1)
    integer :: hdferr

    status = 0
    if (open_file_id /= -1_HID_T) then
      status = -1   ! a run is already open; caller bug
      return
    end if

    if (.not. hdf5_lib_open) then
      call h5open_f(hdferr)
      if (hdferr < 0) then; status = 1; return; end if
      hdf5_lib_open = .true.
    end if

    call h5fcreate_f(trim(path), H5F_ACC_TRUNC_F, open_file_id, hdferr)
    if (hdferr < 0) then; open_file_id = -1_HID_T; status = 2; return; end if

    call h5gcreate_f(open_file_id, "Header", header_id, hdferr)
    scalar_dims = [1_HSIZE_T]
    call h5screate_simple_f(1, scalar_dims, scalar_space, hdferr)

    call h5acreate_f(header_id, "NumPart", H5T_NATIVE_INTEGER, scalar_space, attr_id, hdferr)
    call h5awrite_f(attr_id, H5T_NATIVE_INTEGER, n, scalar_dims, hdferr)
    call h5aclose_f(attr_id, hdferr)

    call h5tcopy_f(H5T_FORTRAN_S1, str_type, hdferr)
    call h5tset_size_f(str_type, int(len("astro/v1-multi"), SIZE_T), hdferr)
    call h5acreate_f(header_id, "Format", str_type, scalar_space, attr_id, hdferr)
    call h5awrite_f(attr_id, str_type, "astro/v1-multi", scalar_dims, hdferr)
    call h5aclose_f(attr_id, hdferr)
    call h5tclose_f(str_type, hdferr)

    call h5sclose_f(scalar_space, hdferr)
    call h5gclose_f(header_id, hdferr)

    call h5gcreate_f(open_file_id, "Snapshots", open_snaps_group, hdferr)
  end function open_run

  function append_snapshot(snapshot_idx, x, y, z, vx, vy, vz, m, n, sim_time) result(status)
    integer(int32), intent(in) :: snapshot_idx, n
    real(real64),   intent(in) :: x(n), y(n), z(n)
    real(real64),   intent(in) :: vx(n), vy(n), vz(n)
    real(real64),   intent(in) :: m(n), sim_time
    integer(int32) :: status

    integer(HID_T) :: snap_id, dspace_1d, dspace_2d, dset_id, attr_id, scalar_space
    integer(HSIZE_T) :: dims_1d(1), dims_2d(2), scalar_dims(1)
    integer :: hdferr
    integer :: i
    real(real64), allocatable :: coords(:,:), vels(:,:)
    character(len=8) :: name

    status = 0
    if (open_file_id == -1_HID_T) then; status = -1; return; end if

    write(name, '(I5.5)') snapshot_idx

    call h5gcreate_f(open_snaps_group, trim(name), snap_id, hdferr)
    if (hdferr < 0) then; status = 3; return; end if

    scalar_dims = [1_HSIZE_T]
    call h5screate_simple_f(1, scalar_dims, scalar_space, hdferr)

    call h5acreate_f(snap_id, "Time", H5T_NATIVE_DOUBLE, scalar_space, attr_id, hdferr)
    call h5awrite_f(attr_id, H5T_NATIVE_DOUBLE, sim_time, scalar_dims, hdferr)
    call h5aclose_f(attr_id, hdferr)

    call h5acreate_f(snap_id, "Step", H5T_NATIVE_INTEGER, scalar_space, attr_id, hdferr)
    call h5awrite_f(attr_id, H5T_NATIVE_INTEGER, snapshot_idx, scalar_dims, hdferr)
    call h5aclose_f(attr_id, hdferr)

    call h5sclose_f(scalar_space, hdferr)

    allocate(coords(3, n), vels(3, n))
    do i = 1, n
      coords(1, i) = x(i);  coords(2, i) = y(i);  coords(3, i) = z(i)
      vels(1, i)   = vx(i); vels(2, i)   = vy(i); vels(3, i)   = vz(i)
    end do

    dims_2d = [3_HSIZE_T, int(n, HSIZE_T)]
    call h5screate_simple_f(2, dims_2d, dspace_2d, hdferr)

    call h5dcreate_f(snap_id, "Coordinates", H5T_NATIVE_DOUBLE, dspace_2d, dset_id, hdferr)
    call h5dwrite_f(dset_id, H5T_NATIVE_DOUBLE, coords, dims_2d, hdferr)
    call h5dclose_f(dset_id, hdferr)

    call h5dcreate_f(snap_id, "Velocities", H5T_NATIVE_DOUBLE, dspace_2d, dset_id, hdferr)
    call h5dwrite_f(dset_id, H5T_NATIVE_DOUBLE, vels, dims_2d, hdferr)
    call h5dclose_f(dset_id, hdferr)

    call h5sclose_f(dspace_2d, hdferr)

    dims_1d = [int(n, HSIZE_T)]
    call h5screate_simple_f(1, dims_1d, dspace_1d, hdferr)
    call h5dcreate_f(snap_id, "Masses", H5T_NATIVE_DOUBLE, dspace_1d, dset_id, hdferr)
    call h5dwrite_f(dset_id, H5T_NATIVE_DOUBLE, m, dims_1d, hdferr)
    call h5dclose_f(dset_id, hdferr)
    call h5sclose_f(dspace_1d, hdferr)

    call h5gclose_f(snap_id, hdferr)
    deallocate(coords, vels)
  end function append_snapshot

  function close_run() result(status)
    integer(int32) :: status
    integer :: hdferr

    status = 0
    if (open_file_id == -1_HID_T) return

    call h5gclose_f(open_snaps_group, hdferr)
    call h5fclose_f(open_file_id, hdferr)
    open_file_id     = -1_HID_T
    open_snaps_group = -1_HID_T
  end function close_run

end module nbody_io
