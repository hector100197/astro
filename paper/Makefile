# paper/Makefile — JOSS submission build using Pandoc.

paper.pdf: paper.md paper.bib
	pandoc paper.md \
	  --bibliography=paper.bib \
	  --citeproc \
	  --pdf-engine=xelatex \
	  -o paper.pdf

clean:
	rm -f paper.pdf

.PHONY: clean
