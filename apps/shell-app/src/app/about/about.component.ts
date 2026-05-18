import { Component } from '@angular/core';

@Component({
  selector: 'app-about',
  standalone: true,
  template: `
    <article class="about">
      <h2 i18n>Acerca de astro</h2>
      <p i18n>
        Simulador N-body de cúmulos estelares. Kernel Fortran 2018 + OpenMP,
        backend Spring Boot, frontend Angular 21 con Native Federation.
      </p>
      <p i18n>
        Diseñado para investigación reproducible y enseñanza de física computacional.
      </p>
      <ul>
        <li><a href="https://github.com/hectormedel/astro" target="_blank">GitHub</a></li>
        <li i18n>Versión: 0.1.0-dev</li>
      </ul>
    </article>
  `,
  styles: [`
    :host { display: block; padding: 2rem; max-width: 800px; }
    .about h2 { margin-top: 0; }
    .about p { line-height: 1.6; color: #cbd5e1; }
    .about a { color: #60a5fa; }
  `]
})
export class AboutComponent {}
