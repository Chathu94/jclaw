# safaribooks.py

Downloads a book you have O'Reilly Learning subscription access to and packages it as an EPUB.

Derived from [lorenzodifuccia/safaribooks](https://github.com/lorenzodifuccia/safaribooks), with **local modifications that upstream does not have** — see [Do not replace this with upstream](#do-not-replace-this-with-upstream).

## Do not replace this with upstream

O'Reilly retired the `/api/v1/book/{id}/` endpoint (it now returns 404). This copy has been ported to the v2 API:

```
/api/v2/epubs/urn:orm:book:{id}/
```

That port spans ~166 lines: `API_TEMPLATE_V2`, `_map_v2_book_info()` (maps v2 response fields onto the v1 names the rest of the code expects), and `_get_book_chapters_v2()` (v2 paginates chapters behind a separate URL). A fresh `git clone` of upstream **will not work** — it will 404 on every book. If this script ever needs repair, patch it; don't re-clone over it.

Known gap carried over from the port: v2 returns authors/publishers/subjects as URLs that need a follow-up fetch, so `_map_v2_book_info()` currently leaves those as empty lists. EPUB metadata for those three fields comes out blank. Content is unaffected.

## Paths

Two environment variables override where the script reads and writes. Both have defaults, so it also runs standalone outside a skill directory.

| Variable | Default | Purpose |
|---|---|---|
| `SAFARIBOOKS_COOKIES` | `../credentials/cookies.json` | Session cookies (see below) |
| `SAFARIBOOKS_OUTPUT` | `./Books` | Where EPUBs and run logs land |

The JClaw skill always sets `SAFARIBOOKS_OUTPUT` to `books/` in the agent workspace, so output never accumulates inside the skill directory.

## Usage

```bash
pip install -r requirements.txt

SAFARIBOOKS_OUTPUT=/path/to/output python3 safaribooks.py <BOOK_ID>
```

`<BOOK_ID>` is the digit string from the book's URL:

```
https://learning.oreilly.com/library/view/book-name/9781234567890/
                                                    ^^^^^^^^^^^^^
```

Flags:

- `--kindle` — adds CSS that stops `table`/`pre` overflow; use when targeting an e-reader
- `--preserve-log` — keeps `info_<BOOK_ID>.log` even on success

`--cred` and `--login` are **disabled upstream** ([issue #358](https://github.com/lorenzodifuccia/safaribooks/issues/358)) — O'Reilly's login flow changed and password auth no longer works. Cookies are the only supported auth path.

## Cookies

Auth is a JSON dict of cookie name → value, or the list-of-objects shape a cookie-export extension produces — the script accepts either. The two that matter are `orm-jwt` and `orm-rt`; the `bm_*` / `_abck` Akamai cookies help requests avoid being flagged as bot traffic and are worth including when present.

`orm-jwt` expires in roughly **30 minutes**, so a stale `cookies.json` is the single most common cause of failures. A run that dies with a 401/403 or an auth error almost always just needs fresh cookies.

To refresh, export them from a logged-in browser session on `learning.oreilly.com` (DevTools → Application → Cookies, or a cookie-export extension) and write them into `credentials/cookies.json` in the shape of `credentials/cookies.example.json`.

The script rewrites `cookies.json` after a successful run to persist the refreshed session, so the file must stay writable.

## Failure modes

| Symptom | Cause |
|---|---|
| 401 / 403 / auth error | `orm-jwt` expired — refresh cookies |
| 404 on every book | Someone replaced this with upstream v1 code |
| `Book directory already exists` | Prior partial run; delete that directory under the output dir and retry |
| Empty author/publisher in EPUB | Known v2 mapping gap, see above |
