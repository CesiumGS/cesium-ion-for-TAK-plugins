package com.cesium.tak.ion;

import com.cesium.tak.ion.http.HttpRequest;
import com.cesium.tak.ion.http.HttpResponse;
import com.cesium.tak.ion.http.HttpTransport;
import com.cesium.tak.ion.json.Json;
import com.cesium.tak.ion.json.JsonException;

import java.io.IOException;
import java.net.URI;

/**
 * Finds the REST API base for an {@link IonServer}, following the same rules
 * as cesium-native's {@code CesiumIonClient::Connection::getApiUrl} so that
 * the ATAK and VR-TAK clients agree:
 *
 * <ol>
 *   <li>An explicit API URL override wins.</li>
 *   <li>Cesium ion Cloud uses {@code https://api.cesium.com/}.</li>
 *   <li>Otherwise read {@code apiHostname} from {@code <server>/config.json}.</li>
 *   <li>If that file is missing or has no {@code apiHostname}, use
 *       {@code <server>/api/}.</li>
 * </ol>
 *
 * See {@code spec/ion-contract/README.md}.
 */
public final class ApiUrlResolver {
    private ApiUrlResolver() {
    }

    public static URI resolve(IonServer server, HttpTransport transport) throws IonException {
        if (server.apiUrlOverride() != null) {
            return server.apiUrlOverride();
        }
        if (server.kind() == IonServer.Kind.CLOUD
                || IonServer.CLOUD_SERVER_URL.getHost().equalsIgnoreCase(server.serverUrl().getHost())) {
            return IonServer.CLOUD_API_URL;
        }

        URI configUrl = server.serverUrl().resolve("config.json");
        HttpResponse response;
        try {
            response = transport.send(HttpRequest.get(configUrl).header("Accept", "application/json").build());
        } catch (IOException failure) {
            throw new IonException(IonException.Kind.NETWORK,
                    "Could not reach the Cesium ion server at " + server.serverUrl() + ": " + failure.getMessage(),
                    -1, failure);
        }
        String apiHostname = null;
        if (response.isSuccessful()) {
            try {
                apiHostname = Json.parseObject(response.bodyText()).optNonBlank("apiHostname");
            } catch (JsonException notJson) {
                // Some deployments answer every path with the web app's HTML;
                // treat that the same as a missing config.json.
                apiHostname = null;
            }
        }
        if (apiHostname == null) {
            return server.serverUrl().resolve("api/");
        }
        return fromApiHostname(server, apiHostname);
    }

    static URI fromApiHostname(IonServer server, String apiHostname) throws IonException {
        String candidate = apiHostname.contains("://")
                ? apiHostname
                : server.serverUrl().getScheme() + "://" + apiHostname;
        return IonServer.normalize(candidate, "API URL from config.json", server.allowsCleartext());
    }
}
