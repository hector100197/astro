import { Routes } from '@angular/router';
import { loadRemoteModule } from '@angular-architects/native-federation';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'simulate' },
  {
    path: 'simulate',
    loadComponent: () =>
      loadRemoteModule('simulation-mfe', './Component').then(m => m.SimulationComponent)
  },
  {
    path: 'about',
    loadComponent: () =>
      import('./about/about.component').then(m => m.AboutComponent)
  }
];
