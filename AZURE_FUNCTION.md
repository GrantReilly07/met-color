# Closest artwork color HTTP function

`src/main/java/com/metcolor/ClosestColorFunction.java` is the Azure Functions
Java entry point. It supports a public, read-only HTTP GET request:

```text
GET /api/closest-color?red=120&green=80&blue=160
```

Every channel must be an integer from 0 through 255. All three parameters are
required. The response uses `Content-Type: application/json` and has this shape:

```json
{
  "objectID": 738077,
  "red": 137,
  "green": 114,
  "blue": 138,
  "distanceSquared": 1929,
  "objectURL": "https://www.metmuseum.org/art/collection/search/738077"
}
```

This is an example from the current color snapshot; results can change when the
CSV changes. `distanceSquared` is `(inputRed - red)^2 + (inputGreen - green)^2 +
(inputBlue - blue)^2`. Zero is an exact RGB match. Equal distances return the
lowest object ID, independently of CSV row order.

Invalid or missing channels return HTTP 400 with an `error` message. Missing,
empty, or invalid color data returns HTTP 503; internal details go to the
function log rather than the response.

## Data and performance

Maven packages the root `objectAverageColors.csv` into the application JAR. The
function reads it once on the first valid request in each Java process, then
reuses an immutable list across requests. Searches scan that in-memory list;
they do not call the Met API or download images. Large source datasets are not
packaged as resources.

Regenerate the colors and rebuild/redeploy to publish a newer snapshot. The
running function does not watch local file changes. Existing generator commands
and output files remain available for that preparation step.

The local CSV search has been measured well below five seconds. Website latency
also includes network and Azure host startup time. An Azure cold start can take
longer than the lookup itself, so the hosting plan and startup configuration
must be considered for a strict end-to-end response-time requirement.

## Build and local run

Use Java 21, Maven, Azure Functions Core Tools v4, and Azurite for the example
local storage connection. The Maven build itself does not require Azure access.

```powershell
mvn package
Copy-Item local.settings.json.example local.settings.json
# Start Azurite before starting the Functions host.
mvn azure-functions:run
```

Then open:

```text
http://localhost:7071/api/closest-color?red=120&green=80&blue=160
```

`local.settings.json` is ignored by Git because real settings can contain
credentials. The example file contains only local development settings.

## Azure and the website

`mvn package` prepares `target/azure-functions/met-color`, including `host.json`,
the function binding metadata, application JAR, and dependencies. The Maven
`functionAppName` property controls the staging folder name; set it to the
actual destination app name when configuring Azure deployment. Target a Java 21
Functions runtime. Review the Azure plugin's subscription and resource-group
configuration before deploying to an existing function app.

For a website hosted on another origin, add that website's origin to the
Function App's CORS allowed origins. The function uses anonymous authorization
so a browser client does not need an embedded function key.

Example frontend call:

```javascript
const query = new URLSearchParams({ red: 120, green: 80, blue: 160 });
const response = await fetch(`${functionAppBaseUrl}/api/closest-color?${query}`);
const result = await response.json();
if (!response.ok) throw new Error(result.error);
console.log(result.objectID, result.objectURL);
```

Microsoft references:

- [Azure Functions Java developer guide](https://learn.microsoft.com/azure/azure-functions/functions-reference-java)
- [Local development with Azure Functions](https://learn.microsoft.com/azure/azure-functions/functions-develop-local)

Run all unit and local HTTP-fixture tests with `mvn test`.
