package eu.comsode.unifiedviews.plugins.extractor.skgovcrp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.unifiedviews.dataunit.DataUnit;
import eu.unifiedviews.dataunit.rdf.WritableRDFDataUnit;
import eu.unifiedviews.dpu.DPU;
import eu.unifiedviews.dpu.DPUException;
import eu.unifiedviews.helpers.dpu.config.ConfigHistory;
import eu.unifiedviews.helpers.dpu.context.ContextUtils;
import eu.unifiedviews.helpers.dpu.exec.AbstractDpu;
import eu.unifiedviews.helpers.dpu.rdf.EntityBuilder;

/**
 * Main data processing unit class.
 */
@DPU.AsExtractor
public class SkGovCRP extends AbstractDpu<SkGovCRPConfig_V1> {
    private static final Logger LOG = LoggerFactory.getLogger(SkGovCRP.class);

    private static final String INPUT_URL = "http://www.crp.gov.sk/5-sk/register-projektov/?page=0";

    private static final String BASE_URI = "http://localhost/";

    private static final String PURL_URI = "http://purl.org/procurement/public-contracts#";

    private static Map<String, Integer> keys = new HashMap<String, Integer>();

    private static URI inputUri = null;

    private static String sessionId = null;

    @DataUnit.AsOutput(name = "rdfOutput")
    public WritableRDFDataUnit rdfOutput;

    public SkGovCRP() {
        super(SkGovCRPVaadinDialog.class, ConfigHistory.noHistory(SkGovCRPConfig_V1.class));
    }

    @Override
    protected void innerExecute() throws DPUException {
        initializeKeysMap();
        RepositoryConnection connection = null;
        int pageCounter = 0;

        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            inputUri = new URI(INPUT_URL);

            org.openrdf.model.URI graph = rdfOutput.addNewDataGraph("skGovCRPRdfData");
            connection = rdfOutput.getConnection();
            ValueFactory vf = ValueFactoryImpl.getInstance();

            HttpGet httpGet = new HttpGet(INPUT_URL);
            httpGet.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            httpGet.setHeader("Accept-Encoding", "gzip, deflate");
            httpGet.setHeader("Accept-Language", "en-US,cs;q=0.7,en;q=0.3");
            httpGet.setHeader("Connection", "keep-alive");
            httpGet.setHeader("Host", (new URL(INPUT_URL)).getHost());
            httpGet.setHeader("Referer", INPUT_URL);
            httpGet.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:38.0) Gecko/20100101 Firefox/38.0");
            CloseableHttpResponse response1 = httpclient.execute(httpGet);
            LOG.debug(String.format("GET response status line: %s", response1.getStatusLine()));
            int responseCode = response1.getStatusLine().getStatusCode();
            StringBuilder headerSb = new StringBuilder();
            for (Header h : response1.getAllHeaders()) {
                headerSb.append("Key : " + h.getName() + " ,Value : " + h.getValue());
            }
            LOG.debug(headerSb.toString());

