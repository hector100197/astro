# Installation — `astro`

This page lists the exact commands you need to run to install the prerequisites
for each of the three usage paths in the [README](README.md). It is not a
walkthrough — it stops as soon as you can run `make -C kernel` or `make dev`
successfully. For what to do *after* that, return to the README path you came
from.

| Path | Page section |
|---|---|
| Python wrapper only (notebook / CLI) | [Path A prerequisites](#path-a-prerequisites) |
| Web UI (full stack) | [Path B prerequisites](#path-b-prerequisites) |
| Full developer setup | [Path C prerequisites](#path-c-prerequisites) |

Common: see [Verify your install](#verify-your-install) once finished.

---

## Path A prerequisites

You need: **Python 3.10+**, **`gfortran`**, **GNU `make`**.

### macOS (Homebrew)

```bash
brew install python@3.12 gcc make
# 'gcc' includes gfortran on macOS via Homebrew.
```

If `gfortran` still isn't found after `brew install gcc`, run
`brew link --overwrite gcc` and reopen the terminal.

### Ubuntu / Debian

```bash
sudo apt update
sudo apt install -y python3.12 python3.12-venv python3-pip gfortran build-essential
```

### Fedora / RHEL

```bash
sudo dnf install -y python3.12 python3-pip gcc-gfortran make
```

### Arch / Manjaro

```bash
sudo pacman -S python gcc-fortran make
```

### Windows

Use [WSL 2](https://learn.microsoft.com/en-us/windows/wsl/install) with
Ubuntu and follow the Ubuntu/Debian instructions above. Native Windows
builds work but are unsupported in v0.1.0.

Now return to [README → Path A](README.md#path-a--python-wrapper-fastest-path).

---

## Path B prerequisites

You need everything from Path A, plus: **Docker Desktop**, **Java 21**,
**Maven 3.9+**, **Node.js 22 LTS**.

### macOS (Homebrew)

```bash
# Add the Python wrapper prereqs (skip if you already did Path A)
brew install python@3.12 gcc make

# Java 21
brew install openjdk@21
# Then make it the default in your shell init (~/.zshrc or ~/.bash_profile):
echo 'export JAVA_HOME="$(/usr/libexec/java_home -v 21)"' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# Maven + Node
brew install maven node@22
brew link --overwrite node@22

# Docker Desktop (downloads a .dmg you install manually)
brew install --cask docker
open -a Docker          # first launch — accept the license, wait for the daemon
```

### Ubuntu / Debian

```bash
# Python wrapper prereqs (from Path A)
sudo apt install -y python3.12 python3.12-venv gfortran build-essential

# Java 21 (Temurin via Adoptium repo)
sudo apt install -y wget gnupg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-21-jdk

# Maven
sudo apt install -y maven

# Node.js 22 via NodeSource
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs

# Docker Engine + Compose plugin
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker          # or log out + log back in
```

### Fedora / RHEL

```bash
sudo dnf install -y python3.12 gcc-gfortran make \
                    java-21-openjdk-devel maven \
                    nodejs npm docker docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker $USER
```

Now return to [README → Path B](README.md#path-b--web-ui).

---

## Path C prerequisites

Everything from Path B plus the optional tooling for cross-cutting work:

```bash
# For building the JOSS paper locally (only contributors who edit paper.md)
brew install pandoc           # macOS
sudo apt install -y pandoc    # Ubuntu/Debian

# For the Sphinx docs site
# (no extra system packages — handled by docs-site/requirements.txt in a venv)
```

Then see [DEVELOPMENT.md](DEVELOPMENT.md) for the per-layer workflow.

---

## Verify your install

After running the relevant section above, run from the repo root:

```bash
./scripts/dev-setup.sh
```

This script:
1. Checks every required binary is on your `PATH` and reports `OK` / `MISSING`
2. Installs per-layer dependencies (npm packages, Python `pip install -e .`,
   Sphinx requirements)

If any check shows `MISSING`, install that tool and re-run the script. When
everything is `OK`, jump back to the path you came from in the README and run
`make -C kernel` (Path A) or `make dev` (Path B).

---

## Troubleshooting

### `error: invalid source release 21 with --enable-preview`

Your active JDK isn't 21. The `services/` Maven build only enables the
`--enable-preview` flag when `<jdk>21</jdk>` matches. Pin Java 21:

```bash
# macOS
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH=$JAVA_HOME/bin:$PATH
java -version          # should say 21.0.x
```

```bash
# Linux (Ubuntu/Debian Temurin)
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
```

### `gfortran: command not found`

You installed `gcc` but it didn't pull `gfortran` (rare on Linux, common on
some macOS setups). Try:

```bash
# macOS
brew reinstall gcc
which gfortran          # should point to /opt/homebrew/bin/gfortran
```

### `Node.js version v20.14.0 detected. The Angular CLI requires v20.19+ or v22.12+`

You have an older Node. Either:

```bash
# macOS — upgrade to Node 22
brew install node@22
brew unlink node && brew link --overwrite node@22
node --version          # should say v22.x
```

```bash
# Or use nvm (works everywhere)
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
nvm install 22
nvm use 22
```

### `docker: Cannot connect to the Docker daemon`

Docker Desktop isn't running. On macOS open `Docker Desktop` from
Applications and wait for the whale icon in the menu bar to stop animating.
On Linux: `sudo systemctl start docker`.

### `port 5433 already in use`

Another Postgres instance is bound to the same port `astro` uses. Either
stop the conflicting service or change the mapping in `docker-compose.yml`
(but then also update `services/*/src/main/resources/application.yml`).

```bash
lsof -nP -iTCP:5433 -sTCP:LISTEN
```

### `npm install` is excruciatingly slow / fails on TLS

Switch the registry mirror once, then retry:

```bash
npm config set registry https://registry.npmjs.org/
```

### Mojibake / accents broken in YAML output

Your terminal locale is not UTF-8. Add to your shell init:

```bash
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
```

### Anything else?

Open an issue at https://github.com/hector100197/astro/issues — include the
output of `./scripts/dev-setup.sh` so we know what your environment looks
like.
