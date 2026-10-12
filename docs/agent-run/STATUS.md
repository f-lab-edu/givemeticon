# Warmed workflow status

2026-10-11: run [38137748465](https://github.com/f-lab-edu/givemeticon/actions/runs/38137748465),
actual SHA `0c59657c92c33e61168a573feeafe72d84a4ce1c`, stopped at policy gate.
Load registration/warmup/measurement NOT_REACHED; exact cause UNKNOWN;
service consistency/performance NOT_ESTABLISHED. Cleanup down/remove exit 0,
independently compared by Verifier (event b6e6cafcb630343091ba38e707077e01246497ab83eaba7d6fbc88328f19d1b6)
and Builder against original artifact. Originals preserved.

Local diagnostic delta prepared and locally validated; independent delta review
pending. No push, dispatch or new hosted run authorized. Details and validation:
[experiment record](../experiments/WARMED_WORKFLOW_ISSUE_177.md) and
[source manifest](../experiments/WARMED_SOURCE_MANIFEST_ISSUE_177.json).


## 2026-10-12 local conditions/count delta

Authorized by Architect events d11274e3f9de01f0349433202987221c97c4e7a0124f38f35e65d5eeb47fe53e
and 9a00b4eeda0edb7f114551858b5f518414da943a0eded728596665b6aa3663f7.
This fixes the reproduced cross-context false-accept gap: endpoint entries are
counted across contexts, with exactly one expected polarity and zero opposite
entries required. Matching and stock/V1 expectations are unchanged. Document,
contexts, each context and each polarity map must be objects; absent evidence
still refuses. Decision uses actual counts; emitted counts saturate at 2.
Success evidence replaces raw condition keys/messages with fixed worker counts.

Strict policy-failure-v1 retains its exact original field set at all stages.
Strict policy-failure-v2 requires the additional condition_counts field and is
allowed only for conditions_match. Four fixed workers each require exactly
pos_count/neg_count integers 0/1/2 (bool rejected); 2 means two or more. Missing,
unknown and extra fields/types refuse. A v2 failure with all expected counts
is contradictory and refuses. Producer and collector share policy-failure.py's
validator. Stage transitions clear counts and restore v1. Count extraction uses
the fixed tuple; extraction/validation failure retains strict v1 and exit 1.
Sidecar/diagnostic failure also retains gate refusal without raw exceptions.

Counts describe endpoint keys, not beans: same short-name collisions may already
have merged before endpoint serialization. They do not establish pre-serialization
uniqueness. For this new producer, v1 at conditions_match failure means count
construction/validation failed; v1 before or after that stage is normal. Count
absence cannot identify a mismatched worker. Existing v1 artifacts remain valid.
The tracked hosted-v1-failure fixture is the unchanged app1 sidecar from run
38185758150. Endpoint fixture projections derive from actual Boot 3.1.3 direct
and scanned local mocked-dependency contexts, not full hosted contexts.

Regression coverage includes four real endpoint projections, 80 refusal variants,
three-context saturation, invalid object types (including the reproduced list
PASS), strict v1/v2 contamination and stage coupling, contradictions for stock
and V1, count fallback, stage reset and CLI -> sidecar -> collector with and
without redaction input. Full local verification records and exact committed SHA
are persisted separately in RESEARCH/ISSUE_177_CONDITIONS_IMPLEMENTATION_20261012/.
No production/workflow/cap/worker config changes, push, dispatch or hosted run.
Run 38185758150 remains FAIL/NOT_REACHED/NOT_ESTABLISHED; exact cause UNKNOWN.
This independent false-accept fix does not explain that hosted refusal.


### Local HTTP boundary and strict harness normalization (2026-10-12)

Run [38187178304](https://github.com/f-lab-edu/givemeticon/actions/runs/38187178304)
at `be56a6bd3265020db8ff5816aeb73d08e35e0bf5`, attempt 1, remains
FAIL / NOT_REACHED / NOT_ESTABLISHED. Reviewer event `857a18a24c691212d949db2cd368b52ac77797dd15b3699808d57f3db1d08029`
and Verifier event `0cf1a72a333d5f9bec85316e394d5c45185795d94e5ef58357b23f756b07e617`
confirm stock/app1 conditions_match v2 with four 0/0 counts, cleanup exits 0/0,
62 matching checksums and redacted collection exit 0. Exact hosted cause UNKNOWN.

The local embedded HTTP test runs actual Actuator -> MVC -> application handler
-> JSON -> the same Python parser, with mocked worker dependencies and real
profile conditions. For stock and V1, enabling the unchanged production handler
applies an envelope: envelope_applied=true, top_level_contexts=false,
data_contexts=true. Before normalization, the old parser refuses with four 0/0;
the inner report and the handler-free raw HTTP report satisfy the existing worker
polarity rules. This reproduces a local mechanism consistent with hosted evidence;
it does not establish what the historical hosted response actually contained.
Raw HTTP conditions remain in memory only. Safe structural booleans and fixed
worker counts are retained under workspace RESEARCH/ISSUE_177_HTTP_BOUNDARY_20261012/.

Architect event `d2e38ea4dffdebbf05c23b7633a5b8d9e78965a4f29efdc86d3e9e2bf02df239`
authorizes local harness-only B implementation and commit. Transport normalization
is separate from worker conditions. Exactly two top-level forms are accepted:
raw `{contexts}` or `{message,data}` with message equal to SUCCESS and object data
whose exact keys are `{contexts}`. Only one unwrap is performed. Extra, mixed,
nested, failure or arbitrary data responses refuse. Normalized contexts must be
present, an object, and nonempty; violations fail at conditions_decode with the
unchanged v1 diagnostic schema. Missing workers, duplicate entries and polarity
rules are unchanged. Successful policy evidence records the fixed boolean
condition_capture.envelope_unwrapped. No raw keys/messages are emitted.

The live HTTP test covers raw and envelope stock/V1 and asserts every fixed count.
Python regressions cover normal forms, malformed/error/mixed/nested/extra shapes,
missing/empty/invalid contexts, successful evidence booleans and decode-stage
failure without v2 counts. Existing direct endpoint fixtures retain their role
as projections; they do not substitute for the HTTP boundary test.

Production wrapper contract design is a separate pending task: decide which
endpoint/controller families should be wrapped, with compatibility coverage for
other endpoints and app API consumers. No production, gate polarity, schema,
workflow, cap, worker configuration, push or hosted rerun is authorized here.