            Header[] cookies = response1.getHeaders("Set-Cookie");
            String[] cookieParts = cookies[0].getValue().split("; ");
            sessionId = cookieParts[0];
            String response = null;
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LOG.error("GET request not worked");
                throw new Exception("GET request not worked");
            }
            HttpEntity entity = null;
            try {
                entity = response1.getEntity();
                response = EntityUtils.toString(entity, "UTF-8");
            } finally {
                EntityUtils.consumeQuietly(entity);
                response1.close();
            }
            int projectCounter = 0;
            do {
                LOG.debug(String.format("Server response:\n%s", response));
                System.out.print(response);
                Document doc = Jsoup.parse(response);

                Element content = doc.select("table.table_list").first();
                if (content == null) {
                    break;
                }
                Elements links = content.select("tr");
                boolean first = true;
                for (Element link : links) {
                    if (first) {
                        first = false;
                        continue;
                    }
                    Element tdDetail = link.select("td.cell2").first();
                    Element detailHref = tdDetail.select("a[href]").first();
                    URIBuilder builder = new URIBuilder();
                    builder.setHost(inputUri.getHost()).setScheme(inputUri.getScheme()).setPath(detailHref.attr("href"));
                    String projectDetail = getDetailInfo(httpclient, builder.build());
                    keys.put("linka-na-detail", keys.get("linka-na-detail") + 1);

                    Element projectDetailContent = Jsoup.parse(projectDetail);
                    System.out.print(projectDetailContent.html());
                    UUID uuid = UUID.randomUUID();
                    org.openrdf.model.URI uri = vf.createURI(BASE_URI + uuid.toString());
                    EntityBuilder eb = new EntityBuilder(uri, vf);
                    eb.property(RDF.TYPE, vf.createURI(PURL_URI));
                    eb.property(vf.createURI(BASE_URI + "linka-na-detail"), builder.build().toString());

                    eb = getDetails(projectDetailContent, eb, vf, httpclient);
                    connection.add(eb.asStatements(), graph);
                    projectCounter++;
                    LOG.debug("Number of scrapped projects: " + Integer.toString(projectCounter));

                }
                pageCounter++;
                URIBuilder nextPageLink = new URIBuilder();
                nextPageLink.setHost(inputUri.getHost()).setScheme(inputUri.getScheme()).setPath(inputUri.getPath()).setParameter("page", Integer.toString(pageCounter));
                response = getDetailInfo(httpclient, nextPageLink.build());
            } while (true);

        } catch (Exception ex) {
            throw ContextUtils.dpuException(ctx, ex, "SkMartinContracts.execute.exception");
        }

    }

    private static String getDetailInfo(CloseableHttpClient client, URI url) throws IOException {
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        httpGet.setHeader("Accept-Encoding", "gzip, deflate");
        httpGet.setHeader("Accept-Language", "en-US,cs;q=0.7,en;q=0.3");
        httpGet.setHeader("Connection", "keep-alive");
        httpGet.setHeader("Cookie", sessionId + "; ys-browserCheck=b%3A1");
        httpGet.setHeader("Host", (new URL(INPUT_URL)).getHost());
        httpGet.setHeader("Referer", (new URL(INPUT_URL)).getHost());
        httpGet.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:38.0) Gecko/20100101 Firefox/38.0");

        String responseDoc = null;
        try (CloseableHttpResponse response2 = client.execute(httpGet)) {

            LOG.debug("GET Response Code :: " + response2.getStatusLine().getStatusCode());

            LOG.debug("Printing Response Header...\n");
            StringBuilder headerSb = new StringBuilder();
            for (Header h : response2.getAllHeaders()) {
                headerSb.append("Key : " + h.getName() + " ,Value : " + h.getValue());
            }
            LOG.debug(headerSb.toString());
            HttpEntity entity = null;
            try {
                entity = response2.getEntity();
                if (response2.getStatusLine().getStatusCode() == HttpURLConnection.HTTP_OK) { //success
                    responseDoc = EntityUtils.toString(entity, "UTF-8");
                } else {
                    LOG.error("POST request not worked");
                }
            } finally {
                EntityUtils.consumeQuietly(entity);
            }
        }
        return responseDoc;
    }

    private static void initializeKeysMap() {
        keys.put("nazov-projektu", 0);
        keys.put("poskytovatel", 0);
        keys.put("meno-prijmatela", 0);
        keys.put("ico-prijmatela", 0);
        keys.put("typ-poskytnutej-pomoci", 0);
        keys.put("vyska-pomoci", 0);
        keys.put("datum-zacatia", 0);
        keys.put("datum-ukoncenia", 0);
        keys.put("datum-zverejnenia", 0);
        keys.put("miesto-realizacie", 0);
        keys.put("zmluvy", 0);
        keys.put("linka-na-detail", 0);
    }

    private EntityBuilder getDetails(Element projectDetail, EntityBuilder eb, ValueFactory vf, CloseableHttpClient httpClient) throws Exception {
        Element content = projectDetail.select("div#content").first();
        Element detailPage = content.select("div#page").first();
        Element title = detailPage.select("h1").first();
        if (title != null && StringUtils.isNotBlank(title.text())) {
            keys.put("nazov-projektu", keys.get("nazov-projektu") + 1);
            eb.property(vf.createURI(BASE_URI + "nazov-projektu"), title.text());
        }
        Element dates = detailPage.select("div.area1").first();
        Element datesTable = dates.select("table").first();
        Elements datesTableRows = datesTable.select("tr");
        for (Element tr : datesTableRows) {
            String key = slugify(tr.select("th").text());
            keys.put(key, keys.get(key));
            String value = tr.select("td").text();
            eb.property(vf.createURI(BASE_URI + key), value);
        }
        Element identProj = detailPage.select("div.area3").first();
        Element identProjTable = identProj.select("table").first();
        Elements identProjTableRows = identProjTable.select("tr");
        for (Element tr : identProjTableRows) {
            String key = slugify(tr.select("th").text());
            keys.put(key, keys.get(key));
            String value = tr.select("td").text();
            eb.property(vf.createURI(BASE_URI + key), value);
        }
        Element cenovePlnenie = detailPage.select("div.area4").first();
        Element cenovePlnenieDiv = cenovePlnenie.select("div.last").first();
        String key = slugify(cenovePlnenieDiv.select("strong").text());
        keys.put(key, keys.get(key));
        String value = cenovePlnenieDiv.select("span").text();
        eb.property(vf.createURI(BASE_URI + key), value);

        Element zmluvy = detailPage.select("div.area7").first();
        if (zmluvy != null) {
            Element zmluvyTable = zmluvy.select("table.table_list").first();
            Elements zmluvyTableRows = zmluvyTable.select("tr");
            boolean tableHeader = true;
            String zmluvyKey = null;
            for (Element tr : zmluvyTableRows) {
                if (tableHeader) {
                    zmluvyKey = slugify(zmluvy.select("h2").first().text());
                    tableHeader = false;
                    continue;
                }
                Element td = tr.select("td.cell2").first();
                URIBuilder builder = new URIBuilder();
                String zmluvaLink = td.select("a").first().attr("href");
                builder.setHost(inputUri.getHost()).setScheme(inputUri.getScheme()).setPath(zmluvaLink);
                String zmluvaDetail = null;
                try {
                    zmluvaDetail = getDetailInfo(httpClient, builder.build());
                } catch (IOException | URISyntaxException ex) {
                    LOG.error("Problem getting detail info from Zmluva!");
                }
                Element zmluvaDetailContent = Jsoup.parse(zmluvaDetail);
                Element zmluvaDetailDiv = zmluvaDetailContent.select("div#content").first();
                Elements prilohy = zmluvaDetailDiv.select("a[href]");
                for (Element priloha : prilohy) {
                    keys.put(zmluvyKey, keys.get(zmluvyKey));
                    String prilohaLink = priloha.attr("href");
                    URIBuilder prilohaLinkBuilder = new URIBuilder();
                    prilohaLinkBuilder.setHost(inputUri.getHost()).setScheme(inputUri.getScheme()).setPath(prilohaLink);
                    LOG.debug(prilohaLinkBuilder.build().toString());
                    eb.property(vf.createURI(BASE_URI + key), prilohaLinkBuilder.build().toString());
                }

                eb.property(vf.createURI(BASE_URI + key), value);
            }
        } else {
            LOG.warn("No contracts found for this project.");
        }
        return eb;
    }

    private static String slugify(String input) {
        String result = StringUtils.stripAccents(input);
        result = StringUtils.lowerCase(result).trim();
        result = result.replaceAll("[^a-zA-Z0-9\\s]", "");
        result = result.replaceAll("\\b\\s+", "-");
        return result;

    }

}
