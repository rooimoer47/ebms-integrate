# ebms-core compatibility reference

The OpenAPI documents in this directory are **vendored copies of ebms-core's own API
definitions**, kept here as the reference that our compatibility layer is built and tested
against (see Epic D in `docs/design-0.1.0.md`).

They are not our API. Do not edit them — refresh them from upstream instead.

## Provenance

| | |
|---|---|
| Source | https://github.com/eluinstra/ebms-core |
| Branch | `ebms-core-2.20.x` |
| Commit | `98d1cae4af4dd9e98f0fbc4f91b84e2123fa594d` (2026-08-27) |
| Retrieved | 2026-09-07 |

| File here | Upstream path |
|---|---|
| `ebms-core-ebms-api.json` | `core/resources/api/rest/ebms.json` |
| `ebms-core-cpas-api.json` | `core/resources/api/rest/cpas.json` |

Upstream also ships `urlMappings.json` and `certificateMappings.json`. Those endpoints are
deliberately out of scope for us (story D8), so they are not vendored.

## Refreshing

```bash
B=https://raw.githubusercontent.com/eluinstra/ebms-core/ebms-core-2.20.x/core/resources/api/rest
curl -s "$B/ebms.json" -o docs/compat/ebms-core-ebms-api.json
curl -s "$B/cpas.json" -o docs/compat/ebms-core-cpas-api.json
```

Update the provenance table above when you do, and re-run the contract tests (story H3) — a
diff here is a change to the contract our clients depend on.

## What we implement

See appendix A of `docs/design-0.1.0.md` for the endpoint-by-endpoint matrix of what is
planned, what maps to an existing feature, and what is out of scope.

## Note on the `DataSource` schema

The generated OpenAPI renders `DataSource` from the JAX-RS reflection of
`jakarta.activation.DataSource` (`name`, `inputStream`, `contentType`, `outputStream`), which
is **not** the JSON that actually goes over the wire. The real wire model is
`nl.clockwork.ebms.api.ebms.model.DataSource`:

```
name        String
contentId   String
contentType String
content     byte[]   -- base64-encoded in JSON
```

Build against that, not against the generated schema.
