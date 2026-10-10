# Safe native ABI

The contract for `native/bridge/include/jpdfium_api.h`. The legacy `jpdfium.h` ABI is unchanged
and keeps raw pointer-valued handles; the two surfaces never share handle values.

## Versioning

`jpdf_api_version()` returns `(major << 16) | minor`. A binding accepts the library when the
major matches and the minor is at least the one it was compiled against. Additive changes bump
the minor; any layout or semantic break bumps the major.

`jpdf_abi_struct_size(JPDF_STRUCT_*)` reports each struct's size so bindings that do not compile
against the header (Java FFM) can verify layouts at load time.

## Structs

Every struct starts with `uint32_t struct_size`, uses fixed-width fields and explicit `reserved`
padding, so the layout is identical on every 64-bit target.

Input structs (`*_options`):

- `struct_size` below the 1.0 size is `JPDF_E_INVALID_ARGUMENT`.
- A larger struct from a newer caller is accepted only when every byte the library does not know
  is zero; otherwise `JPDF_E_UNSUPPORTED`. A newer caller can never silently lose a setting.
- Unknown flag bits and non-zero reserved fields are `JPDF_E_UNSUPPORTED`.
- A `NULL` options pointer selects documented defaults where the function allows it.

Output structs (`*_info`, `jpdf_bitmap_view`): the caller sets `struct_size`; the library
requires at least the 1.0 size and never writes past `struct_size`.

Native structs are not a wire format. Do not serialize them across processes.

## Strings

Inputs are NUL-terminated UTF-8. Library-owned output strings (`build_id`, status names) are
static. Error detail is copied into a caller buffer by `jpdf_last_error_message`.

## Status codes

Every function returns `jpdf_status`; `JPDF_OK` is 0 and every failure is negative. Bindings must
map unknown negative values to an internal error, never to success.

| Status | Meaning |
|---|---|
| `INVALID_ARGUMENT` | Bad plain argument or struct |
| `INVALID_HANDLE` | Token was never issued, or is malformed |
| `STALE_HANDLE` | Token was valid once and has been closed |
| `WRONG_HANDLE_TYPE` | Token is live but of another type |
| `BUSY` | Close or shutdown refused while dependents exist; nothing changed |
| `UNSUPPORTED` | Unknown flag, option, field or format |
| `IO`, `PASSWORD`, `FORMAT`, `NOT_FOUND` | Input problems |
| `LIMIT` | Pixel or byte budget exceeded |
| `NO_MEMORY`, `BUFFER_TOO_SMALL` | Allocation or caller buffer too small |
| `NOT_INITIALIZED`, `QUIESCING`, `STOPPED`, `FAILED` | Runtime state refuses the call |
| `AUTHORITY` | The legacy bridge already owns PDFium in this process |
| `REENTRANT` | Called from inside the domain |
| `CANCELLED`, `TIMEOUT` | Reserved |
| `INTERNAL` | Unexpected native failure |

Detail is per thread and per call: each call clears it, and the first failure in a call wins so
cleanup cannot overwrite the cause. Read it before making another call on the same thread.

## Ownership

| Value | Ownership |
|---|---|
| Path, password, options | Borrowed for the duration of the call |
| Document, page, bitmap, view tokens | Owned by the caller until the matching close or release |
| Bitmap pixels | Owned by the library; independent of the page and document |
| `jpdf_bitmap_view.data` | Valid until `jpdf_view_release`, even after the bitmap closes |
| `build_id`, status names | Static, owned by the library |
| Error detail | Copied into the caller's buffer |

No C++ exception crosses the ABI: every entry point is `noexcept` and runs its body inside an
exception firewall that maps failures to status codes.
