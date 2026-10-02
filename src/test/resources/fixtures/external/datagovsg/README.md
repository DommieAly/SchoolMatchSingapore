Test fixtures for the data.gov.sg client and the importer tests, in the exact `datastore_search` response shape.
School, CCA and subject rows are copied from the public datasets (Singapore Open Data Licence v1.0, accessed
2 Oct 2026); one CCA school name was changed to a curly apostrophe and double space on purpose, and one
JUNIOR COLLEGE row was added, to test name normalisation and the section filter.
`planning-areas.geojson` is the seed's three simplified planning areas with the raw property names
(`PLN_AREA_N`, `PLN_AREA_C`) of dataset d_4765db0e87b9c86336792efe8a1f7a66. `poll-download.json` points at a
made-up download URL.
