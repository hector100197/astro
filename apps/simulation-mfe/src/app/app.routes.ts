import { Routes } from '@angular/router';
import { SimulationComponent } from './simulation/simulation.component';

/**
 * Routes used when simulation-mfe runs standalone (port 4201, direct browser access).
 * When loaded via Native Federation by shell-app, the SimulationComponent is loaded
 * directly via `loadRemoteModule(...)` and these routes are bypassed.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', component: SimulationComponent }
];
