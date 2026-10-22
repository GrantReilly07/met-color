# Met Color

see project here: [My website](https://grantreilly.me/projects/project-two/)

A Java program that calculates average RGB colors for artworks using 2D mediums in the
Metropolitan Museum of Art collection. It reads image URLs from Huggingface's metmuseum `openaccess.csv`
and downloads images directly, avoiding repeated object API requests.

## Requirements

- Java 21 or newer
- Maven
- Git LFS, to download the two large datasets when cloning this repository

## Clone and run

```sh
git lfs install
git clone https://github.com/GrantReilly07/met-color.git
cd met-color
git lfs pull
mvn test
```

Open the project in a Java IDE and run `com.metcolor.avgColorGenerator.main()`.
No program arguments are required.

## Project files

- `src/main/java/com/metcolor/avgColorGenerator.java`: concurrent image downloads
  and exact average RGB calculations.
- `src/main/java/com/metcolor/ArtworkCsvReader.java`: streaming CSV reader.
- `src/main/java/com/metcolor/usableObjectListGenerator.java`: creates the selected
  ID list from `MetObjects.txt`.
- `usableObjectIDS.txt`: selected artwork IDs.
- `openaccess.csv`: image metadata supplied from a Hugging Face dataset.
- `MetObjects.txt`: the older Met collection CSV used to generate the ID list.
- `objectAverageColors.csv`: saved average colors; new results are appended and
  already-saved IDs are skipped on subsequent runs.
- `objectAverageColors-failures.csv`: error history from image-processing runs.

`openaccess.csv` and `MetObjects.txt` are included through Git LFS. Maven build
output and local IDE settings are excluded.

See [AVG_COLOR_GENERATOR.md](AVG_COLOR_GENERATOR.md) for selection behavior,
download retries, image fallbacks, and progress reporting.

## Website backend

`ClosestColorFunction.java` provides an Azure Functions HTTP endpoint that finds
the closest saved artwork color:

```text
GET /api/closest-color?red=120&green=80&blue=160
```

It packages the saved color CSV, loads it once per Java process, and returns the
closest artwork as JSON. Build the Azure staging package with `mvn package`.
See [AZURE_FUNCTION.md](AZURE_FUNCTION.md) for responses, local setup, CORS, and
deployment preparation.

## Data source

Artwork metadata and image URLs originate from the Metropolitan Museum of Art's
[Open Access collection](https://metmuseum.github.io/). The supplied image
metadata is a dataset snapshot; image availability can change over time.
