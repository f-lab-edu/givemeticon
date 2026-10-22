# evidence

Test-bundle results, written only by `scripts/verify.sh bundle ...`. Read-only: never edit or delete.

Layout: `<stage>/<tag>/<UTC yyyyMMdd-HHmmss>-<sha>[-dirty]/`
* `<tag>.json` - measurements and env block written by the test
* `TEST-*.xml` - JUnit results of that run

`-dirty` means the working tree had uncommitted changes under `coupon-lab/` when the run started.
Compare runs only for the same test, iteration count and env block.
