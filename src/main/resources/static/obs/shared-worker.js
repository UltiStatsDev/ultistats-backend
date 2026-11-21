// Retired OBS transport. Cached predecessors are also blocked by the server stream retirement.
self.onconnect = event => {
 for (const port of event.ports) {
  try { port.postMessage({type: 'retired'}); } finally { port.close(); }
 }
};
