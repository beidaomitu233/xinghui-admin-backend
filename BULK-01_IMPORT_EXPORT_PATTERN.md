# Import/export pattern for business modules

This feature is the user-module example. A second module should keep its own Excel row model and service rules; it should reuse the safeguards and response shape below instead of adding user-specific columns to a generic component.

## Import

- Define a module-specific Excel model with explicit column headers and a fixed order. Validate the exact expected header row before any writes.
- Set endpoint-specific limits for request bytes, uploaded file bytes, expanded archive bytes, archive entries, and data rows. Reject the request before multipart parsing when its declared body is too large, inspect the archive before parsing, and stop the row listener at the first row over the cap.
- Parse the full bounded file before saving. Reject file-level failures such as a damaged workbook, wrong headers, or excess rows without writing any data.
- Use partial success for row-level errors. Return `totalRows`, success/failure/skipped counts, and per-row `rowNumber`, `status`, `field`, and a fixed safe `reason`. The row number is the physical worksheet line, including the header row offset.
- Map imported identifiers only through objects visible to the current tenant. Call the same single-record service method used by the normal create flow so validation, authorization, uniqueness checks, and record-plus-association transactions stay aligned.
- For this user-import flow, create every imported account disabled as a pre-provisioned account. Keep the template free of an “enabled” choice, tell admins that login is unavailable until activation, and name the operational step that uses the existing one-time reset-password response before the account is enabled. Other modules should choose and document their own activation policy.
- Never return or log passwords, source cell contents, database exception text, or stack traces. Error reports contain only row number, status, field, and the fixed reason.

## Export

- Share the list endpoint's filter builder and tenant/data-scope constraints. Count matching rows and reject over the documented export cap before setting download headers.
- Fetch a bounded page at a time. Batch-load related records for that page rather than issuing one query per exported row.
- Export only approved business fields. Before writing user-controlled text, prefix an apostrophe when the first non-whitespace/control character is `=`, `+`, `-`, or `@`.
- Keep a valid header row for empty results. Encode a safe attachment filename and set the XLSX content type.

## User-facing flow

- Gate template/export controls with read permission and import controls with write permission; enforce the same permissions at the API.
- Show the import counts and stable row-level results. Keep the downloadable report free of usernames, source values, secrets, and internal errors.
- Keep a generic finite request timeout. For a bounded import endpoint, set a request-specific timeout that accounts for its maximum row count and measured processing time. If a network error or timeout leaves completion unknown, tell the operator to refresh and check for created rows before retrying; distinguish that from a successful import whose later list refresh failed.
- Use module-specific field help and row validation. Do not move those details into a cross-module generic Excel engine.
