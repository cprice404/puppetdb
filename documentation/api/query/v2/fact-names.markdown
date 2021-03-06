---
title: "PuppetDB 2.0 » API » v2 » Querying Fact Names"
layout: default
canonical: "/puppetdb/latest/api/query/v2/fact-names.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

The `/fact-names` endpoint can be used to retrieve all known fact names.

> **Note:** The v2 API is deprecated. It is recommended that you use the v3 API instead.

## Routes

### `GET /fact-names`

This will return an alphabetical list of all known fact names, *including* those which are
known only for deactivated nodes.

#### Examples

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/v2/fact-names

    ["kernel", "operatingsystem", "osfamily", "uptime"]

## Response Format

The response will be in `application/json`, and will contain an alphabetical
JSON array containing fact names. Each fact name will appear only once,
regardless of how many nodes have that fact.

    [<fact>, <fact>, ..., <fact>, <fact>]
