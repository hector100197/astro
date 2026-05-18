import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header>
      <h1>astro — N-body simulator</h1>
      <nav>
        <a routerLink="/simulate" routerLinkActive="active" i18n>Simular</a>
        <a routerLink="/about" routerLinkActive="active" i18n>Acerca</a>
      </nav>
    </header>
    <main>
      <router-outlet />
    </main>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; height: 100vh; }
    header { padding: 0.75rem 1.25rem; border-bottom: 1px solid #1f2937; background: #0b1220; color: #e5e7eb; }
    nav { display: flex; gap: 1rem; margin-top: 0.5rem; }
    nav a { color: #9ca3af; text-decoration: none; }
    nav a.active { color: #60a5fa; font-weight: 600; }
    main { flex: 1; }
  `]
})
export class AppComponent {}
