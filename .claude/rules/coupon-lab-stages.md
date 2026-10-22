---
paths:
  - "coupon-lab/src/main/**"
---

# coupon-lab stages

* Each strategy class carries `@ConditionalOnProperty` on `coupon.strategy`.
* Stages share only interfaces and models.
* Changes to locks, transactions or constraints are that stage's own decision; do not apply them to other stages.
