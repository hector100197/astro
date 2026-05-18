/**
 * Bootstrap entry for simulation-mfe.
 *
 * Two-phase init for Native Federation:
 *   1. initFederation() — registers this remote with the federation runtime.
 *      Fails harmlessly when running standalone (no `ng add` yet) because
 *      there is no remoteEntry.json served. We log a friendly note and
 *      continue with the standalone bootstrap.
 *   2. import('./bootstrap') — runs the Angular bootstrap.
 */
import { initFederation } from '@angular-architects/native-federation';

initFederation()
  .catch(() => {
    console.info(
      '[main] Native Federation runtime not active — running standalone. ' +
      'Run `ng add @angular-architects/native-federation --type remote` to enable federation.'
    );
  })
  .then(() => import('./bootstrap'))
  .catch(err => console.error('[main] Bootstrap failed:', err));
