package sg.schoolmatch.boundary.external.datagovsg;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sg.schoolmatch.boundary.external.DataGovSgInterface;
import sg.schoolmatch.boundary.external.DataGovSgRecord;
import sg.schoolmatch.config.AppProperties;
import sg.schoolmatch.error.ExternalServiceUnavailableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live {@link DataGovSgInterface} (app.external.datagovsg.mode=live; the import profile), DC-12, DC-33.
 * <ul>
 *   <li>Tables: {@code GET {app.datagovsg.base-url}/api/action/datastore_search?resource_id=<id>&limit=1000&offset=<n>},
 *       page by page until {@code result.total} rows are read.</li>
 *   <li>{@link #fetchSchools()} keeps the rows whose {@code mainlevel_code} contains "SECONDARY" or starts with
 *       "MIXED LEVEL": on 2 Oct 2026 that is 147 of 337 schools (117 SECONDARY (S1-S5), 16 SECONDARY (S1-S4),
 *       10 MIXED LEVEL (S1-JC2), 3 MIXED LEVEL (P1-S4), 1 MIXED LEVEL (S1-S5, JC1-JC2)).</li>
 *   <li>Planning areas: {@code GET {app.datagovsg.open-api-base-url}/v1/public/api/datasets/<id>/poll-download}
 *       answers {@code {"code":0,"data":{"url":<signed download URL>}}}; that URL is fetched as is.</li>
 * </ul>
 * Without an API key data.gov.sg answers HTTP 429 when called faster than about one request per 3 s, so calls are
 * spaced 3 s apart and a 429 is retried once after 15 s. The optional key ({@code DATAGOVSG_API_KEY}) is sent in the
 * {@code x-api-key} header to data.gov.sg only (never to the download URL) and never logged.
 * Failures throw {@code ExternalServiceUnavailableException("data.gov.sg", e)}.
 */
@Component
@ConditionalOnProperty(name = "app.external.datagovsg.mode", havingValue = "live")
public class DataGovSgClient implements DataGovSgInterface {

    public static final String SCHOOLS_DATASET = "d_688b934f82c1059ed0a6993d2a829089";
    public static final String CCAS_DATASET = "d_9aba12b5527843afb0b2e8e4ed6ac6bd";
    public static final String SUBJECTS_DATASET = "d_f1d144e423570c9d84dbc5102c2e664d";
    public static final String PLANNING_AREAS_DATASET = "d_4765db0e87b9c86336792efe8a1f7a66";

    static final String SERVICE = "data.gov.sg";
    static final String API_KEY_HEADER = "x-api-key";
    static final int PAGE_SIZE = 1000;
    static final long MIN_INTERVAL_MS = 3000;
    static final long RETRY_WAIT_MS = 15_000;
    private static final int MAX_PAGES = 100;   // 100 000 rows: far more than any of the datasets has

    private final RestClient restClient;
    private final String baseUrl;
    private final String openApiBaseUrl;
    private final String apiKey;   // "" when not set
    private final JsonMapper json = JsonMapper.builder().build();
    private final int pageSize;
    private final long minIntervalMillis;
    private final long retryWaitMillis;
    private long lastCallMillis;   // guarded by this

    @Autowired
    public DataGovSgClient(RestClient.Builder restClientBuilder, AppProperties props) {
        this(restClientBuilder, props, PAGE_SIZE, MIN_INTERVAL_MS, RETRY_WAIT_MS);
    }

    /** For tests: smaller pages and no pauses. */
    DataGovSgClient(RestClient.Builder restClientBuilder, AppProperties props, int pageSize,
                    long minIntervalMillis, long retryWaitMillis) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = props.datagovsg().baseUrl();
        this.openApiBaseUrl = props.datagovsg().openApiBaseUrl();
        this.apiKey = props.datagovsg().apiKey();
        this.pageSize = pageSize;
        this.minIntervalMillis = minIntervalMillis;
        this.retryWaitMillis = retryWaitMillis;
    }

    /** General information of schools: secondary and mixed-level schools only (see class comment). */
    @Override
    public List<DataGovSgRecord> fetchSchools() {
        return fetchAll(SCHOOLS_DATASET).stream().filter(DataGovSgClient::isSecondary).toList();
    }

    @Override
    public List<DataGovSgRecord> fetchSchoolCcas() {
        return fetchAll(CCAS_DATASET);
    }

    @Override
    public List<DataGovSgRecord> fetchSchoolSubjects() {
        return fetchAll(SUBJECTS_DATASET);
    }

    /** The URA Master Plan 2019 planning-area boundaries (no sea) as GeoJSON text. */
    @Override
    public String fetchDistrictsGeoJson() {
        JsonNode poll = readJson(get(openApiBaseUrl + "/v1/public/api/datasets/{id}/poll-download",
                PLANNING_AREAS_DATASET));
        String url = poll.path("data").path("url").asString("");
        if (poll.path("code").asInt(-1) != 0 || url.isBlank()) {
            throw new ExternalServiceUnavailableException(SERVICE, new IllegalStateException(
                    "poll-download gave no download URL: code=" + poll.path("code") + ", errorMsg="
                            + poll.path("errorMsg")));
        }
        try {
            String geoJson = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
            if (geoJson == null || geoJson.isBlank()) {
                throw new ExternalServiceUnavailableException(SERVICE, new IllegalStateException("empty download"));
            }
            return geoJson;
        } catch (RestClientException e) {
            throw new ExternalServiceUnavailableException(SERVICE, e);
        }
    }

    /** True for a school whose {@code mainlevel_code} contains SECONDARY or starts with MIXED LEVEL. */
    static boolean isSecondary(DataGovSgRecord school) {
        String level = school.get("mainlevel_code");
        if (level == null) {
            return false;
        }
        String upper = level.toUpperCase(Locale.ROOT);
        return upper.contains("SECONDARY") || upper.startsWith("MIXED LEVEL");
    }

    /** Every row of one datastore table, page by page. */
    private List<DataGovSgRecord> fetchAll(String resourceId) {
        List<DataGovSgRecord> rows = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int offset = page * pageSize;
            JsonNode root = readJson(get(baseUrl + "/api/action/datastore_search?resource_id={id}&limit={limit}"
                    + "&offset={offset}", resourceId, pageSize, offset));
            if (!root.path("success").asBoolean(false)) {
                throw new ExternalServiceUnavailableException(SERVICE, new IllegalStateException(
                        "datastore_search for " + resourceId + " failed: " + root.path("error")));
            }
            JsonNode result = root.path("result");
            List<JsonNode> records = result.path("records").values().stream().toList();
            records.forEach(record -> rows.add(toRecord(record)));
            int total = result.path("total").asInt(0);
            if (records.isEmpty() || offset + records.size() >= total) {
                return rows;
            }
        }
        throw new ExternalServiceUnavailableException(SERVICE, new IllegalStateException(
                resourceId + " has more than " + MAX_PAGES + " pages"));
    }

    /** One JSON row → field name → text (numbers as text, JSON null as null). */
    private static DataGovSgRecord toRecord(JsonNode record) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.properties().forEach(e -> {
            JsonNode value = e.getValue();
            fields.put(e.getKey(), value == null || value.isNull() ? null
                    : value.isValueNode() ? value.asString() : value.toString());
        });
        return new DataGovSgRecord(fields);
    }

    /** GET to data.gov.sg with the key header (when set), spaced out, and one retry after HTTP 429. */
    private String get(String uriTemplate, Object... variables) {
        try {
            try {
                return getOnce(uriTemplate, variables);
            } catch (HttpClientErrorException.TooManyRequests tooFast) {
                Thread.sleep(retryWaitMillis);
                return getOnce(uriTemplate, variables);
            }
        } catch (RestClientException e) {
            throw new ExternalServiceUnavailableException(SERVICE, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceUnavailableException(SERVICE, e);
        }
    }

    private String getOnce(String uriTemplate, Object... variables) throws InterruptedException {
        waitForTurn();
        RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uriTemplate, variables);
        if (!apiKey.isBlank()) {
            request = request.header(API_KEY_HEADER, apiKey);
        }
        return request.retrieve().body(String.class);
    }

    private JsonNode readJson(String body) {
        try {
            JsonNode root = body == null ? null : json.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ExternalServiceUnavailableException(SERVICE, new IllegalStateException("not a JSON object"));
            }
            return root;
        } catch (JacksonException e) {
            throw new ExternalServiceUnavailableException(SERVICE, e);
        }
    }

    /** Keeps data.gov.sg calls at least {@code minIntervalMillis} apart. */
    private synchronized void waitForTurn() throws InterruptedException {
        long wait = lastCallMillis + minIntervalMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastCallMillis = System.currentTimeMillis();
    }
}
