"""Sphinx configuration for the astro documentation site."""

project = "astro"
author = "Héctor Medel"
copyright = "2026, Héctor Medel"
release = "0.1.0"

extensions = [
    "sphinx.ext.autodoc",
    "sphinx.ext.napoleon",
    "sphinx.ext.intersphinx",
    "sphinx.ext.mathjax",
    "sphinx.ext.viewcode",
    "myst_parser",
]

source_suffix = {
    ".rst": "restructuredtext",
    ".md": "markdown",
}

templates_path = ["_templates"]
exclude_patterns = ["_build", "Thumbs.db", ".DS_Store"]

html_theme = "furo"
html_title = "astro — N-body stellar cluster simulator"
html_static_path = ["_static"]

# Bilingual: en (default) + es
language = "en"
locale_dirs = ["locale/"]
gettext_compact = False

intersphinx_mapping = {
    "python": ("https://docs.python.org/3", None),
    "numpy": ("https://numpy.org/doc/stable/", None),
    "h5py": ("https://docs.h5py.org/en/stable/", None),
    "astropy": ("https://docs.astropy.org/en/stable/", None),
}
