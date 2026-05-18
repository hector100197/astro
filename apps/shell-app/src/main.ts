/**
 * Native Federation host bootstrap.
 *
 * The host loads the manifest (which lists remote URLs) before any of the
 * application code executes, so dynamic {@code loadRemoteModule} calls in
 * the router can resolve to the right URL at runtime.
 *
 * Without `ng add @angular-architects/native-federation --type dynamic-host`
 * the federation runtime is not active — initFederation rejects, we log a
 * friendly note and continue. Routes that depend on remote modules will
 * fail at navigation time until federation is enabled.
 */
import { initFederation } from '@angular-architects/native-federation';

initFederation('/federation.manifest.json')
  .catch(() => {
    console.info(
      '[main] Native Federation runtime not active — chrome will render but routes ' +
      'that load remotes will fail. Run `ng add @angular-architects/native-federation ' +
      '--type dynamic-host` to enable.'
    );
  })
  .then(() => import('./bootstrap'))
  .catch(err => console.error('[main] Bootstrap failed:', err));
