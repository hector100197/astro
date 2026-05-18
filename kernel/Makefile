# kernel/Makefile — Fortran 2018 + OpenMP + HDF5 build

# GNU Make pre-defines FC := f77 as an implicit rule default. The `?=` form
# would not override that, so we use a normal assignment. Caller can still
# pin a specific compiler with `make FC=ifx` on the command line.
FC = gfortran

UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
    LIB_EXT := dylib
    LIB_FLAGS := -dynamiclib
else
    LIB_EXT := so
    LIB_FLAGS := -shared
endif

LIB_NAME := libnbody.$(LIB_EXT)

# ---- HDF5 detection ----
# Try pkg-config first (most portable). Fall back to Homebrew's known path on
# macOS Apple Silicon. If neither works, the build fails with a clear error.
HAVE_PKG_HDF5 := $(shell pkg-config --exists hdf5_fortran 2>/dev/null && echo yes)
ifeq ($(HAVE_PKG_HDF5),yes)
    HDF5_CFLAGS := $(shell pkg-config --cflags hdf5_fortran)
    HDF5_LIBS   := $(shell pkg-config --libs hdf5_fortran)
else ifeq ($(UNAME_S),Darwin)
    # Homebrew fallback (Apple Silicon path, then Intel / older Homebrew).
    HDF5_PREFIX := /opt/homebrew/opt/hdf5
    ifeq ($(wildcard $(HDF5_PREFIX)/lib/libhdf5_fortran.$(LIB_EXT)),)
        HDF5_PREFIX := /usr/local/opt/hdf5
    endif
    HDF5_CFLAGS := -I$(HDF5_PREFIX)/include
    HDF5_LIBS   := -L$(HDF5_PREFIX)/lib -lhdf5_fortran -lhdf5
else
    # Linux fallback: Debian/Ubuntu `libhdf5-dev` serial multiarch layout
    # (modules in /usr/include/hdf5/serial, libs in the arch-specific dir).
    # The serial dir is NOT on the default runtime linker path, so embed an
    # rpath — otherwise dlopen() of this .so (e.g. from the JVM via FFM)
    # fails with "Cannot open library" even though linking succeeded.
    HDF5_ARCH   := $(shell uname -m)
    HDF5_DIR    := /usr/lib/$(HDF5_ARCH)-linux-gnu/hdf5/serial
    HDF5_CFLAGS := -I/usr/include/hdf5/serial
    HDF5_LIBS   := -L$(HDF5_DIR) -Wl,-rpath,$(HDF5_DIR) -lhdf5_fortran -lhdf5
endif

FFLAGS_RELEASE := -O3 -march=native -fopenmp -fimplicit-none -std=f2018 -Wall -fPIC $(HDF5_CFLAGS)
FFLAGS_DEBUG   := -O0 -g -fopenmp -fimplicit-none -fbounds-check -std=f2018 -Wall -Wextra -fPIC $(HDF5_CFLAGS)
FFLAGS ?= $(FFLAGS_RELEASE)

LDFLAGS := -fopenmp $(HDF5_LIBS)

BUILD_DIR := build
SRC_DIR := src
TEST_DIR := tests

SOURCES := $(SRC_DIR)/nbody_core.f90 $(SRC_DIR)/nbody_io.f90 $(SRC_DIR)/nbody_api.f90
OBJECTS := $(SOURCES:$(SRC_DIR)/%.f90=$(BUILD_DIR)/%.o)

.PHONY: all debug clean test info

all: $(BUILD_DIR)/$(LIB_NAME)

debug: FFLAGS = $(FFLAGS_DEBUG)
debug: all

info:
	@echo "FC          = $(FC)"
	@echo "HDF5_CFLAGS = $(HDF5_CFLAGS)"
	@echo "HDF5_LIBS   = $(HDF5_LIBS)"

$(BUILD_DIR)/$(LIB_NAME): $(OBJECTS) | $(BUILD_DIR)
	$(FC) $(LIB_FLAGS) $(LDFLAGS) -o $@ $^

$(BUILD_DIR)/%.o: $(SRC_DIR)/%.f90 | $(BUILD_DIR)
	$(FC) $(FFLAGS) -J$(BUILD_DIR) -c $< -o $@

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

test: $(BUILD_DIR)/$(LIB_NAME)
	@echo "TODO: wire up pFUnit-based tests in tests/"
	@echo "Java-side physics tests are in services/simulation-service/src/test/"

clean:
	rm -rf $(BUILD_DIR)
