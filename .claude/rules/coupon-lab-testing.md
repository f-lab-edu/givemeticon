---
paths:
  - "coupon-lab/src/test/**"
---

# coupon-lab tests

* Bundle tests call the service methods directly, not through HTTP.
* Write `build/evidence/<tag>.json` in `@AfterAll`, whether the test passed or failed.
* Assert invariants only: no oversell (issued <= stock), no duplicate issuance, stock matches issued count.
* Measured values (latency, throughput, counts) go into the evidence JSON, never into assertions.
* Iteration count, thread count and stock are constants at the top of the test class.
