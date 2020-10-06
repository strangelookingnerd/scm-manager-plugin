package com.cloudogu.scmmanager.scm.api;

import com.cloudogu.scmmanager.BearerHttpAuthentication;
import com.cloudogu.scmmanager.SSHAuthentication;
import com.cloudogu.scmmanager.SshConnection;
import com.cloudogu.scmmanager.SshConnectionFactory;
import com.cloudogu.scmmanager.SshConnectionFailedException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.ning.http.client.AsyncHttpClient;
import de.otto.edison.hal.HalRepresentation;
import de.otto.edison.hal.Link;
import jenkins.plugins.asynchttpclient.AHC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class SshApiClient extends ApiClient {

  private static final Logger LOG = LoggerFactory.getLogger(SshApiClient.class);
  private static final String API_PATH = "/api/v2";

  private final Supplier<AccessToken> fetcher = Suppliers.memoize(this::fetchAccessTokenFromSsh);

  private final AsyncHttpClient client;
  private final String sshUrl;
  private final SSHAuthentication authentication;

  public SshApiClient(String sshUrl, SSHAuthentication authentication) {
    this(AHC.instance(), sshUrl, authentication);
  }

  public SshApiClient(AsyncHttpClient client, String sshUrl, SSHAuthentication authentication) {
    this.client = client;
    this.sshUrl = sshUrl;
    this.authentication = authentication;
  }

  @Override
  public <T> CompletableFuture<T> get(String url, String contentType, Class<T> type) {
    LOG.info("get {} from {}", type.getName(), url);

    AccessToken token = fetcher.get();
    String apiUrl = createApiUrl(token.getApiUrl(), url);

    AsyncHttpClient.BoundRequestBuilder requestBuilder = client.prepareGet(apiUrl);
    BearerHttpAuthentication.authenticate(requestBuilder, token.getAccessToken());
    requestBuilder.addHeader("Accept", contentType);

    return execute(requestBuilder, type);
  }

  @VisibleForTesting
  static String createApiUrl(String apiUrl, String url) {
    if (url.startsWith("http")) {
      return url;
    }
    return createBaseUrl(apiUrl) + url;
  }

  private static String createBaseUrl(String apiUrl) {
    String baseUrl = apiUrl;
    if (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    if (!baseUrl.endsWith(API_PATH)) {
      throw new IllegalStateException("returned api url does not end with /api/v2");
    }

    return baseUrl.substring(0, baseUrl.length() - API_PATH.length());
  }

  private AccessToken fetchAccessTokenFromSsh() {
    LOG.info("connect to {} in order to fetch access token", sshUrl);
    try (SshConnection connection = createConnection()) {
      connection.connect(authentication);
      return connection.command("scm-access-token")
        .withOutput(AccessToken.class)
        .json();
    } catch (IOException ex) {
      throw new SshConnectionFailedException("failed to create ssh connection", ex);
    }
  }

  private SshConnection createConnection() {
    return SshConnectionFactory.create(sshUrl)
      .orElseThrow(() -> new IllegalStateException("could not create ssh connection"));
  }

  public static class AccessToken extends HalRepresentation {

    private String accessToken;

    public String getAccessToken() {
      return accessToken;
    }

    public void setAccessToken(String accessToken) {
      this.accessToken = accessToken;
    }

    public String getApiUrl() {
      return getLinks().getLinkBy("index")
        .map(Link::getHref)
        .orElseThrow(() -> new IllegalStateException("no index link found"));
    }
  }
}
