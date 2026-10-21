# Average color generator

Run `com.metcolor.avgColorGenerator.main()` from the IDE, with no arguments.
The working directory can be `met-color` or its parent workspace.

## Input and selection

- `usableObjectIDS.txt` is the selection list: one numeric artwork ID per line.
- `openaccess.csv` supplies `objectID` and `primaryImageSmall` directly. Its optional
  `primaryImage` column supplies the original-image fallback.
- Only IDs present in both files are considered. Empty small-image fields are
  skipped. The final summary reports requested IDs absent from the CSV separately.
- The CSV is streamed with support for quoted commas, doubled quotes, embedded
  line breaks, UTF-8, and an optional byte-order mark. Columns are found by name.
  The complete CSV is never loaded into memory.

The supplied CSV has 259,874 records. Of the 96,790 IDs in the current selection
list, 42,946 are present, all with small-image URLs; 53,844 are absent. These are
counts for this dataset snapshot, not guarantees for future replacements.

## Downloads and results

Eight workers download and process images concurrently, with at most sixteen
pending image jobs. There are no Met object API requests or API request limits.
Only the image URLs supplied by the CSV are requested.

Each image is decoded with Scrimage and averaged over every pixel. Results are
appended as `objectID,red,green,blue` to `objectAverageColors.csv`. Already-saved IDs
and duplicate input IDs are skipped; each new row is flushed immediately so a
stopped job can resume later.

If the small-image URL returns HTTP 404, the worker tries that row's nonempty
`primaryImage` URL. Empty small-image fields remain excluded even if an original
URL exists. Temporary network errors, 403/429 responses, and server errors get up
to three attempts per URL, with increasing delays and `Retry-After` support.
A retry waits in its image worker; other workers continue downloading.

`objectAverageColors-failures.csv` retains an append-only error history, including
earlier runs. An old error may remain after a later attempt succeeds. IDs absent
from the CSV are reported in the summary, not as failed image downloads.

## Progress and timing

Progress appears every 100 processed IDs or every 10 seconds while waiting
for image jobs. Reports show download, decoding, averaging, and retry-wait time.
These totals overlap because workers run concurrently; they are not elapsed wall
time or estimates of remaining time. Each run scans the local CSV again to find
selected URLs, but it does not download images already saved in the output.

The old API scheduler, rate limiter, metadata cache, JSON dependency, and
`--refresh-cache` option have been removed. `usableObjectListGenerator` remains
available for generating the selection list from the older MetObjects dataset.

Run the regression suite with `mvn test` from `met-color`.
