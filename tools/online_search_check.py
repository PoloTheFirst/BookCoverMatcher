"""Checks the exact request `OpenLibraryBooksSource` sends, and the fields the parser reads.

This is the desktop half of the online-search integration test: it proves the endpoint, the
`fields=` list and the response shape the Kotlin parser expects all still agree. It needs no
device and no API key.

    python tools/online_search_check.py ["a book title"]
"""

import json
import sys
import urllib.parse
import urllib.request

# Keep these three in sync with OpenLibraryBooksSource.kt / OpenLibraryResponseParser.kt.
ENDPOINT = "https://openlibrary.org/search.json"
FIELDS = "key,title,author_name,first_publish_year,publisher,cover_i,language,number_of_pages"
USER_AGENT = "BookCoverMatcher/2.1 (Android; on-device cover matcher)"
COVER_SITE = "https://covers.openlibrary.org"
SITE = "https://openlibrary.org"

import re

KEY_PATTERN = re.compile(r"^/(works|books)/(OL[0-9]+[A-Z]?)$")


def build_url(query: str, limit: int) -> str:
    return f"{ENDPOINT}?limit={limit}&fields={FIELDS}&q={urllib.parse.quote_plus(query)}"


def main() -> int:
    query = sys.argv[1] if len(sys.argv) > 1 else "The Hobbit"
    url = build_url(query, 5)
    print("GET", url)

    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        print("HTTP", response.status, response.headers.get("Content-Type"))
        body = json.load(response)

    docs = body.get("docs")
    if docs is None:
        print("FAIL: response has no docs array -> OpenLibraryResponseParser.ParseException")
        return 1

    print(f"numFound={body.get('numFound')}  docs={len(docs)}")
    kept = 0
    for doc in docs:
        key = doc.get("key", "")
        title = (doc.get("title") or "").strip()
        if not KEY_PATTERN.match(key) or not title:
            print(f"   skip  key={key!r} title={title!r}")
            continue
        kept += 1
        cover = doc.get("cover_i")
        if isinstance(cover, list):
            cover = cover[0] if cover else None
        cover_url = f"{COVER_SITE}/b/id/{int(cover)}-M.jpg" if cover else None
        print(f"   ok    {title[:44]!r}  {doc.get('author_name', ['-'])[0][:24]!r}")
        print(f"         page  {SITE + key}")
        print(f"         cover {cover_url}")

    print(f"\n{kept} of {len(docs)} records survive the parser's rules.")
    if kept == 0:
        print("FAIL: nothing usable for this query.")
        return 1

    # The cover host is a second origin; confirm it really serves the image the card will show.
    first_cover = next(
        (f"{COVER_SITE}/b/id/{int((d['cover_i'][0] if isinstance(d.get('cover_i'), list) else d.get('cover_i')))}-M.jpg"
         for d in docs if d.get("cover_i")),
        None,
    )
    if first_cover:
        try:
            with urllib.request.urlopen(urllib.request.Request(first_cover, headers={"User-Agent": USER_AGENT}), timeout=30) as r:
                blob = r.read()
            print(f"cover fetch: HTTP {r.status} {r.headers.get('Content-Type')} {len(blob)} bytes")
        except Exception as exc:  # noqa: BLE001 - diagnostics only
            print("cover fetch failed:", exc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
