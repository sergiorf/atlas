# Bundle validation attestation

- **Status:** Implemented for bundles published after this contract
- **Attestation version:** `1`
- **Location:** `data/_atlas/bundles/validation/<bundle-id>.json`

A validation attestation is generated evidence bound to an immutable published bundle. It is not
stored inside the generation because changing generation contents after hashing would violate
immutability. Publication performs full validation after pointer cutover; a blocking failure uses
the existing publication recovery path to restore the prior pointer.

Version 1 contains:

| Field | Type | Meaning |
| --- | --- | --- |
| `attestation_version` | integer | Attestation schema version; exactly `1` |
| `bundle_id` | string | Exact validated bundle generation |
| `bundle_manifest_sha256` | string | SHA-256 of its immutable `bundle-manifest.json` |
| `validator_version` | string | Validator implementation version |
| `validation_contract_version` | string | Bundle validation contract version |
| `mode` | string | `full`; structural-only evidence is insufficient for serving |
| `result` | string | `PASS` or `PASS_WITH_WARNINGS` |
| `completed_at` | timestamp string | UTC validation completion/evidence time |
| `warning_codes` | array of strings | Stable validation check IDs that warned |
| `components` | array | Component name and manifest-recorded SHA-256 pairs |

The file is written by atomic replacement. It contains no absolute paths. A downstream consumer
must recompute the manifest and required component hashes, require an exact bundle ID match, and
apply its own allowlist to warning codes. Unknown warnings fail closed.

Historical bundles published before this contract do not acquire evidence by mutation. They
remain valid ETL bundles but are ineligible for the serving reader until an explicit operator
workflow independently performs full validation and writes an attestation for the unchanged
manifest. That historical-attestation command is not implemented in the first slice.
