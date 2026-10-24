// Pure helpers that decide which coupon event a request goes to (test C: spread the load over several event rows).
// Kept free of k6 imports so that test_events.py can load it with node. Used by issue.js.

// "7,8,9" -> ["7", "8", "9"]. Anything that is not a list of integers is a configuration error.
export function parseEventIds(text) {
  const ids = String(text === undefined || text === null ? '' : text)
    .split(',')
    .map((id) => id.trim())
    .filter((id) => id !== '');
  for (const id of ids) {
    if (!/^[0-9]+$/.test(id)) {
      throw new Error(`event id must be a non-negative integer, got '${id}'`);
    }
  }
  return ids;
}

// Round robin by iteration number: iteration i goes to ids[i % ids.length], so every event gets the same share.
export function pickEventId(ids, iteration) {
  if (ids.length === 0) {
    throw new Error('no event ids');
  }
  return ids[iteration % ids.length];
}
